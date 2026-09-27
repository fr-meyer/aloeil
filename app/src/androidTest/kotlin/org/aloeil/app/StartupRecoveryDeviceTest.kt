package org.aloeil.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An initialization failure must still offer the guarded local recovery flow. */
@RunWith(AndroidJUnit4::class)
class StartupRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun failedDatabaseInitializationNeedsTwoExplicitResetActions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resets = AtomicInteger()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                AloeilStartup(
                    Result.failure(IllegalStateException("Synthetic open failure")),
                    resetUnreadableStore = { resets.incrementAndGet(); Unit },
                )
            }
        }
        compose.onNode(hasText(context.getString(R.string.recovery_unreadable_title)))
            .assertExists()
        check(resets.get() == 0)
        compose.onNode(
            hasText(context.getString(R.string.recovery_prepare_reset)) and hasClickAction()
        ).performClick()
        compose.onNode(hasText(context.getString(R.string.recovery_confirm_title))).assertExists()
        check(resets.get() == 0)
        compose.onNode(
            hasText(context.getString(R.string.recovery_confirm_reset)) and hasClickAction()
        ).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { resets.get() == 1 }
    }
}
