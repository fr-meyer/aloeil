package org.aloeil.app

import android.content.Context
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Tab inserted whitespace and trapped keyboard users in the numeric field. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class KeyboardNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var inputModeManager: InputModeManager

    private fun useKeyboard() {
        // Key injection dispatches directly to Compose; establish the mode that
        // Android selects when the real keyboard sends a key.
        compose.runOnIdle { check(inputModeManager.requestInputMode(InputMode.Keyboard)) }
    }

    private fun withRepository(block: (Context) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher(), { "Asia/Seoul" }) {
                1_700_000_000_000L
            }
            compose.setContent {
                inputModeManager = LocalInputModeManager.current
                AloeilApp(repo)
            }
            block(context)
        } finally {
            db.close()
        }
    }

    private fun tap(context: Context, id: Int) {
        val target = hasText(context.getString(id)) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(target).performClick()
    }

    @Test fun tabAndShiftTabNavigateNumericAndNoteFieldsWithoutChangingText() {
        withRepository { context ->
            tap(context, R.string.start_sitting)
            tap(context, R.string.left_eye)
            tap(context, R.string.continue_action)
            val field = compose.onNode(hasSetTextAction())
            field.performClick().performTextInput("12.4")
            useKeyboard()
            field.performKeyInput { pressKey(Key.Tab) }
            val next = compose.onNode(hasText(context.getString(R.string.continue_action)) and hasClickAction())
            next.assertIsFocused()
            check(field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "12.4")
            next.performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            field.assertIsFocused()
            check(field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "12.4")
            field.performKeyInput { pressKey(Key.Tab) }
            next.performKeyInput { pressKey(Key.Enter) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.note_label))).fetchSemanticsNodes().isNotEmpty()
            }
            val note = compose.onNode(hasSetTextAction())
            note.performClick().performTextInput("Synthetic keyboard note")
            useKeyboard()
            note.performKeyInput { pressKey(Key.Tab) }
            compose.onNode(hasText(context.getString(R.string.continue_action)) and hasClickAction()).assertIsFocused()
            check(note.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "Synthetic keyboard note")
        }
    }

    @Test fun passphraseFieldReachesDestinationActionAndReturnsWithShiftTab() {
        withRepository { context ->
            tap(context, R.string.archive_title)
            tap(context, R.string.archive_create)
            val field = compose.onNode(hasSetTextAction())
            field.performClick().performTextInput("synthetic-keyboard-only")
            useKeyboard()
            field.performKeyInput { pressKey(Key.Tab) }
            val next = compose.onNode(hasText(context.getString(R.string.archive_choose_destination)) and hasClickAction())
            next.assertIsFocused()
            next.performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            field.assertIsFocused()
        }
    }
}
