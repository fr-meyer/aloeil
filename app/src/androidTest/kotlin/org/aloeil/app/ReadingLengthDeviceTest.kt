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
import org.aloeil.app.data.ReadingValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Oversized pasted input is rejected before it reaches draft persistence. */
@RunWith(AndroidJUnit4::class)
class ReadingLengthDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longNumericPasteShowsErrorAndNormalEntryStillWorks() {
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
            compose.onNode(hasSetTextAction())
                .performTextInput("1".repeat(ReadingValue.MAX_LENGTH + 1))
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText(context.getString(R.string.error_length)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasSetTextAction()).performTextInput("12.3")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            tap(R.string.save_reading)
            compose.waitUntil(timeoutMillis = 10_000) {
                runBlocking { repo.all().singleOrNull()?.value == "12.3" }
            }
        } finally {
            db.close()
        }
    }
}
