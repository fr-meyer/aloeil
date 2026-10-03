package org.aloeil.app

import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aloeil.app.data.Eye
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UUID-isolated synthetic caches; no recipient, real reading, or production job is touched. */
@RunWith(AndroidJUnit4::class)
class CsvSharePreparationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun isolatedCache(base: Context): Context = object : ContextWrapper(base) {
        private val identity = UUID.randomUUID().toString()
        override fun getCacheDir() = File(base.cacheDir, "synthetic-share-" + identity)
    }

    private fun cleanup(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val folder = CsvShareCache.directory(context)
        folder.setWritable(true, true)
        folder.listFiles()?.forEach {
            it.delete()
            scheduler.cancel(CsvShareCache.jobId(it))
        }
        folder.delete()
        context.cacheDir.delete()
    }

    @Test
    fun cancelledNonCooperativeWriterRetainsReferenceUntilVerifiedRemoval() = runBlocking {
        val context = isolatedCache(ApplicationProvider.getApplicationContext<Context>())
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        var written: File? = null
        val worker = launch {
            prepareAndOpenCsvShare(
                write = {
                    started.complete(Unit)
                    check(release.await(30, TimeUnit.SECONDS))
                    CsvShareCache.writeShare(context, emptyList(), emptyList()).also { written = it }
                },
                remove = { CsvShareCache.remove(context, it) },
                open = { opened.set(true) },
            )
        }
        try {
            withTimeout(10_000) { started.await() }
            // A new screen's initial scan can finish before this non-cooperative writer.
            check(!CsvShareCache.hasFiles(context))
            worker.cancel()
            release.countDown()
            withTimeout(10_000) { worker.join() }
            val file = checkNotNull(written)
            check(!opened.get() && !file.exists())
            check(context.getSystemService(JobScheduler::class.java)
                .getPendingJob(CsvShareCache.jobId(file)) == null)
        } finally {
            release.countDown()
            worker.cancel()
            worker.join()
            cleanup(context)
        }
    }

    @Test
    fun chooserFailureRemovesOnlyItsFileAndMatchingLease() = runBlocking {
        val context = isolatedCache(ApplicationProvider.getApplicationContext<Context>())
        val scheduler = context.getSystemService(JobScheduler::class.java)
        try {
            val other = CsvShareCache.writeShare(context, emptyList(), emptyList())
            var attempted: File? = null
            check(!prepareAndOpenCsvShare(
                write = {
                    CsvShareCache.writeShare(context, emptyList(), emptyList()).also { attempted = it }
                },
                remove = { CsvShareCache.remove(context, it) },
                open = { throw IllegalStateException("Synthetic chooser failure") },
            ))
            val file = checkNotNull(attempted)
            check(!file.exists() && scheduler.getPendingJob(CsvShareCache.jobId(file)) == null)
            check(other.exists() && scheduler.getPendingJob(CsvShareCache.jobId(other)) != null)
        } finally {
            cleanup(context)
        }
    }

    @Test
    fun failedRemovalRetainsLeaseAndRecreatedScreenShowsClearForLateFile() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = isolatedCache(base)
        val database = Room.inMemoryDatabaseBuilder(base, ReadingDatabase::class.java).build()
        val repository = ReadingRepository(database.readings(), SyntheticCipher())
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var written: File? = null
        fun installScreen() {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(
                        LocalContext provides context,
                        LocalActivityResultRegistryOwner provides activity,
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CsvExportScreen(repository, onBack = {})
                        }
                    }
                }
            }
        }
        val clear = hasText(context.getString(R.string.csv_clear_cache)) and hasClickAction()
        installScreen()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(context.getString(R.string.csv_count, 0)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val worker = owner.launch {
            prepareAndOpenCsvShare(
                write = {
                    started.complete(Unit)
                    check(release.await(30, TimeUnit.SECONDS))
                    val real = CsvShareCache.writeShare(context, emptyList(), emptyList())
                    written = real
                    // Keep the real file but force only this removal attempt to fail.
                    object : File(real.path) { override fun delete(): Boolean = false }
                },
                remove = { CsvShareCache.remove(context, it) },
                open = { error("A cancelled preparation must not open a chooser") },
            )
        }
        try {
            runBlocking { withTimeout(10_000) { started.await() } }
            worker.cancel()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { Text("Synthetic screen disposed") }
            }
            installScreen()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.csv_count, 0)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(clear).assertDoesNotExist()
            release.countDown()
            runBlocking { withTimeout(10_000) { worker.join() } }
            val file = checkNotNull(written)
            check(file.exists() && context.getSystemService(JobScheduler::class.java)
                .getPendingJob(CsvShareCache.jobId(file)) != null)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(clear).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(clear).performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) { !file.exists() }
            check(context.getSystemService(JobScheduler::class.java)
                .getPendingJob(CsvShareCache.jobId(file)) == null)
        } finally {
            release.countDown()
            worker.cancel()
            runBlocking { worker.join() }
            owner.cancel()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { Text("Synthetic fixture complete") }
            }
            database.close()
            cleanup(context)
        }
    }

    @Test
    fun partialWriteFailureKeepsCleanupWhenDeletionFails() {
        val context = isolatedCache(ApplicationProvider.getApplicationContext<Context>())
        val folder = CsvShareCache.directory(context)
        val changesBefore = CsvShareCache.changes.value
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // CSV opens its output before accessing this synthetic list; fail after its header.
        val invalidReadings = object : AbstractList<Reading>() {
            override val size = 1
            override fun get(index: Int): Reading {
                check(index == 0)
                check(folder.setWritable(false, false))
                return Reading("synthetic", "missing-sitting", 0L, Eye.LEFT, "12.3", 1L, 0L)
            }
        }
        try {
            check(runCatching { CsvShareCache.writeShare(context, invalidReadings, emptyList()) }.isFailure)
            val partial = checkNotNull(folder.listFiles()).single()
            check(partial.exists() && partial.length() > 0L)
            check(scheduler.getPendingJob(CsvShareCache.jobId(partial))?.isPersisted == true)
            check(CsvShareCache.hasFiles(context) && CsvShareCache.changes.value > changesBefore)
            check(folder.setWritable(true, true))
            CsvShareCache.remove(context, partial)
            check(!partial.exists() && scheduler.getPendingJob(CsvShareCache.jobId(partial)) == null)
        } finally {
            cleanup(context)
        }
    }
}
