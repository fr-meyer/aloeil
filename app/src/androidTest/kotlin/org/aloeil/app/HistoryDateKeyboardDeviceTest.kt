package org.aloeil.app

import android.content.Context
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic keyboard semantics, without claiming physical keyboard or TalkBack acceptance. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class HistoryDateKeyboardDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var inputModeManager: InputModeManager

    @Test
    fun bothDateFieldsNavigateForwardAndBackwardWithoutInsertingTabs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher(), { "UTC" }) {
                Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
            }
            runBlocking {
                repo.startSitting("synthetic-history-keyboard-sitting")
                repo.record("synthetic-history-keyboard-reading", "synthetic-history-keyboard-sitting", Eye.LEFT, "12.3")
                check(repo.finishSitting("synthetic-history-keyboard-sitting"))
            }
            compose.setContent {
                inputModeManager = LocalInputModeManager.current
                AloeilApp(repo)
            }
            val history = hasText(context.getString(R.string.history_title)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(history).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(history).performScrollTo().performClick()
            val from = hasText(context.getString(R.string.history_from_date)) and hasSetTextAction()
            val to = hasText(context.getString(R.string.history_to_date)) and hasSetTextAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(from).fetchSemanticsNodes().isNotEmpty()
            }
            val fromField = compose.onNode(from)
            val toField = compose.onNode(to)
            fromField.performScrollTo().performClick().performTextInput("2026-01-01")
            toField.performScrollTo().performClick().performTextInput("2026-01-01")
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.history_count, 1)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            fromField.performScrollTo().performClick()
            compose.runOnIdle { check(inputModeManager.requestInputMode(InputMode.Keyboard)) }
            fromField.performKeyInput { pressKey(Key.Tab) }
            toField.assertIsFocused()
            toField.performKeyInput {
                keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft)
            }
            fromField.assertIsFocused()
            fromField.performKeyInput {
                keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft)
            }
            compose.onNode(hasText(context.getString(R.string.right_eye)) and hasClickAction()).assertIsFocused()

            toField.performScrollTo().performClick()
            compose.runOnIdle { check(inputModeManager.requestInputMode(InputMode.Keyboard)) }
            toField.performKeyInput { pressKey(Key.Tab) }
            compose.onNode(hasText("12.3", substring = true) and hasClickAction()).assertIsFocused()
            check(fromField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "2026-01-01")
            check(toField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "2026-01-01")
        } finally {
            db.close()
        }
    }
}
