package org.aloeil.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.aloeil.app.data.Eye
import org.aloeil.app.data.OutboxRow
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingCipher
import org.aloeil.app.data.ReadingDao
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingRow
import org.aloeil.app.data.ReadingVersionRow
import org.aloeil.app.data.SittingRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity recreation with synthetic DAO returns held strictly outside completed transactions. */
@RunWith(AndroidJUnit4::class)
class CaptureMutationLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private enum class Mutation { START, SAVE, FINISH, CORRECTION, DELETE }

    private class ReturnGate {
        val entered = AtomicBoolean(false)
        private var release = CompletableDeferred<Unit>()
        private val armed = AtomicBoolean(false)
        fun arm() { check(!armed.getAndSet(true)); entered.set(false); release = CompletableDeferred() }
        fun release() { release.complete(Unit) }
        suspend fun afterCommit() {
            if (!armed.compareAndSet(true, false)) return
            entered.set(true)
            // The real Room call has returned/committed. This bounded test-only hold
            // exposes cancellation after commit without holding a database transaction.
            withContext(NonCancellable) { withTimeout(30_000) { release.await() } }
        }
    }

    private class HeldDao(private val real: ReadingDao, private val mutation: Mutation, private val gate: ReturnGate) : ReadingDao by real {
        override suspend fun insertOpenSitting(row: SittingRow, cipher: ReadingCipher) {
            real.insertOpenSitting(row, cipher)
            if (mutation == Mutation.START) gate.afterCommit()
        }
        override suspend fun saveOnPhone(reading: ReadingRow, outbox: OutboxRow, sittingId: String, cipher: ReadingCipher): ReadingRow {
            val result = real.saveOnPhone(reading, outbox, sittingId, cipher)
            if (mutation == Mutation.SAVE) gate.afterCommit()
            return result
        }
        override suspend fun updateSittingIfUnchanged(id: String, oldNonce: ByteArray, oldCiphertext: ByteArray, newNonce: ByteArray, newCiphertext: ByteArray): Int {
            val result = real.updateSittingIfUnchanged(id, oldNonce, oldCiphertext, newNonce, newCiphertext)
            if (mutation == Mutation.FINISH) gate.afterCommit()
            return result
        }
        override suspend fun applyCorrection(operationId: String, expectedRevision: Long, updated: ReadingRow, priorVersion: ReadingVersionRow, outbox: OutboxRow): Boolean {
            val result = real.applyCorrection(operationId, expectedRevision, updated, priorVersion, outbox)
            if (mutation == Mutation.CORRECTION) gate.afterCommit()
            return result
        }
        override suspend fun deleteReading(id: String, expectedRevision: Long): Boolean {
            val result = real.deleteReading(id, expectedRevision)
            if (mutation == Mutation.DELETE) gate.afterCommit()
            return result
        }
    }

    private fun tap(id: Int) {
        val node = hasText(context.getString(id)) and hasClickAction() and isEnabled()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(node).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(node).performScrollTo().performClick()
    }

    private fun waitFor(id: Int) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(context.getString(id))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun attach(repository: ReadingRepository): CaptureUiState {
        lateinit var capture: CaptureUiState
        compose.activityRule.scenario.onActivity { activity ->
            capture = ViewModelProvider(activity).get("synthetic-held-mutation", CaptureUiState::class.java)
            activity.setContent { AloeilApp(repository, captureState = capture) }
        }
        return capture
    }

    private fun recreate(repository: ReadingRepository, capture: CaptureUiState, disabledAction: Int) {
        compose.activityRule.scenario.recreate()
        check(attach(repository) === capture)
        check(capture.busyState.value)
        val action = hasText(context.getString(disabledAction)) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(action).performScrollTo().assertIsNotEnabled()
    }

    private fun seedSaved(capture: CaptureUiState, reading: Reading) {
        compose.runOnIdle {
            capture.initialized = true
            capture.sittingIdState.value = reading.sittingId
            capture.hasOpenSittingState.value = true
            capture.readingIdState.value = reading.id
            capture.eyeState.value = reading.eye
            capture.valueState.value = reading.value
            capture.savedState.value = reading
            capture.stepState.value = Step.SAVED
        }
    }

    private fun dispose(capture: CaptureUiState?) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { Text("Synthetic test complete") } }
        capture?.disposeEphemeral()
    }

    @Test
    fun committedStartReturnsToRetainedOwnerAfterRecreation() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val gate = ReturnGate()
        val repository = ReadingRepository(HeldDao(db.readings(), Mutation.START, gate), SyntheticCipher())
        var capture: CaptureUiState? = null
        try {
            val owner = attach(repository)
            capture = owner
            gate.arm()
            tap(R.string.start_sitting)
            compose.waitUntil(timeoutMillis = 10_000) { gate.entered.get() }
            val sitting = runBlocking { repository.openSitting() ?: error("Synthetic start did not commit") }
            recreate(repository, owner, R.string.start_sitting)
            gate.release()
            waitFor(R.string.choose_eye)
            check(owner.sittingIdState.value == sitting.id && owner.hasOpenSittingState.value)
            tap(R.string.left_eye)
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("12.3")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            tap(R.string.save_reading)
            waitFor(R.string.saved_on_phone)
            runBlocking {
                check(repository.allSittings().size == 1)
                check(repository.all().single().sittingId == sitting.id)
            }
        } finally { gate.release(); dispose(capture); db.close() }
    }

    @Test
    fun committedSaveReturnsOnceAfterRecreation() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val gate = ReturnGate()
        val repository = ReadingRepository(HeldDao(db.readings(), Mutation.SAVE, gate), SyntheticCipher())
        var capture: CaptureUiState? = null
        try {
            val owner = attach(repository)
            capture = owner
            tap(R.string.start_sitting)
            tap(R.string.left_eye)
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("12.3")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            gate.arm()
            tap(R.string.save_reading)
            compose.waitUntil(timeoutMillis = 10_000) { gate.entered.get() }
            runBlocking { check(repository.all().size == 1) }
            recreate(repository, owner, R.string.saving)
            gate.release()
            waitFor(R.string.saved_on_phone)
            check(owner.savedState.value?.revision == 1L)
            runBlocking { check(repository.all().size == 1 && db.readings().dueOutbox(Long.MAX_VALUE).size == 1) }
        } finally { gate.release(); dispose(capture); db.close() }
    }

    @Test
    fun committedFinishStartsANewSittingAfterRecreation() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val gate = ReturnGate()
        val repository = ReadingRepository(HeldDao(db.readings(), Mutation.FINISH, gate), SyntheticCipher())
        var capture: CaptureUiState? = null
        try {
            val reading = runBlocking {
                repository.startSitting("synthetic-finish-sitting")
                repository.record("synthetic-finish-reading", "synthetic-finish-sitting", Eye.LEFT, "12.3")
            }
            val owner = attach(repository)
            capture = owner
            waitFor(R.string.resume_sitting)
            seedSaved(owner, reading)
            tap(R.string.finish_sitting)
            gate.arm()
            tap(R.string.finish)
            compose.waitUntil(timeoutMillis = 10_000) { gate.entered.get() }
            runBlocking { check(repository.openSitting() == null) }
            recreate(repository, owner, R.string.finish)
            gate.release()
            waitFor(R.string.sitting_finished)
            check(!owner.hasOpenSittingState.value)
            tap(R.string.start_sitting)
            waitFor(R.string.choose_eye)
            runBlocking {
                val open = repository.openSitting() ?: error("New synthetic sitting missing")
                check(open.id != reading.sittingId && owner.sittingIdState.value == open.id)
                check(repository.allSittings().size == 2)
            }
        } finally { gate.release(); dispose(capture); db.close() }
    }

    @Test
    fun committedCorrectionAndUndoReturnCurrentRevisionAfterRecreation() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val gate = ReturnGate()
        val repository = ReadingRepository(HeldDao(db.readings(), Mutation.CORRECTION, gate), SyntheticCipher())
        var capture: CaptureUiState? = null
        try {
            val reading = runBlocking {
                repository.startSitting("synthetic-correction-sitting")
                repository.record("synthetic-correction-reading", "synthetic-correction-sitting", Eye.LEFT, "12.3")
            }
            val owner = attach(repository)
            capture = owner
            waitFor(R.string.resume_sitting)
            seedSaved(owner, reading)
            tap(R.string.correct_reading)
            tap(R.string.correct_value)
            compose.onNode(hasSetTextAction()).performTextReplacement("13.4")
            tap(R.string.continue_action)
            gate.arm()
            tap(R.string.save_correction)
            compose.waitUntil(timeoutMillis = 10_000) { gate.entered.get() }
            runBlocking { check(repository.all().single().revision == 2L) }
            recreate(repository, owner, R.string.correction_saving)
            gate.release()
            waitFor(R.string.correction_saved)
            check(owner.savedState.value?.revision == 2L)
            gate.arm()
            tap(R.string.undo_correction)
            compose.waitUntil(timeoutMillis = 10_000) { gate.entered.get() }
            runBlocking { check(repository.all().single().revision == 3L) }
            recreate(repository, owner, R.string.undo_correction)
            gate.release()
            waitFor(R.string.undo_done)
            check(owner.savedState.value?.revision == 3L)
            runBlocking {
                check(repository.all().single().value == "12.3")
                check(db.readings().operationsForReading(reading.id).size == 2)
            }
        } finally { gate.release(); dispose(capture); db.close() }
    }

    @Test
    fun committedDeletionReturnsWithoutResurrectingReadingAfterRecreation() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val gate = ReturnGate()
        val repository = ReadingRepository(HeldDao(db.readings(), Mutation.DELETE, gate), SyntheticCipher())
        var capture: CaptureUiState? = null
        try {
            val reading = runBlocking {
                repository.startSitting("synthetic-delete-sitting")
                repository.record("synthetic-delete-reading", "synthetic-delete-sitting", Eye.LEFT, "12.3")
            }
            val owner = attach(repository)
            capture = owner
            waitFor(R.string.resume_sitting)
            seedSaved(owner, reading)
            tap(R.string.delete_reading)
            gate.arm()
            tap(R.string.confirm_delete)
            compose.waitUntil(timeoutMillis = 10_000) { gate.entered.get() }
            runBlocking { check(repository.all().isEmpty() && db.readings().allDeletedReadings().size == 1) }
            recreate(repository, owner, R.string.confirm_delete)
            gate.release()
            waitFor(R.string.deleted_on_phone)
            check(owner.savedState.value == null && owner.readingIdState.value.isEmpty())
            tap(R.string.add_another)
            waitFor(R.string.choose_eye)
            check(owner.readingIdState.value != reading.id)
            runBlocking { check(repository.all().isEmpty() && db.readings().allDeletedReadings().size == 1) }
        } finally { gate.release(); dispose(capture); db.close() }
    }

    @Test
    fun pendingStartSurvivesRecreationBeforeCommit() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val entered = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        val real = db.readings()
        val dao = object : ReadingDao by real {
            override suspend fun insertOpenSitting(row: SittingRow, cipher: ReadingCipher) {
                entered.set(true)
                withTimeout(30_000) { release.await() }
                real.insertOpenSitting(row, cipher)
            }
        }
        val repository = ReadingRepository(dao, SyntheticCipher())
        var capture: CaptureUiState? = null
        try {
            val owner = attach(repository)
            capture = owner
            tap(R.string.start_sitting)
            compose.waitUntil(timeoutMillis = 10_000) { entered.get() }
            runBlocking { check(repository.allSittings().isEmpty()) }
            recreate(repository, owner, R.string.start_sitting)
            release.complete(Unit)
            waitFor(R.string.choose_eye)
            runBlocking { check(repository.allSittings().single().id == owner.sittingIdState.value) }
            check(owner.hasOpenSittingState.value)
        } finally { release.complete(Unit); dispose(capture); db.close() }
    }

    @Test
    fun ephemeralDisposalCancelsBeforeCommitWithoutReportingStorageFailure() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val entered = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        val real = db.readings()
        val dao = object : ReadingDao by real {
            override suspend fun insertOpenSitting(row: SittingRow, cipher: ReadingCipher) {
                entered.set(true)
                try { withTimeout(30_000) { release.await() } }
                catch (failure: CancellationException) {
                    if (failure !is TimeoutCancellationException) cancelled.set(true)
                    throw failure
                }
                real.insertOpenSitting(row, cipher)
            }
        }
        val repository = ReadingRepository(dao, SyntheticCipher())
        val visible = mutableStateOf(true)
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { if (visible.value) AloeilApp(repository) else Text("Synthetic disposed") }
            }
            tap(R.string.start_sitting)
            compose.waitUntil(timeoutMillis = 10_000) { entered.get() }
            compose.runOnIdle { visible.value = false }
            compose.waitUntil(timeoutMillis = 10_000) { cancelled.get() }
            runBlocking { check(repository.allSittings().isEmpty() && repository.all().isEmpty()) }
            val cancellation = CancellationException("Synthetic cancellation")
            runBlocking { check(runCatching { captureMutationResult<Unit> { throw cancellation } }.exceptionOrNull() === cancellation) }
        } finally { release.complete(Unit); dispose(null); db.close() }
    }

    @Test
    fun cancellationWithAnActiveOwnerClearsBusyAndKeepsInput() {
        val owner = CaptureUiState()
        try {
            val job = compose.runOnIdle {
                owner.stepState.value = Step.REVIEW
                owner.valueState.value = "12.3"
                owner.messageState.value = null
                owner.busyState.value = true
                owner.launchMutation { throw CancellationException("Synthetic DAO cancellation") }
            }
            runBlocking { withTimeout(10_000) { job.join() } }
            check(job.isCancelled && owner.mutationScope.isActive)
            check(!owner.busyState.value && owner.messageState.value == null)
            check(owner.stepState.value == Step.REVIEW && owner.valueState.value == "12.3")
        } finally { owner.disposeEphemeral() }
    }
}
