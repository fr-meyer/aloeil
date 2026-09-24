package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.DraftRow
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A corrupt unsaved checkpoint must not hide a valid open sitting. */
@RunWith(AndroidJUnit4::class)
class CorruptDraftRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openSittingRemainsUsableWhenDraftCannotDecrypt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher(), { "UTC" }) {
                1_700_000_000_000L
            }
            runBlocking {
                repo.startSitting("synthetic-open")
                db.readings().saveDraft(DraftRow(
                    nonce = ByteArray(12),
                    ciphertext = byteArrayOf(1, 2, 3),
                ))
            }
            compose.setContent { AloeilApp(repo) }
            fun tap(id: Int) {
                val label = context.getString(id)
                val target = hasText(label) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performClick()
            }
            tap(R.string.resume_sitting)
            tap(R.string.left_eye)
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("12.3")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            tap(R.string.save_reading)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.saved_on_phone)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                check(repo.all().single().sittingId == "synthetic-open")
                check(repo.openSitting()?.id == "synthetic-open")
            }
        } finally {
            db.close()
        }
    }
}
