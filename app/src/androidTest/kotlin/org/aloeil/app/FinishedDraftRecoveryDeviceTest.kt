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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.Eye
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A closed sitting must not recover into an active capture screen. */
@RunWith(AndroidJUnit4::class)
class FinishedDraftRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun staleSavedCheckpointAfterFinishStartsAFreshSitting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-finished-draft-" + UUID.randomUUID() + ".db"
        val cipher = SyntheticCipher()
        val first = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            runBlocking {
                val repo = ReadingRepository(first.readings(), cipher)
                repo.startSitting("finished-sitting")
                val old = repo.record("old-reading", "finished-sitting", Eye.LEFT, "12.3")
                repo.saveDraft(DraftCheckpoint(
                    old.sittingId, old.id, "SAVED", old.eye, old.value, "heading",
                ))
                check(repo.finishSitting("finished-sitting"))
            }
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, ReadingDatabase::class.java, name).build()
        try {
            val repo = ReadingRepository(reopened.readings(), cipher)
            runBlocking { check(repo.openSitting() == null) }
            compose.setContent { AloeilApp(repo) }
            fun tap(id: Int) {
                val label = context.getString(id)
                val target = hasText(label) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(target).performScrollTo().performClick()
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.start_sitting)) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking { check(reopened.readings().draft() == null) }
            tap(R.string.start_sitting)
            tap(R.string.left_eye)
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("14.2")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            tap(R.string.save_reading)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.saved_on_phone)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                check(repo.all().size == 2)
                check(repo.all().first { it.id != "old-reading" }.sittingId != "finished-sitting")
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
