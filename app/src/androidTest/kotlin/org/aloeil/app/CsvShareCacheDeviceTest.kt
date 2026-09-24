package org.aloeil.app

import android.app.job.JobScheduler
import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
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
            check(file.setLastModified(
                System.currentTimeMillis() - CsvShareCache.RETENTION_MILLIS - 1000,
            ))
            val error = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.read() }
            }.exceptionOrNull()
            check(error is FileNotFoundException)
            CsvShareCache.cleanupExpired(context)
            check(!file.exists())
        } finally {
            file.delete()
            scheduler.cancel(CsvShareCache.jobId(file))
        }
    }
}
