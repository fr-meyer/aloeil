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
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deleting then choosing Keep recording must reserve a fresh reading ID. */
@RunWith(AndroidJUnit4::class)
class DeleteKeepRecordingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun newReadingSavesAfterDeleteFinishAndKeep() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher(), { "UTC" }) {
                1_700_000_000_000L
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
            fun enter(value: String) {
                tap(R.string.left_eye)
                tap(R.string.continue_action)
                compose.onNode(hasSetTextAction()).performTextInput(value)
                tap(R.string.continue_action)
                tap(R.string.continue_action)
                tap(R.string.save_reading)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(hasText(context.getString(R.string.saved_on_phone)))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            tap(R.string.start_sitting)
            enter("12.3")
            tap(R.string.delete_reading)
            tap(R.string.confirm_delete)
            tap(R.string.finish_sitting)
            tap(R.string.keep_recording)
            enter("13.1")
            runBlocking {
                check(repo.all().single().value == "13.1")
                check(db.readings().allDeletedReadings().size == 1)
            }
        } finally {
            db.close()
        }
    }
}
