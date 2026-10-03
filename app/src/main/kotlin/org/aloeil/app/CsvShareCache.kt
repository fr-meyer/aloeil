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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.aloeil.app.data.CsvExport
import org.aloeil.app.data.Reading
import org.aloeil.app.data.Sitting

/** Plain-text shares live only in this private cache until the delayed local cleanup runs. */
internal object CsvShareCache {
    const val RETENTION_MILLIS = 60L * 60 * 1000
    private const val JOB_ID_NAMESPACE = 0x10000000
    private const val JOB_ID_MASK = 0x0FFFFFFF
    private const val DEADLINE_SLACK_MILLIS = 5L * 60 * 1000

    // Only a non-sensitive invalidation counter. A recreated screen observes late writes too.
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges

    private fun changed() { mutableChanges.value = mutableChanges.value + 1 }

    fun jobId(file: File): Int = JOB_ID_NAMESPACE or (file.name.hashCode() and JOB_ID_MASK)

    private val shareName = Regex(
        """^aloeil-readings-([0-9]+)-([0-9]+)-[0-9a-fA-F-]{36}\.csv$""",
    )

    fun directory(context: Context): File = File(context.cacheDir, "aloeil-share")

    @Synchronized
    fun hasFiles(context: Context): Boolean = directory(context).listFiles()?.any(File::isFile) == true

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

    @Synchronized
    fun cleanupExpired(
        context: Context,
        executingJobId: Int? = null,
        shouldContinue: () -> Boolean = { true },
    ) {
        if (!shouldContinue()) return
        directory(context).listFiles()?.forEach { file ->
            if (!shouldContinue()) return
            if (file.isFile && isExpired(context, file)) {
                remove(context, file, executingJobId)
            }
        }
    }

    @Synchronized
    fun clearAll(context: Context) {
        directory(context).listFiles()?.forEach { file ->
            check(file.isFile)
            remove(context, file)
        }
    }

    /** Retire only this file's cleanup, and only after its absence is verified. */
    @Synchronized
    fun remove(context: Context, file: File, executingJobId: Int? = null) {
        check(file.delete() || !file.exists()) { "Temporary CSV could not be removed" }
        cancelDeletedFileCleanup(context, file, executingJobId)
        changed()
    }

    private fun cancelDeletedFileCleanup(context: Context, file: File, executingJobId: Int? = null) {
        val id = jobId(file)
        // The executing service owns jobFinished/retry. Cancelling itself would stop
        // its coroutine in the middle of scanning the remaining expired files.
        if (id == executingJobId) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val pending = scheduler.getPendingJob(id) ?: return
        if (pending.service == ComponentName(context, CsvShareCleanupJobService::class.java)) {
            scheduler.cancel(id)
        }
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
    @Synchronized
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
            // Preserve the original write failure. If deletion fails, keep its persisted lease.
            runCatching { remove(context, output) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            changed()
        }
    }
}

/** Android can run this job after the chooser and app screen have both closed, including after reboot. */
class CsvShareCleanupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            val retry = try {
                withContext(Dispatchers.IO) {
                    val workerContext = coroutineContext
                    CsvShareCache.cleanupExpired(this@CsvShareCleanupJobService, executingJobId = params.jobId) {
                        workerContext.isActive
                    }
                }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                true
            }
            // JobScheduler callbacks and this completion run on Main. A stopped
            // invocation is removed before cancellation and must never finish a replacement.
            if (running[params.jobId] === coroutineContext[Job]) {
                running.remove(params.jobId)
                jobFinished(params, retry)
            }
        }
        running[params.jobId] = worker
        worker.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        running.clear()
        scope.cancel()
        super.onDestroy()
    }
}
