package org.aloeil.app

import android.content.Context
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
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

/** The archive picker must return a writable file and complete an encrypted save. */
@RunWith(AndroidJUnit4::class)
class ArchivePickerSaveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectingSyntheticArchiveSavesEncryptedFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun tap(id: Int) {
            val target = hasText(context.getString(id)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(target).performClick()
        }
        tap(R.string.archive_title)
        tap(R.string.archive_create)
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-archive-passphrase")
        tap(R.string.archive_choose_destination)
        compose.waitUntil(timeoutMillis = 15_000) {
            automation.rootInActiveWindow?.packageName?.toString() ==
                instrumentation.context.packageName
        }
        val choose = automation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText("Select synthetic archive")
            ?.firstOrNull { it.isClickable }
        check(choose?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodes(hasText(context.getString(R.string.archive_export_done)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val archiveUri = Uri.parse("content://org.aloeil.app.test.syntheticcsv/export.archive")
        val bytes = context.contentResolver.openInputStream(archiveUri)?.use { it.readBytes() }
        check(bytes != null && bytes.size > 32)
        check(!bytes.toString(Charsets.UTF_8).contains("synthetic-archive-passphrase"))
    }
}
