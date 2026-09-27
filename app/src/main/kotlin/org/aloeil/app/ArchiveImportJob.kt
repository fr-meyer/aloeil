package org.aloeil.app

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns a restore buffer and passphrase until the transaction completes. */
internal sealed interface ArchiveImportState {
    data object Idle : ArchiveImportState
    data class Importing(val id: String) : ArchiveImportState
    data class Finished(val id: String, val added: Int?, val success: Boolean) : ArchiveImportState
}

internal class ArchiveImportJob(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<ArchiveImportState>(ArchiveImportState.Idle)
    val state: StateFlow<ArchiveImportState> = mutableState
    private var activeBytes: ByteArray? = null

    @Synchronized
    fun owns(bytes: ByteArray): Boolean = activeBytes === bytes

    @Synchronized
    fun start(
        bytes: ByteArray,
        passphrase: CharArray,
        importOperation: suspend (ByteArray, CharArray) -> Int,
    ): String {
        check(mutableState.value !is ArchiveImportState.Importing) {
            "An archive restore is already running"
        }
        val id = UUID.randomUUID().toString()
        activeBytes = bytes
        mutableState.value = ArchiveImportState.Importing(id)
        scope.launch {
            var added: Int? = null
            var success = false
            try {
                added = importOperation(bytes, passphrase)
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The screen reports a failed restore; the database transaction rolls back.
            } finally {
                synchronized(this@ArchiveImportJob) {
                    bytes.fill(0)
                    passphrase.fill('\u0000')
                    activeBytes = null
                    mutableState.value = ArchiveImportState.Finished(id, added, success)
                }
            }
        }
        return id
    }
}

internal val archiveImportJob = ArchiveImportJob(
    CoroutineScope(SupervisorJob() + Dispatchers.IO),
)
