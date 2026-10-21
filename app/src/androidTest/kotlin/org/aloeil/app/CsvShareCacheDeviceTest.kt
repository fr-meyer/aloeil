package org.aloeil.app

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

/** A cancelled chooser leaves no screen callback, so cleanup must already be scheduled. */
@RunWith(AndroidJUnit4::class)
class CsvShareCacheDeviceTest {
    private fun isolatedCache(base: Context): Context = object : ContextWrapper(base) {
        private val identity = UUID.randomUUID().toString()
        override fun getCacheDir() = File(base.cacheDir, "synthetic-cleanup-" + identity)
    }

    /** A future elapsed-time lease is already invalid, while its job will not run during the test. */
    private fun scheduledExpiredFile(context: Context): File {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val boot = CsvShareCache.bootCount(context)
        check(boot >= 0)
        val folder = CsvShareCache.directory(context)
        check(folder.isDirectory || folder.mkdirs())
        val file = generateSequence {
            CsvShareCache.newFile(context, boot, SystemClock.elapsedRealtime() + CsvShareCache.RETENTION_MILLIS)
        }.first { !it.exists() && scheduler.getPendingJob(CsvShareCache.jobId(it)) == null }
        try {
            file.writeText("synthetic")
            check(CsvShareCache.scheduleFile(context, file))
            check(CsvShareCache.isExpired(context, file))
            return file
        } catch (error: Throwable) {
            file.delete()
            scheduler.cancel(CsvShareCache.jobId(file))
            throw error
        }
    }

    private fun removeTestFiles(context: Context, files: List<File>) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        files.forEach { it.delete(); scheduler.cancel(CsvShareCache.jobId(it)) }
        // These UUID-isolated directories belong only to this fixture; delete only if empty.
        CsvShareCache.directory(context).delete()
        context.cacheDir.delete()
    }

    @Test
    fun repeatedClearCancelsOnlyDeletedFileJobs() {
        val context = isolatedCache(ApplicationProvider.getApplicationContext<Context>())
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // A separate test-owned sentinel uses the same service but no share-file ID.
        val sentinel = generateSequence { 0x50000000 or (UUID.randomUUID().hashCode() and 0x0FFFFFFF) }
            .first { scheduler.getPendingJob(it) == null }
        val info = JobInfo.Builder(sentinel, ComponentName(context, CsvShareCleanupJobService::class.java))
            .setMinimumLatency(CsvShareCache.RETENTION_MILLIS * 2).setPersisted(true).build()
        val files = mutableListOf<File>()
        try {
            check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS)
            repeat(2) {
                val batch = (1..3).map {
                    CsvShareCache.writeShare(context, emptyList(), emptyList()).also { files.add(it) }
                }
                batch.forEach { check(scheduler.getPendingJob(CsvShareCache.jobId(it))?.isPersisted == true) }
                CsvShareCache.clearAll(context)
                batch.forEach {
                    check(!it.exists())
                    check(scheduler.getPendingJob(CsvShareCache.jobId(it)) == null)
                }
                // Clearing an already empty cache must preserve unrelated scheduled work.
                CsvShareCache.clearAll(context)
                check(scheduler.getPendingJob(sentinel) != null)
            }
        } finally {
            removeTestFiles(context, files)
            scheduler.cancel(sentinel)
        }
    }

    @Test
    fun expiredCleanupLeavesExecutingJobForItsOwnerAndPreservesFreshShare() {
        val context = isolatedCache(ApplicationProvider.getApplicationContext<Context>())
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val files = mutableListOf<File>()
        try {
            val fresh = CsvShareCache.writeShare(context, emptyList(), emptyList()).also { files.add(it) }
            val executing = scheduledExpiredFile(context).also { files.add(it) }
            val other = scheduledExpiredFile(context).also { files.add(it) }
            val executingId = CsvShareCache.jobId(executing)
            // Model the service's executing job ID; do not dispatch or force any job.
            CsvShareCache.cleanupExpired(context, executingJobId = executingId)
            check(!executing.exists() && !other.exists())
            check(scheduler.getPendingJob(CsvShareCache.jobId(other)) == null)
            check(scheduler.getPendingJob(executingId) != null)
            check(fresh.exists() && scheduler.getPendingJob(CsvShareCache.jobId(fresh)) != null)
            // Its own job is still the caller's responsibility after a repeated scan.
            CsvShareCache.cleanupExpired(context, executingJobId = executingId)
            check(scheduler.getPendingJob(executingId) != null)
        } finally {
            // Direct-helper tests own these jobs; real service completion uses jobFinished.
            removeTestFiles(context, files)
        }
    }

    @Test
    fun stoppedCleanupLeavesRemainingFilesForRetry() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = isolatedCache(base)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val files = mutableListOf<File>()
        try {
            repeat(3) { files.add(scheduledExpiredFile(context)) }
            var polls = 0
            // Permit entry and one deletion, then model cancellation before the next file.
            CsvShareCache.cleanupExpired(context) { ++polls <= 2 }
            check(files.count { it.exists() } == 2)
            files.forEach { check((scheduler.getPendingJob(CsvShareCache.jobId(it)) != null) == it.exists()) }
            CsvShareCache.cleanupExpired(context)
            check(files.none { it.exists() })
            files.forEach { check(scheduler.getPendingJob(CsvShareCache.jobId(it)) == null) }
            // Another cleanup invocation must accept an already-removed file set.
            CsvShareCache.cleanupExpired(context)
        } finally {
            removeTestFiles(context, files)
        }
    }

    @Test
    fun cancelledChooserStillHasPersistedCleanupAndExpiredUriCannotBeRead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val file = CsvShareCache.writeShare(context, emptyList(), emptyList())
        try {
            // No recipient is selected, as when the system chooser is cancelled.
            val job = scheduler.getPendingJob(CsvShareCache.jobId(file))
            check(job != null && job.isPersisted)
            val uri = FileProvider.getUriForFile(
                context, "org.aloeil.app.fileprovider", file,
            )
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                check(stream.read() >= 0)
            }
            // A clock rollback makes the wall timestamp look far in the future.
            // The same-boot lease still expires using elapsed real time.
            val futureWall = System.currentTimeMillis() + CsvShareCache.RETENTION_MILLIS * 3
            check(file.setLastModified(futureWall))
            val boot = CsvShareCache.bootCount(context)
            check(boot >= 0)
            check(CsvShareCache.isExpired(
                context, file,
                nowElapsed = SystemClock.elapsedRealtime() + CsvShareCache.RETENTION_MILLIS + 1000,
                currentBoot = boot,
            ))
            // A reboot invalidates the lease immediately, even with a future wall timestamp.
            val stale = CsvShareCache.newFile(context, boot + 1, SystemClock.elapsedRealtime())
            file.copyTo(stale)
            check(stale.setLastModified(futureWall))
            val staleUri = FileProvider.getUriForFile(
                context, "org.aloeil.app.fileprovider", stale,
            )
            val error = runCatching {
                context.contentResolver.openInputStream(staleUri)?.use { it.read() }
            }.exceptionOrNull()
            check(error is FileNotFoundException)
            CsvShareCache.cleanupExpired(context)
            check(!stale.exists())
            check(file.exists())
        } finally {
            file.delete()
            scheduler.cancel(CsvShareCache.jobId(file))
        }
    }
}
