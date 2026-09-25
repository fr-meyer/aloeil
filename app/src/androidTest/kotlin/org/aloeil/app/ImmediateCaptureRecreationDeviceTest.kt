package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An Activity recreation immediately after typing must retain current plaintext in memory. */
@RunWith(AndroidJUnit4::class)
class ImmediateCaptureRecreationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun latestValueAndNoteSurviveWithoutWaitingForDraftCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun tap(id: Int) {
            val target = hasText(context.getString(id)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(target).performScrollTo().performClick()
        }
        tap(R.string.start_sitting)
        tap(R.string.left_eye)
        tap(R.string.continue_action)
        compose.onNode(hasSetTextAction()).performTextInput("12.3")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).assertTextContains("12.3")
        tap(R.string.continue_action)
        compose.onNode(hasSetTextAction()).performTextInput("fresh synthetic note")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).assertTextContains("fresh synthetic note")
    }
}
