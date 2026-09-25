package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** A historical selection cannot return to a saved-reading screen with no selected reading. */
@RunWith(AndroidJUnit4::class)
class HistoryReturnNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun returnFromSelectedHistoryKeepsFinishedDestinationSafe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        try {
            val repo = ReadingRepository(db.readings(), SyntheticCipher())
            compose.setContent { AloeilApp(repo) }
            fun tap(id: Int) {
                val target = hasText(context.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performScrollTo().performClick()
            }
            tap(R.string.start_sitting)
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
            tap(R.string.finish_sitting)
            tap(R.string.finish)
            tap(R.string.history_title)
            val reading = hasText("12.3", substring = true) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(reading).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(reading).performScrollTo().performClick()
            tap(R.string.back)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.history_title)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            tap(R.string.back)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.sitting_finished)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking { check(repo.all().single().value == "12.3") }
        } finally {
            db.close()
        }
    }
}
