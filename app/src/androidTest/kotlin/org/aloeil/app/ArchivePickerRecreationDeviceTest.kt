package org.aloeil.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Picker cancellation after Activity recreation must return to a usable archive screen. */
@RunWith(AndroidJUnit4::class)
class ArchivePickerRecreationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun exportAndImportPickersSurviveActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun tap(id: Int) {
            val target = hasText(context.getString(id)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(target).performClick()
        }
        fun pickerActive(): Boolean = runCatching {
            val owner = automation.rootInActiveWindow?.packageName?.toString()
            owner == InstrumentationRegistry.getInstrumentation().context.packageName
        }.getOrDefault(false)
        fun cancelAfterRecreation(returnLabel: Int) {
            compose.waitUntil(timeoutMillis = 15_000) { pickerActive() }
            var originalActivity = 0
            compose.activityRule.scenario.onActivity { activity ->
                originalActivity = System.identityHashCode(activity)
                activity.recreate()
            }
            compose.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    var recreated = false
                    compose.activityRule.scenario.onActivity { activity ->
                        recreated = System.identityHashCode(activity) != originalActivity
                    }
                    recreated
                }.getOrDefault(false)
            }
            compose.waitUntil(timeoutMillis = 15_000) { pickerActive() }
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            val target = hasText(context.getString(returnLabel)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            }
        }

        tap(R.string.archive_title)
        tap(R.string.archive_create)
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-recreation-only")
        tap(R.string.archive_choose_destination)
        cancelAfterRecreation(R.string.archive_choose_destination)
        tap(R.string.back)
        tap(R.string.archive_restore)
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-recreation-only")
        tap(R.string.archive_choose_file)
        cancelAfterRecreation(R.string.archive_choose_file)
    }
}
