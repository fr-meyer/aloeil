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

/** Normal replacement of a draft autosave effect must not report a storage failure. */
@RunWith(AndroidJUnit4::class)
class RapidDraftEditsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun successiveValueEditsPersistLatestDraftWithoutStorageWarning() {
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
            val field = compose.onNode(hasSetTextAction())
            field.performTextInput("1")
            field.performTextInput("2")
            field.performTextInput(".")
            field.performTextInput("3")
            compose.waitUntil(timeoutMillis = 10_000) {
                runBlocking { repo.recoverDraft()?.first?.input == "12.3" }
            }
            check(compose.onAllNodes(hasText(context.getString(R.string.error_storage)))
                .fetchSemanticsNodes().isEmpty())
        } finally {
            db.close()
        }
    }
}
