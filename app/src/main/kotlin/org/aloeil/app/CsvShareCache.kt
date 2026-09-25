package org.aloeil.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.UUID
import java.io.OutputStreamWriter
import org.aloeil.app.data.CsvExport
import org.aloeil.app.data.Reading
import org.aloeil.app.data.Sitting

/** Plain-text shares live only in this private cache until the delayed local cleanup runs. */
internal object CsvShareCache {
    const val RETENTION_MILLIS = 60L * 60 * 1000
    private const val JOB_ID_NAMESPACE = 0x10000000
    private const val JOB_ID_MASK = 0x0FFFFFFF
    private const val DEADLINE_SLACK_MILLIS = 5L * 60 * 1000

    fun jobId(file: File): Int = JOB_ID_NAMESPACE or (file.name.hashCode() and JOB_ID_MASK)

    private val shareName = Regex(
        """^aloeil-readings-([0-9]+)-([0-9]+)-[0-9a-fA-F-]{36}\.csv$""",
    )

    fun directory(context: Context): File = File(context.cacheDir, "aloeil-share")

    fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    fun newFile(context: Context, boot: Int, issuedElapsed: Long): File =
        File(directory(context), "aloeil-readings-$boot-$issuedElapsed-" + UUID.randomUUID() + ".csv")

    private fun lease(file: File): Pair<Int, Long>? {
        val match = shareName.matchEntire(file.name) ?: return null
        val boot = match.groupValues[1].toIntOrNull() ?: return null
        val issued = match.groupValues[2].toLongOrNull() ?: return null
        return boot to issued
    }

    fun isExpired(
        context: Context,
        file: File,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        currentBoot: Int = bootCount(context),
    ): Boolean {
        val (issuedBoot, issuedElapsed) = lease(file) ?: return true
        return currentBoot < 0 || issuedBoot != currentBoot ||
            nowElapsed < issuedElapsed || nowElapsed - issuedElapsed >= RETENTION_MILLIS
    }

    fun cleanupExpired(context: Context) {
        directory(context).listFiles()?.filter { it.isFile && isExpired(context, it) }
            ?.forEach { check(it.delete()) { "Temporary CSV could not be removed" } }
    }

    fun clearAll(context: Context) {
        directory(context).listFiles()?.forEach { check(it.isFile && it.delete()) }
    }

    /** Each file keeps its own persisted cleanup job, even if the chooser is cancelled. */
    fun scheduleFile(context: Context, file: File): Boolean {
        val (issuedBoot, issuedElapsed) = lease(file) ?: return false
        if (issuedBoot != bootCount(context)) return false
        val delay = maxOf(
            1L, issuedElapsed + RETENTION_MILLIS - SystemClock.elapsedRealtime(),
        )
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(
            jobId(file), ComponentName(context, CsvShareCleanupJobService::class.java),
        ).setMinimumLatency(delay)
            .setOverrideDeadline(delay + DEADLINE_SLACK_MILLIS)
            .setPersisted(true)
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    /** Restore a missing cleanup after an interrupted write or an app upgrade. */
    fun ensureScheduled(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        directory(context).listFiles()?.filter(File::isFile)?.forEach { file ->
            if (scheduler.getPendingJob(jobId(file)) == null) {
                check(scheduleFile(context, file))
            }
        }
    }

    @Synchronized
    fun writeShare(context: Context, readings: List<Reading>, sittings: List<Sitting>): File {
        val folder = directory(context)
        check(folder.isDirectory || folder.mkdirs())
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val boot = bootCount(context)
        check(boot >= 0) { "Boot identity is unavailable" }
        val issuedElapsed = SystemClock.elapsedRealtime()
        val output = generateSequence {
            newFile(context, boot, issuedElapsed)
        }.first { candidate ->
            scheduler.getPendingJob(jobId(candidate)) == null &&
                (folder.listFiles()?.none { it.isFile && jobId(it) == jobId(candidate) } ?: true)
        }
        try {
            // Schedule before writing so process death and a cancelled chooser are covered.
            check(scheduleFile(context, output))
            OutputStreamWriter(output.outputStream(), Charsets.UTF_8).buffered().use {
                CsvExport.write(readings, sittings, it)
            }
            check(scheduleFile(context, output))
            return output
        } catch (error: Exception) {
            output.delete()
            scheduler.cancel(jobId(output))
            throw error
        }
    }
}

/** Android can run this job after the chooser and app screen have both closed, including after reboot. */
class CsvShareCleanupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val retry = runCatching { CsvShareCache.cleanupExpired(this) }.isFailure
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
