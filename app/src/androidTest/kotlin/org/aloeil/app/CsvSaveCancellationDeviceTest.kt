package org.aloeil.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A canceled second save must not retain the first save's success message. */
@RunWith(AndroidJUnit4::class)
class CsvSaveCancellationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun saveThenCancelDoesNotReportSecondSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun tap(id: Int) {
            val target = hasText(context.getString(id)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(target).performScrollTo().performClick()
        }
        val saved = hasText(context.getString(R.string.csv_saved))
        tap(R.string.csv_title)
        tap(R.string.csv_save)
        compose.waitUntil(timeoutMillis = 15_000) {
            automation.rootInActiveWindow?.packageName?.toString() ==
                instrumentation.context.packageName
        }
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
        compose.waitUntil(timeoutMillis = 15_000) {
            automation.rootInActiveWindow?.packageName?.toString() ==
                instrumentation.context.packageName
        }
        val choose = automation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText("Select synthetic CSV")
            ?.firstOrNull { it.isClickable }
        check(choose?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(saved).fetchSemanticsNodes().isNotEmpty()
        }
        val savedFile = java.io.File(instrumentation.context.cacheDir, "synthetic-csv-save.csv")
        check(savedFile.isFile && savedFile.readText().contains("sitting"))
        tap(R.string.csv_save)
        compose.waitUntil(timeoutMillis = 15_000) {
            automation.rootInActiveWindow?.packageName?.toString() ==
                instrumentation.context.packageName
        }
        check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(saved).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasText(context.getString(R.string.csv_save)) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
