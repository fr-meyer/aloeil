package org.aloeil.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.aloeil.app.data.Eye
import org.aloeil.app.data.OutboxRow
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingDao
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingRow
import org.aloeil.app.data.ReadingVersionRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Failure/retry and no-op confirmation flows against synthetic Room data only. */
@RunWith(AndroidJUnit4::class)
class CaptureRetryNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun tap(id: Int) {
        val matcher = hasText(context.getString(id)) and hasClickAction() and isEnabled()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(matcher).performScrollTo().performClick()
    }

    private fun waitFor(id: Int) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(context.getString(id))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun attach(repository: ReadingRepository, reading: Reading, step: Step, fromHistory: Boolean = false): CaptureUiState {
        lateinit var owner: CaptureUiState
        compose.activityRule.scenario.onActivity { activity ->
            owner = ViewModelProvider(activity).get("synthetic-retry-navigation", CaptureUiState::class.java)
            owner.initialized = true
            owner.stepState.value = step
            owner.sittingIdState.value = reading.sittingId
            owner.readingIdState.value = reading.id
            owner.savedState.value = reading
            owner.hasOpenSittingState.value = true
            owner.fromHistoryState.value = fromHistory
            owner.messageState.value = null
            activity.setContent { AloeilApp(repository, captureState = owner) }
        }
        return owner
    }

    private fun dispose(owner: CaptureUiState?) {
        compose.activityRule.scenario.onActivity { it.setContent { Text("Synthetic test complete") } }
        owner?.disposeEphemeral()
    }

    @Test
    fun finishFailureThenSuccessfulRetryClearsErrorAndCommitsOnce() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val real = db.readings()
        val failOnce = AtomicBoolean(true)
        val dao = object : ReadingDao by real {
            override suspend fun updateSittingIfUnchanged(id: String, oldNonce: ByteArray, oldCiphertext: ByteArray, newNonce: ByteArray, newCiphertext: ByteArray): Int {
                if (failOnce.compareAndSet(true, false)) throw IOException("Synthetic finish failure")
                return real.updateSittingIfUnchanged(id, oldNonce, oldCiphertext, newNonce, newCiphertext)
            }
        }
        val repository = ReadingRepository(dao, SyntheticCipher())
        var owner: CaptureUiState? = null
        try {
            val reading = runBlocking {
                repository.startSitting("synthetic-retry-finish-sitting")
                repository.record("synthetic-retry-finish-reading", "synthetic-retry-finish-sitting", Eye.LEFT, "12.3")
            }
            val capture = attach(repository, reading, Step.FINISH)
            owner = capture
            tap(R.string.finish)
            waitFor(R.string.error_storage)
            runBlocking { check(repository.openSitting()?.id == reading.sittingId) }
            tap(R.string.finish)
            waitFor(R.string.sitting_finished)
            compose.onNode(hasText(context.getString(R.string.error_storage))).assertDoesNotExist()
            check(capture.messageState.value == null && !capture.busyState.value)
            runBlocking {
                check(repository.openSitting() == null)
                check(repository.allSittings().size == 1 && repository.all().single().revision == 1L)
            }
        } finally { dispose(owner); db.close() }
    }

    @Test
    fun undoFailureThenSuccessfulRetryClearsErrorAndCreatesOneRevision() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val real = db.readings()
        val failOnce = AtomicBoolean(false)
        val dao = object : ReadingDao by real {
            override suspend fun applyCorrection(operationId: String, expectedRevision: Long, updated: ReadingRow, priorVersion: ReadingVersionRow, outbox: OutboxRow): Boolean {
                if (failOnce.compareAndSet(true, false)) throw IOException("Synthetic undo failure")
                return real.applyCorrection(operationId, expectedRevision, updated, priorVersion, outbox)
            }
        }
        val repository = ReadingRepository(dao, SyntheticCipher())
        var owner: CaptureUiState? = null
        try {
            val corrected = runBlocking {
                repository.startSitting("synthetic-retry-undo-sitting")
                val original = repository.record("synthetic-retry-undo-reading", "synthetic-retry-undo-sitting", Eye.LEFT, "12.3")
                repository.correct("synthetic-retry-correction", original.id, 1, Eye.LEFT, "13.4")!!
            }
            val capture = attach(repository, corrected, Step.CORRECT_SAVED)
            owner = capture
            failOnce.set(true)
            tap(R.string.undo_correction)
            waitFor(R.string.nothing_to_undo)
            runBlocking { check(repository.all().single().revision == 2L) }
            tap(R.string.undo_correction)
            waitFor(R.string.undo_done)
            compose.onNode(hasText(context.getString(R.string.nothing_to_undo))).assertDoesNotExist()
            check(capture.messageState.value == null && !capture.busyState.value)
            runBlocking {
                val current = repository.all().single()
                check(current.revision == 3L && current.value == "12.3")
                check(real.operationsForReading(current.id).size == 2)
            }
        } finally { dispose(owner); db.close() }
    }

    @Test
    fun cancelDeletionAfterRecreationRetainsCorrectedScreenAndUndo() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val repository = ReadingRepository(db.readings(), SyntheticCipher())
        var owner: CaptureUiState? = null
        try {
            val corrected = runBlocking {
                repository.startSitting("synthetic-cancel-delete-sitting")
                val original = repository.record("synthetic-cancel-delete-reading", "synthetic-cancel-delete-sitting", Eye.LEFT, "12.3")
                repository.correct("synthetic-cancel-delete-correction", original.id, 1, Eye.LEFT, "13.4")!!
            }
            val capture = attach(repository, corrected, Step.CORRECT_SAVED)
            owner = capture
            tap(R.string.delete_reading)
            waitFor(R.string.delete_confirm_title)
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { activity ->
                check(ViewModelProvider(activity).get("synthetic-retry-navigation", CaptureUiState::class.java) === capture)
                activity.setContent { AloeilApp(repository, captureState = capture) }
            }
            tap(R.string.keep_reading)
            waitFor(R.string.correction_saved)
            check(capture.stepState.value == Step.CORRECT_SAVED)
            runBlocking { check(repository.all().single().revision == 2L && db.readings().allDeletedReadings().isEmpty()) }
            tap(R.string.undo_correction)
            waitFor(R.string.undo_done)
            runBlocking { check(repository.all().single().revision == 3L) }
        } finally { dispose(owner); db.close() }
    }

    @Test
    fun cancelDeletionPreservesEverySavedAndHistoryOriginWithoutWriting() {
        val db = Room.inMemoryDatabaseBuilder(context, ReadingDatabase::class.java).build()
        val repository = ReadingRepository(db.readings(), SyntheticCipher())
        var owner: CaptureUiState? = null
        try {
            val corrected = runBlocking {
                repository.startSitting("synthetic-cancel-origins-sitting")
                val original = repository.record("synthetic-cancel-origins-reading", "synthetic-cancel-origins-sitting", Eye.LEFT, "12.3")
                repository.correct("synthetic-cancel-origins-correction", original.id, 1, Eye.LEFT, "13.4")!!
            }
            val origins = listOf(Step.SAVED to false, Step.CORRECT_SAVED to false,
                Step.CORRECT_SAVED to true, Step.UNDO_DONE to false, Step.HISTORY_READING to true)
            for ((origin, history) in origins) {
                val capture = attach(repository, corrected, origin, history)
                owner = capture
                tap(R.string.delete_reading)
                waitFor(R.string.delete_confirm_title)
                tap(R.string.keep_reading)
                compose.runOnIdle { check(capture.stepState.value == origin) }
                runBlocking { check(repository.all().single().revision == 2L && db.readings().allDeletedReadings().isEmpty()) }
            }
        } finally { dispose(owner); db.close() }
    }
}
