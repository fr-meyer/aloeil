package org.aloeil.app

import androidx.activity.compose.setContent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingCipher
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.SealedPayload
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Back must not cancel a composition-scoped encrypted archive transfer. */
@RunWith(AndroidJUnit4::class)
class ArchiveBackDuringTransferDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackCannotEndActivityDuringHeldArchiveExport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        try {
            val baseCipher = SyntheticCipher()
            val source = ReadingRepository(db.readings(), baseCipher)
            runBlocking {
                source.startSitting("synthetic-sitting")
                source.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
            }
            val heldCipher = object : ReadingCipher {
                override fun seal(plaintext: ByteArray, aad: ByteArray): SealedPayload =
                    baseCipher.seal(plaintext, aad)

                override fun open(payload: SealedPayload, aad: ByteArray): ByteArray {
                    enteredRead.countDown()
                    check(releaseRead.await(30, TimeUnit.SECONDS))
                    error("Synthetic archive read failure")
                }
            }
            val repository = ReadingRepository(db.readings(), heldCipher)
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { ArchiveTransferScreen(repository) { } }
            }
            fun tap(id: Int) {
                val target = hasText(context.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performClick()
            }
            tap(R.string.archive_create)
            compose.onNode(hasSetTextAction()).performTextInput("synthetic-passphrase-only")
            tap(R.string.archive_choose_destination)
            // Snapshot decryption runs before the document picker opens.
            check(enteredRead.await(10, TimeUnit.SECONDS))
            compose.onNode(hasText(context.getString(R.string.archive_choose_destination)))
                .assertIsNotEnabled()
            compose.onNode(hasText(context.getString(R.string.archive_passphrase)))
                .assertIsNotEnabled()
            compose.activityRule.scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                check(!activity.isFinishing)
            }
            releaseRead.countDown()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.archive_export_error)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.activityRule.scenario.onActivity { activity ->
                check(!activity.isFinishing)
            }
            compose.onNode(hasText(context.getString(R.string.archive_passphrase)))
                .assertIsEnabled()
        } finally {
            releaseRead.countDown()
            db.close()
        }
    }

    @Test
    fun heldPreviewRejectsStalePasswordEditsAndRestores() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val uri = Uri.parse("content://org.aloeil.app.test.syntheticcsv/export.archive")
        val passphrase = "synthetic-preview-password".toCharArray()
        fun provider(method: String) = context.contentResolver.call(uri, method, null, null)
        try {
            // This provider and both databases belong to the synthetic test only.
            provider("release")
            provider("releaseRead")
            val source = ReadingRepository(sourceDb.readings(), SyntheticCipher())
            val target = ReadingRepository(targetDb.readings(), SyntheticCipher())
            val archive = runBlocking {
                source.startSitting("synthetic-preview-sitting")
                source.record("synthetic-preview-reading", "synthetic-preview-sitting", Eye.LEFT, "12.3")
                source.exportArchive(passphrase)
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(archive) }
                    ?: error("Synthetic archive destination unavailable")
            } finally {
                archive.fill(0)
            }
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { ArchiveTransferScreen(target) { } }
            }
            fun tap(id: Int) {
                val node = hasText(context.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 15_000) {
                    compose.onAllNodes(node).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(node).performClick()
            }
            tap(R.string.archive_restore)
            val password = compose.onNode(hasSetTextAction())
            password.performTextInput(String(passphrase))
            // Retain the enabled action to model a queued edit arriving after busy starts.
            val queuedEdit = password.fetchSemanticsNode().config[SemanticsActions.SetText].action
                ?: error("Synthetic password edit action unavailable")
            provider("holdRead")
            tap(R.string.archive_choose_file)
            compose.waitUntil(timeoutMillis = 15_000) {
                automation.rootInActiveWindow?.packageName?.toString() ==
                    InstrumentationRegistry.getInstrumentation().context.packageName
            }
            val choose = automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Select synthetic archive")
                ?.firstOrNull { it.isClickable }
            check(choose?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            compose.waitUntil(timeoutMillis = 15_000) {
                provider("waitingRead")?.getBoolean("waiting") == true
            }
            compose.onNode(hasText(context.getString(R.string.archive_passphrase)))
                .assertIsNotEnabled()
            compose.runOnIdle { queuedEdit(AnnotatedString("synthetic-wrong-password")) }
            provider("releaseRead")
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(hasText(context.getString(R.string.archive_review)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            tap(R.string.archive_confirm_restore)
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(hasText(context.getString(R.string.archive_restore_done)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking { check(target.all().size == 1) }
        } finally {
            provider("releaseRead")
            passphrase.fill('\u0000')
            sourceDb.close()
            targetDb.close()
        }
    }
}
