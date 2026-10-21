package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full synthetic capture, correction, undo and confirmed deletion through the UI. */
@RunWith(AndroidJUnit4::class)
class CaptureFlowDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun committedFactsFollowTheScreenFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher(), { "Asia/Seoul" }) {
                1_700_000_000_000L
            }
            compose.setContent { AloeilApp(repo) }
            fun tap(id: Int) {
                val label = context.getString(id)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText(label) and hasClickAction()).performClick()
            }
            tap(R.string.start_sitting)
            tap(R.string.left_eye)
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("12.3")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            tap(R.string.save_reading)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(context.getString(R.string.saved_on_phone))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                check(repo.all().single().value == "12.3")
            }

            tap(R.string.correct_reading)
            tap(R.string.correct_value)
            compose.onNode(hasSetTextAction()).performTextReplacement("13.1")
            tap(R.string.continue_action)
            tap(R.string.save_correction)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(context.getString(R.string.correction_saved))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                val reading = repo.all().single()
                check(reading.value == "13.1" && reading.revision == 2L)
            }

            tap(R.string.undo_correction)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(context.getString(R.string.undo_done))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                val reading = repo.all().single()
                check(reading.value == "12.3" && reading.revision == 3L)
            }

            tap(R.string.delete_reading)
            tap(R.string.confirm_delete)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(context.getString(R.string.deleted_on_phone))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                check(repo.all().isEmpty())
                check(db.readings().allDeletedReadings().size == 1)
            }
        } finally {
            db.close()
        }
    }
}
