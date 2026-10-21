package org.aloeil.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import android.net.Uri
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

/** A held save survives Back and Activity recreation; a later cancellation clears success. */
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
        val csvUri = Uri.parse("content://org.aloeil.app.test.syntheticcsv/export.csv")
        context.contentResolver.call(csvUri, "hold", null, null)
        try {
            val choose = automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Select synthetic CSV")
                ?.firstOrNull { it.isClickable }
            check(choose?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            compose.waitUntil(timeoutMillis = 15_000) {
                context.contentResolver.call(csvUri, "waiting", null, null)
                    ?.getBoolean("waiting") == true
            }
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.csv_disclosure)))
                    .fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(saved).fetchSemanticsNodes().isEmpty()
            }
            var writingActivity = 0
            compose.activityRule.scenario.onActivity { activity ->
                writingActivity = System.identityHashCode(activity)
                activity.recreate()
            }
            compose.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    var recreated = false
                    compose.activityRule.scenario.onActivity { activity ->
                        recreated = System.identityHashCode(activity) != writingActivity
                    }
                    recreated
                }.getOrDefault(false)
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                context.contentResolver.call(csvUri, "waiting", null, null)
                    ?.getBoolean("waiting") == true &&
                    compose.onAllNodes(hasText(context.getString(R.string.csv_disclosure)))
                        .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            context.contentResolver.call(csvUri, "release", null, null)
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(saved).fetchSemanticsNodes().isNotEmpty()
        }
        val savedContent = context.contentResolver.openInputStream(csvUri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        check(savedContent?.contains("sitting") == true)
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
