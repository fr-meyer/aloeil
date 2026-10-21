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
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EyeBackNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun newCaptureCanReturnToStartWithoutLeavingADraft() {
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
                compose.onNode(target).performClick()
            }
            tap(R.string.start_sitting)
            tap(R.string.back)
            val resume = hasText(context.getString(R.string.resume_sitting)) and hasClickAction()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(resume).fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                check(db.readings().draft() == null)
                check(repo.all().isEmpty())
                check(repo.openSitting() != null)
            }
            tap(R.string.resume_sitting)
            tap(R.string.left_eye)
        } finally {
            db.close()
        }
    }

    @Test
    fun eyeCorrectionCanReturnToCorrectionChoiceWithoutSaving() {
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
                compose.onNode(target).performClick()
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
            tap(R.string.correct_reading)
            tap(R.string.correct_eye)
            tap(R.string.right_eye)
            tap(R.string.back)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.choose_correction)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                val saved = repo.all().single()
                check(saved.eye == Eye.LEFT && saved.revision == 1L)
            }
        } finally {
            db.close()
        }
    }
}
