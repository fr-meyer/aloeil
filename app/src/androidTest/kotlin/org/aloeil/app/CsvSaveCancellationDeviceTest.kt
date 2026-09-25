package org.aloeil.app

import android.accessibilityservice.AccessibilityService
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.aloeil.app.data.CsvExport
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
            compose.onAllNodes(saved).fetchSemanticsNodes().isNotEmpty()
        }
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

/** A writable synthetic URI exists only in the instrumentation APK. */
class SyntheticCsvProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = CsvExport.mimeType

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File(requireNotNull(context).cacheDir, "synthetic-csv-save.csv")
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY,
        )
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
