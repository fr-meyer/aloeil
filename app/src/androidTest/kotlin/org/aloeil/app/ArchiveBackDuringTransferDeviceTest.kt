package org.aloeil.app

import androidx.activity.compose.setContent
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
                override fun seal(plaintext: ByteArray): SealedPayload =
                    baseCipher.seal(plaintext)

                override fun open(payload: SealedPayload): ByteArray {
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
            check(enteredRead.await(10, TimeUnit.SECONDS))
            compose.onNode(hasText(context.getString(R.string.archive_choose_destination)))
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
        } finally {
            releaseRead.countDown()
            db.close()
        }
    }
}
