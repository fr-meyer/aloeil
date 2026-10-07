package org.aloeil.app

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Check actual Android input metadata on both production archive routes, without any secret text. */
@RunWith(AndroidJUnit4::class)
class ArchivePassphraseKeyboardDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun exportRequestsPasswordInputWithoutAutoCorrection() = exercise(R.string.archive_create)
    @Test fun importRequestsPasswordInputWithoutAutoCorrection() = exercise(R.string.archive_restore)

    private fun exercise(route: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val repository = ReadingRepository(database.readings(), SyntheticCipher())
        val capture = CaptureUiState().apply { initialized = true; stepState.value = Step.ARCHIVE }
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { AloeilApp(repository, captureState = capture) }
            }
            val target = hasText(context.getString(route)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(target).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performScrollTo().performClick()
            var info: EditorInfo? = null
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activityRule.scenario.onActivity { activity ->
                    val focused = activity.window.decorView.findFocus()
                    if (focused?.onCheckIsTextEditor() == true) {
                        val candidate = EditorInfo()
                        focused.onCreateInputConnection(candidate)?.let { connection ->
                            info = candidate
                            connection.closeConnection()
                        }
                    }
                }
                info != null
            }
            val inputType = checkNotNull(info).inputType
            check(inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
                "routeResource=$route inputType=$inputType: expected text class"
            }
            check(inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                "routeResource=$route inputType=$inputType: expected password variation"
            }
            check(inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0) {
                "routeResource=$route inputType=$inputType: unexpected auto correction"
            }
            check(inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE == 0) {
                "routeResource=$route inputType=$inputType: unexpected auto completion"
            }
            check(inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0) {
                "routeResource=$route inputType=$inputType: expected single line"
            }
            // Only OS metadata is inspected: no EditorInfo surrounding text or input is collected.
        } finally {
            compose.activityRule.scenario.onActivity { it.setContent { Text("Synthetic keyboard test complete") } }
            compose.waitForIdle()
            compose.runOnIdle { capture.disposeEphemeral() }
            database.close()
        }
    }
}
