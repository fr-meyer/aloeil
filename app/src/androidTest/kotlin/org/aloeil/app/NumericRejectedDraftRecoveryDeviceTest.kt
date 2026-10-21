package org.aloeil.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.aloeil.app.data.DraftCodec
import org.aloeil.app.data.DraftRow
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingAad
import org.aloeil.app.data.ReadingDao
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingValue
import org.aloeil.app.data.SealedPayload
import org.aloeil.app.data.withDecryptedPayload
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reopen disk-backed synthetic data with fresh UI/repository/cipher owners, never a retained VM. */
@RunWith(AndroidJUnit4::class)
class NumericRejectedDraftRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun newEntryRecoversRejectedDraftAndThenAnExplicitEdit() = exercise(Step.VALUE)
    @Test fun correctionRecoversRejectedDraftAndThenAnExplicitEdit() = exercise(Step.CORRECT_VALUE)

    @Test fun acceptedEditReplacesAHeldRejectedCheckpointCoherently() = exercise(Step.VALUE, holdRejectedWrite = true)

    private fun exercise(inputStep: Step, holdRejectedWrite: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-rejected-draft-" + UUID.randomUUID() + ".db"
        var database = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        val holdNextWrite = AtomicBoolean(false)
        val heldWriteEntered = AtomicBoolean(false)
        val releaseHeldWrite = CompletableDeferred<Unit>()
        val real = database.readings()
        val cipher = SyntheticCipher()
        val heldDao = object : ReadingDao by real {
            override suspend fun saveDraft(row: DraftRow) {
                real.saveDraft(row)
                val rejected = cipher.withDecryptedPayload(
                    SealedPayload(row.nonce, row.ciphertext), ReadingAad.draft(),
                ) { DraftCodec.decode(it).inputRejected }
                if (rejected && holdNextWrite.compareAndSet(true, false)) {
                    heldWriteEntered.set(true)
                    // Hold only after the real transaction commits, never inside Room.
                    withContext(NonCancellable) { withTimeout(30_000) { releaseHeldWrite.await() } }
                }
            }
        }
        var repository = ReadingRepository(heldDao, cipher)
        var capture = CaptureUiState()
        val owners = mutableListOf(capture)
        fun next() = hasText(context.getString(R.string.continue_action)) and hasClickAction()
        fun tap(id: Int) {
            val target = hasText(context.getString(id)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(target).performScrollTo().performClick()
        }
        fun showFreshOwner() {
            val currentRepository = repository
            val currentCapture = capture
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { AloeilApp(currentRepository, captureState = currentCapture) }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun waitForDraft(rejected: Boolean, input: String) {
            compose.waitUntil(timeoutMillis = 10_000) {
                runBlocking {
                    // Inspect the committed encrypted row directly. recoverDraft() deliberately
                    // waits behind this repository's writer mutex, while the held-write case
                    // pauses only after Room has committed the row under test.
                    database.readings().draft()?.let { row ->
                        cipher.withDecryptedPayload(
                            SealedPayload(row.nonce, row.ciphertext), ReadingAad.draft(),
                            DraftCodec::decode,
                        )
                    }?.let {
                        it.inputRejected == rejected && it.input == input && it.step == inputStep.name
                    } == true
                }
            }
        }
        fun discardOwnersAndReopen() {
            val previous = capture
            compose.activityRule.scenario.onActivity { it.setContent { Text("Synthetic owner discarded") } }
            compose.waitForIdle()
            compose.runOnIdle { previous.disposeEphemeral() }
            database.close()
            database = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
            repository = ReadingRepository(database.readings(), SyntheticCipher())
            capture = CaptureUiState()
            owners.add(capture)
            check(capture !== previous && !capture.initialized && capture.valueState.value.isEmpty())
            check(capture.valueErrorState.value == null)
            showFreshOwner()
        }
        try {
            val existing = runBlocking {
                repository.startSitting("synthetic-rejected-sitting")
                repository.record("synthetic-rejected-original", "synthetic-rejected-sitting", Eye.LEFT, "12.3")
            }
            compose.runOnIdle {
                capture.initialized = true
                capture.stepState.value = inputStep
                capture.sittingIdState.value = existing.sittingId
                capture.readingIdState.value = if (inputStep == Step.VALUE) "synthetic-rejected-new" else existing.id
                capture.savedState.value = if (inputStep == Step.VALUE) null else existing
                capture.eyeState.value = Eye.LEFT
                capture.valueState.value = "12.3"
                capture.hasOpenSittingState.value = true
            }
            showFreshOwner()
            waitForDraft(rejected = false, input = "12.3")
            if (holdRejectedWrite) holdNextWrite.set(true)
            compose.onNode(hasSetTextAction()).performScrollTo()
                .performTextReplacement("1".repeat(ReadingValue.MAX_LENGTH + 1))
            compose.onNode(next()).assertIsNotEnabled()
            // Wait for the actual UI-produced encrypted checkpoint, not a manually seeded flag.
            waitForDraft(rejected = true, input = "12.3")
            if (holdRejectedWrite) {
                compose.waitUntil(timeoutMillis = 10_000) { heldWriteEntered.get() }
                compose.onNode(hasSetTextAction()).performTextReplacement("13.4")
                compose.onNode(next()).assertIsEnabled()
                compose.runOnIdle {
                    check(capture.valueState.value == "13.4" && capture.valueErrorState.value == null)
                }
                // The pending accepted checkpoint waits behind the older write's mutex.
                // Its immutable frame must not inherit the older rejection flag or input.
                waitForDraft(rejected = true, input = "12.3")
                releaseHeldWrite.complete(Unit)
                waitForDraft(rejected = false, input = "13.4")
                discardOwnersAndReopen()
                compose.onNode(hasSetTextAction()).assertTextContains("13.4")
                compose.onNode(next()).assertIsEnabled()
                runBlocking { check(repository.all().single().revision == 1L) }
                return
            }
            discardOwnersAndReopen()
            compose.onNode(hasSetTextAction()).performScrollTo().assertTextContains("12.3")
            compose.onNode(hasText(context.getString(R.string.error_length))).assertExists()
            compose.onNode(next()).assertIsNotEnabled()
            compose.onNode(next()).performScrollTo().performClick()
            compose.runOnIdle {
                check(capture.valueErrorState.value == R.string.error_length)
                check(capture.stepState.value == inputStep)
            }
            compose.onNode(hasSetTextAction()).performTextReplacement("12.3")
            compose.onNode(next()).assertIsNotEnabled()
            runBlocking { check(repository.all().single().revision == 1L) }

            compose.onNode(hasSetTextAction()).performTextReplacement("13.4")
            compose.onNode(next()).assertIsEnabled()
            waitForDraft(rejected = false, input = "13.4")
            // Accepted recovery also reloads from disk through another completely fresh owner.
            discardOwnersAndReopen()
            compose.onNode(hasSetTextAction()).performScrollTo().assertTextContains("13.4")
            compose.onNode(next()).assertIsEnabled()
            compose.runOnIdle { check(capture.valueErrorState.value == null) }
            tap(R.string.continue_action)
            if (inputStep == Step.VALUE) {
                tap(R.string.continue_action)
                tap(R.string.save_reading)
            } else tap(R.string.save_correction)
            compose.waitUntil(timeoutMillis = 10_000) {
                capture.stepState.value == (if (inputStep == Step.VALUE) Step.SAVED else Step.CORRECT_SAVED) &&
                    !capture.busyState.value
            }
            runBlocking {
                val rows = repository.all()
                if (inputStep == Step.VALUE) {
                    check(rows.size == 2 && rows.single { it.id == "synthetic-rejected-new" }.value == "13.4")
                    check(rows.single { it.id == existing.id }.revision == 1L)
                } else {
                    check(rows.single().revision == 2L && rows.single().value == "13.4")
                }
            }
        } finally {
            releaseHeldWrite.complete(Unit)
            compose.activityRule.scenario.onActivity { it.setContent { Text("Synthetic recovery test complete") } }
            compose.waitForIdle()
            compose.runOnIdle { owners.forEach { it.disposeEphemeral() } }
            database.close()
            context.deleteDatabase(name)
        }
    }
}
