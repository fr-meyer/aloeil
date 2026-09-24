package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic end-to-end access after the saved draft has been cleared. */
@RunWith(AndroidJUnit4::class)
class HistoryRestartDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun finishedReadingIsSelectableAfterDatabaseRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-history-" + UUID.randomUUID() + ".db"
        val cipher = SyntheticCipher()
        val first = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            runBlocking {
                val repo = ReadingRepository(first.readings(), cipher, { "Asia/Seoul" }) {
                    1_700_000_000_000L
                }
                repo.startSitting("synthetic-sitting")
                repo.record("synthetic-reading", "synthetic-sitting", Eye.LEFT, "12.3")
                repo.clearDraft()
                check(repo.finishSitting("synthetic-sitting"))
            }
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val repo = ReadingRepository(reopened.readings(), cipher)
            runBlocking {
                check(repo.recoverDraft() == null)
                check(repo.openSitting() == null)
                check(repo.all().single().id == "synthetic-reading")
            }
            var selected = ""
            compose.setContent {
                HistoryScreen(repo, onSelect = { reading, _ -> selected = reading.id }, onBack = {})
            }
            val label = context.getString(R.string.left_eye) + ": " +
                context.getString(R.string.numeric_reading, "12.3")
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(label) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(label) and hasClickAction()).performClick()
            compose.runOnIdle { check(selected == "synthetic-reading") }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
