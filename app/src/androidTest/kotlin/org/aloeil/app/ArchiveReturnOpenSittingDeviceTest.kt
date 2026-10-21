package org.aloeil.app

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
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

/** A restored open sitting must be usable immediately after leaving backup and restore. */
@RunWith(AndroidJUnit4::class)
class ArchiveReturnOpenSittingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun restoredOpenSittingCanResumeWithoutRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val passphrase = "synthetic-open-sitting-only".toCharArray()
        try {
            val source = ReadingRepository(sourceDb.readings(), SyntheticCipher())
            val target = ReadingRepository(targetDb.readings(), SyntheticCipher())
            val archive = runBlocking {
                source.startSitting("synthetic-open")
                source.record("synthetic-original", "synthetic-open", Eye.LEFT, "12.3")
                source.exportArchive(passphrase)
            }
            compose.setContent { AloeilApp(target) }
            fun tap(id: Int) {
                val targetNode = hasText(context.getString(id)) and hasClickAction()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(targetNode).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(targetNode).performScrollTo().performClick()
            }
            tap(R.string.archive_title)
            runBlocking { check(target.importArchive(archive, passphrase) == 1) }
            tap(R.string.back)
            tap(R.string.resume_sitting)
            tap(R.string.right_eye)
            tap(R.string.continue_action)
            compose.onNode(hasSetTextAction()).performTextInput("13.4")
            tap(R.string.continue_action)
            tap(R.string.continue_action)
            tap(R.string.save_reading)
            compose.waitUntil(timeoutMillis = 10_000) {
                runBlocking { target.all().size == 2 }
            }
            runBlocking {
                check(target.all().all { it.sittingId == "synthetic-open" })
                check(target.openSitting()?.id == "synthetic-open")
            }
        } finally {
            sourceDb.close()
            targetDb.close()
        }
    }
}
