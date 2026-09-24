package org.aloeil.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File
import java.util.UUID
import java.io.OutputStreamWriter
import org.aloeil.app.data.CsvExport
import org.aloeil.app.data.Reading
import org.aloeil.app.data.Sitting

/** Plain-text shares live only in this private cache until the delayed local cleanup runs. */
internal object CsvShareCache {
    const val RETENTION_MILLIS = 60L * 60 * 1000
    const val JOB_ID = 0xA10E1
    private const val DEADLINE_SLACK_MILLIS = 5L * 60 * 1000

    fun directory(context: Context): File = File(context.cacheDir, "aloeil-share")

    fun isExpired(file: File, now: Long = System.currentTimeMillis()): Boolean =
        now - file.lastModified() >= RETENTION_MILLIS

    fun cleanupExpired(context: Context, now: Long = System.currentTimeMillis()) {
        directory(context).listFiles()?.filter { it.isFile && isExpired(it, now) }
            ?.forEach { check(it.delete()) { "Temporary CSV could not be removed" } }
    }

    fun clearAll(context: Context) {
        directory(context).listFiles()?.forEach { check(it.isFile && it.delete()) }
    }

    /** Also schedule before writing so an interrupted share still has a cleanup job. */
    fun scheduleNext(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val earliest = directory(context).listFiles()?.filter(File::isFile)
            ?.minOfOrNull { it.lastModified() + RETENTION_MILLIS }
            ?: now + RETENTION_MILLIS
        val delay = maxOf(1L, earliest - now)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(
            JOB_ID, ComponentName(context, CsvShareCleanupJobService::class.java),
        ).setMinimumLatency(delay)
            .setOverrideDeadline(delay + DEADLINE_SLACK_MILLIS)
            .setPersisted(true)
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    fun writeShare(context: Context, readings: List<Reading>, sittings: List<Sitting>): File {
        val folder = directory(context)
        check(folder.isDirectory || folder.mkdirs())
        val output = File(folder, "aloeil-readings-" + UUID.randomUUID() + ".csv")
        try {
            // The persisted job exists before any plaintext is written, including when
            // the system chooser is later cancelled or this process is killed.
            check(scheduleNext(context))
            OutputStreamWriter(output.outputStream(), Charsets.UTF_8).buffered().use {
                CsvExport.write(readings, sittings, it)
            }
            check(scheduleNext(context))
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }
}

/** Android can run this job after the chooser and app screen have both closed, including after reboot. */
class CsvShareCleanupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            var retry = false
            try {
                CsvShareCache.cleanupExpired(this)
                if (CsvShareCache.directory(this).listFiles()?.any(File::isFile) == true) {
                    retry = !CsvShareCache.scheduleNext(this)
                }
            } catch (_: Exception) {
                retry = true
            }
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
