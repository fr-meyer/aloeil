package org.aloeil.app

import android.content.Context
import android.net.Uri
import java.io.OutputStreamWriter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.aloeil.app.data.CsvExport
import org.aloeil.app.data.Reading
import org.aloeil.app.data.Sitting

/** Keeps a deliberate plaintext CSV write alive through Activity recreation. */
internal sealed interface CsvSaveState {
    data object Idle : CsvSaveState
    data class Writing(val id: String) : CsvSaveState
    data class Finished(val id: String, val saved: Boolean) : CsvSaveState
}

internal class CsvSaveJob(
    private val scope: CoroutineScope,
    private val writer: suspend (Context, Uri, List<Reading>, List<Sitting>) -> Unit,
) {
    private val mutableState = MutableStateFlow<CsvSaveState>(CsvSaveState.Idle)
    val state: StateFlow<CsvSaveState> = mutableState

    @Synchronized
    fun start(
        context: Context,
        uri: Uri,
        readings: List<Reading>,
        sittings: List<Sitting>,
    ): String {
        check(mutableState.value !is CsvSaveState.Writing) { "A CSV write is already running" }
        val id = UUID.randomUUID().toString()
        mutableState.value = CsvSaveState.Writing(id)
        scope.launch {
            var saved = false
            try {
                writer(context.applicationContext, uri, readings, sittings)
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The screen reports a failed or partial destination.
            } finally {
                mutableState.value = CsvSaveState.Finished(id, saved)
            }
        }
        return id
    }
}

internal val csvSaveJob = CsvSaveJob(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    writer = { context, uri, readings, sittings ->
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Cannot open CSV destination")
        OutputStreamWriter(stream, Charsets.UTF_8).buffered().use { writer ->
            CsvExport.write(readings, sittings, writer)
        }
    },
)
