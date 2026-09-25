package org.aloeil.app

import android.app.job.JobScheduler
import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import android.os.SystemClock
import org.junit.Test
import org.junit.runner.RunWith

/** A cancelled chooser leaves no screen callback, so cleanup must already be scheduled. */
@RunWith(AndroidJUnit4::class)
class CsvShareCacheDeviceTest {
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
