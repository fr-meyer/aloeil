package org.aloeil.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rejected edits must never advance with a retained earlier synthetic value. */
@RunWith(AndroidJUnit4::class)
class NumericEntryLengthDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun newEntryRejectsOversizeReplacementThroughRecreation() = exercise(Step.VALUE)

    @Test
    fun correctionRejectsOversizeReplacementThroughRecreation() = exercise(Step.CORRECT_VALUE)

    private fun exercise(inputStep: Step) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val repository = ReadingRepository(db.readings(), SyntheticCipher())
        var owner: CaptureUiState? = null
        try {
            val reading = runBlocking {
                repository.startSitting("synthetic-length-sitting")
                repository.record("synthetic-length-reading", "synthetic-length-sitting", Eye.LEFT, "12.3")
            }
            lateinit var capture: CaptureUiState
            compose.activityRule.scenario.onActivity { activity ->
                capture = ViewModelProvider(activity).get("synthetic-length-entry", CaptureUiState::class.java)
                capture.initialized = true
                capture.stepState.value = inputStep
                capture.sittingIdState.value = reading.sittingId
                capture.readingIdState.value = if (inputStep == Step.VALUE) "synthetic-length-new-reading" else reading.id
                capture.savedState.value = if (inputStep == Step.VALUE) null else reading
                capture.eyeState.value = Eye.LEFT
                capture.valueState.value = "12.3"
                capture.hasOpenSittingState.value = true
                activity.setContent { AloeilApp(repository, captureState = capture) }
            }
            owner = capture
            fun next() = hasText(compose.activity.getString(R.string.continue_action)) and hasClickAction()
            fun lengthError() = hasText(compose.activity.getString(R.string.error_length))
            fun waitForUi(phase: String, condition: () -> Boolean) {
                try {
                    compose.waitUntil(timeoutMillis = 10_000, condition = condition)
                } catch (failure: ComposeTimeoutException) {
                    // Only nonsensitive state/IDs; no synthetic or personal reading values.
                    throw AssertionError("$phase: step=${capture.stepState.value}; " +
                        "lengthError=${capture.valueErrorState.value == R.string.error_length}", failure)
                }
            }
            compose.waitForIdle()
            waitForUi("initial numeric Continue") {
                compose.onAllNodes(next()).fetchSemanticsNodes().isNotEmpty()
            }
            val queuedAdvance = compose.onNode(next()).performScrollTo().fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action ?: error("Synthetic Continue action unavailable")
            compose.onNode(hasSetTextAction()).performScrollTo()
                .performTextReplacement("1".repeat(ReadingValue.MAX_LENGTH + 1))
            compose.onNode(lengthError()).assertExists()
            compose.onNode(next()).assertIsNotEnabled()
            compose.runOnIdle {
                check(capture.valueState.value == "12.3")
                queuedAdvance()
                check(capture.stepState.value == inputStep)
            }

            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { activity ->
                check(ViewModelProvider(activity).get("synthetic-length-entry", CaptureUiState::class.java) === capture)
                check(capture.stepState.value == inputStep)
                check(capture.valueErrorState.value == R.string.error_length)
                check(capture.valueState.value == "12.3")
                activity.setContent { AloeilApp(repository, captureState = capture) }
            }
            waitForUi("recreated numeric editor") {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            // Scroll state is recreated too; bring the editor/supporting error into view.
            compose.onNode(hasSetTextAction()).performScrollTo()
            waitForUi("recreated length error") {
                compose.onAllNodes(lengthError()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(next()).assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextReplacement("13.4")
            compose.onNode(next()).assertIsEnabled().performScrollTo().performClick()
            compose.runOnIdle {
                check(capture.valueErrorState.value == null)
                check(capture.stepState.value == if (inputStep == Step.VALUE) Step.NOTE else Step.CORRECT_REVIEW_VALUE)
            }
            runBlocking {
                check(repository.all().single().revision == 1L)
                check(repository.all().single().value == "12.3")
            }
        } finally {
            compose.activityRule.scenario.onActivity { it.setContent { Text("Synthetic test complete") } }
            owner?.disposeEphemeral()
            db.close()
        }
    }
}
