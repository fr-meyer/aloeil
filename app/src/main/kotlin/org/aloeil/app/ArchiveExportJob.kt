package org.aloeil.app

import android.content.Context
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns encrypted backup bytes until writing ends, across Activity recreation. */
internal sealed interface ArchiveWriteState {
    data object Idle : ArchiveWriteState
    data class Writing(val id: String) : ArchiveWriteState
    data class Finished(val id: String, val saved: Boolean) : ArchiveWriteState
}

internal class ArchiveExportJob(
    private val scope: CoroutineScope,
    private val writer: suspend (Context, Uri, ByteArray) -> Unit,
) {
    private val mutableState = MutableStateFlow<ArchiveWriteState>(ArchiveWriteState.Idle)
    val state: StateFlow<ArchiveWriteState> = mutableState
    private var activeBytes: ByteArray? = null

    /** A disposed screen may still hold a stale reference after transfer of ownership. */
    @Synchronized
    fun scrubUnlessOwned(bytes: ByteArray) {
        if (activeBytes !== bytes) bytes.fill(0)
    }

    @Synchronized
    fun start(context: Context, uri: Uri, bytes: ByteArray): String {
        check(mutableState.value !is ArchiveWriteState.Writing) {
            "An archive write is already running"
        }
        val id = UUID.randomUUID().toString()
        activeBytes = bytes
        mutableState.value = ArchiveWriteState.Writing(id)
        scope.launch {
            var saved = false
            try {
                writer(context.applicationContext, uri, bytes)
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The screen reports the failed destination; never report success.
            } finally {
                synchronized(this@ArchiveExportJob) {
                    bytes.fill(0)
                    activeBytes = null
                    mutableState.value = ArchiveWriteState.Finished(id, saved)
                }
            }
        }
        return id
    }
}

internal val archiveExportJob = ArchiveExportJob(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    writer = { context, uri, bytes ->
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IllegalStateException("Cannot open backup destination")
    },
)
