package org.aloeil.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aloeil.app.data.CsvExport
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.Sitting
import java.io.OutputStreamWriter

/** CSV is plain text. The user reviews its scope before choosing a file or recipient. */
@Composable
internal fun CsvExportScreen(repository: ReadingRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<Pair<List<Reading>, List<Sitting>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<Int?>(null) }
    var pendingSaveUri by rememberSaveable { mutableStateOf<String?>(null) }
    var hasShareFile by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                CsvShareCache.cleanupExpired(context)
                val previousShare = CsvShareCache.directory(context).listFiles()
                    ?.any { it.isFile } == true
                if (previousShare) CsvShareCache.ensureScheduled(context)
                repository.currentFactsSnapshot() to previousShare
            }
        }.onSuccess { (data, previousShare) ->
            snapshot = data
            hasShareFile = previousShare
        }.onFailure {
            pendingSaveUri = null
            busy = false
            error = R.string.csv_load_error
        }
    }

    // Save only the URI across Activity recreation. Rebuild the CSV from the
    // repository after the snapshot has loaded; never save plaintext CSV in state.
    LaunchedEffect(pendingSaveUri, snapshot != null) {
        val selected = pendingSaveUri ?: return@LaunchedEffect
        val data = snapshot ?: return@LaunchedEffect
        busy = true
        error = null
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(Uri.parse(selected), "wt")
                    ?: throw IllegalStateException("Cannot open CSV destination")
                OutputStreamWriter(stream, Charsets.UTF_8).buffered().use { writer ->
                    CsvExport.write(data.first, data.second, writer)
                }
            }
        }.isSuccess
        pendingSaveUri = null
        busy = false
        if (saved) result = R.string.csv_saved else error = R.string.csv_write_error
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CsvExport.mimeType),
    ) { uri ->
        result = null
        error = null
        pendingSaveUri = uri?.toString()
        busy = uri != null
    }

    fun share() {
        val data = snapshot ?: return
        busy = true
        error = null
        result = null
        scope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    CsvShareCache.writeShare(context, data.first, data.second)
                }
            }.getOrNull()
            if (file == null) {
                error = R.string.csv_share_error
            } else {
                val opened = runCatching {
                    val uri = FileProvider.getUriForFile(
                        context, "org.aloeil.app.fileprovider", file,
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = CsvExport.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(
                        send, context.getString(R.string.csv_choose_recipient),
                    ))
                }.isSuccess
                if (opened) {
                    hasShareFile = true
                    result = R.string.csv_chooser_opened
                } else {
                    file.delete()
                    error = R.string.csv_share_error
                }
            }
            busy = false
        }
    }

    Text(stringResource(R.string.csv_title), modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.csv_disclosure))
    val data = snapshot
    if (data == null) {
        if (error == null) Text(stringResource(R.string.loading))
    } else {
        Text(stringResource(R.string.csv_count, data.first.size))
        Text(stringResource(R.string.csv_fields))
        if (data.first.isNotEmpty()) {
            Text(stringResource(R.string.csv_sample_title))
            data.first.take(3).forEach { reading ->
                Text(eyeLabel(reading.eye) + ": " +
                    readingLabel(reading.value, reading.rangeState))
            }
        }
        Text(stringResource(R.string.csv_backup_reminder))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    result = null
                    error = null
                    createDocument.launch(CsvExport.fileName)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.csv_save)) }
            OutlinedButton(
                onClick = ::share,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.csv_share)) }
            if (hasShareFile) {
                Text(stringResource(R.string.csv_cache_disclosure))
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            val cleared = runCatching {
                                withContext(Dispatchers.IO) {
                                    CsvShareCache.clearAll(context)
                                }
                            }.isSuccess
                            busy = false
                            if (cleared) {
                                hasShareFile = false
                                result = R.string.csv_cache_cleared
                            } else {
                                error = R.string.csv_cache_error
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.csv_clear_cache)) }
            }
        }
    }
    OutlinedButton(
        onClick = onBack,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.back)) }
    error?.let {
        Text(stringResource(it), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
    }
    result?.let {
        Text(stringResource(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
