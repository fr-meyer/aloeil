package org.aloeil.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aloeil.app.data.ArchivePreview
import org.aloeil.app.data.ReadingRepository
import java.io.ByteArrayOutputStream
import java.io.InputStream

private enum class TransferStep {
    CHOOSE, EXPORT, EXPORT_DONE, IMPORT, IMPORT_PREVIEW, IMPORT_DONE,
}

private const val MAX_ARCHIVE_FILE_BYTES = 16 * 1024 * 1024 + 64

/** The Android document picker lets the user choose a destination; Aloeil has no upload permission. */
@Composable
internal fun ArchiveTransferScreen(repository: ReadingRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(TransferStep.CHOOSE) }
    var passphrase by remember { mutableStateOf("") }
    var archiveBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<ArchivePreview?>(null) }
    var restoredCount by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val bytes = archiveBytes
        archiveBytes = null
        if (uri == null || bytes == null) {
            bytes?.fill(0)
            step = TransferStep.EXPORT
        } else {
            busy = true
            scope.launch {
                val saved = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            output.write(bytes)
                            output.flush()
                        } ?: throw IllegalStateException("Cannot open backup destination")
                    }
                }.isSuccess
                bytes.fill(0)
                busy = false
                if (saved) {
                    step = TransferStep.EXPORT_DONE
                } else {
                    error = R.string.archive_write_error
                    step = TransferStep.EXPORT
                }
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use(::readArchive)
                            ?: throw IllegalStateException("Cannot open archive")
                        val secret = passphrase.toCharArray()
                        try {
                            bytes to repository.previewArchive(bytes, secret)
                        } finally {
                            secret.fill('\u0000')
                        }
                    }
                }
                busy = false
                result.onSuccess { (bytes, counts) ->
                    archiveBytes?.fill(0)
                    archiveBytes = bytes
                    preview = counts
                    step = TransferStep.IMPORT_PREVIEW
                }.onFailure {
                    error = R.string.archive_read_error
                    step = TransferStep.IMPORT
                }
            }
        }
    }

    fun prepareExport() {
        if (passphrase.length < 12) {
            error = R.string.archive_passphrase_short
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val secret = passphrase.toCharArray()
                    try {
                        repository.exportArchive(secret)
                    } finally {
                        secret.fill('\u0000')
                    }
                }
            }
            busy = false
            result.onSuccess { bytes ->
                archiveBytes = bytes
                passphrase = ""
                createDocument.launch("aloeil-backup.aloeil")
            }.onFailure { error = R.string.archive_export_error }
        }
    }

    fun restore() {
        val bytes = archiveBytes ?: return
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val secret = passphrase.toCharArray()
                    try {
                        repository.importArchive(bytes, secret)
                    } finally {
                        secret.fill('\u0000')
                    }
                }
            }
            bytes.fill(0)
            archiveBytes = null
            passphrase = ""
            preview = null
            busy = false
            result.onSuccess { added ->
                restoredCount = added
                step = TransferStep.IMPORT_DONE
            }.onFailure {
                error = R.string.archive_restore_error
                step = TransferStep.IMPORT
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (step) {
            TransferStep.CHOOSE -> {
                TransferHeading(R.string.archive_title)
                Text(stringResource(R.string.archive_intro))
                TransferButton(R.string.archive_create, busy) {
                    error = null
                    step = TransferStep.EXPORT
                }
                TransferSecondary(R.string.archive_restore, busy) {
                    error = null
                    step = TransferStep.IMPORT
                }
                TransferSecondary(R.string.back, busy, onBack)
            }
            TransferStep.EXPORT -> {
                TransferHeading(R.string.archive_create)
                Text(stringResource(R.string.archive_export_explain))
                PassphraseField(passphrase) { passphrase = it; error = null }
                TransferButton(R.string.archive_choose_destination, busy) { prepareExport() }
                TransferSecondary(R.string.back, busy) {
                    passphrase = ""
                    step = TransferStep.CHOOSE
                }
            }
            TransferStep.EXPORT_DONE -> {
                TransferHeading(R.string.archive_export_done)
                Text(stringResource(R.string.archive_export_done_body))
                TransferButton(R.string.archive_done, busy, onBack)
            }
            TransferStep.IMPORT -> {
                TransferHeading(R.string.archive_restore)
                Text(stringResource(R.string.archive_import_explain))
                PassphraseField(passphrase) { passphrase = it; error = null }
                TransferButton(R.string.archive_choose_file, busy || passphrase.isEmpty()) {
                    openDocument.launch(arrayOf("*/*"))
                }
                TransferSecondary(R.string.back, busy) {
                    passphrase = ""
                    step = TransferStep.CHOOSE
                }
            }
            TransferStep.IMPORT_PREVIEW -> {
                TransferHeading(R.string.archive_review)
                val counts = preview
                if (counts != null) {
                    Text(stringResource(R.string.archive_contents, counts.readingCount, counts.sittingCount))
                }
                Text(stringResource(R.string.archive_restore_explain))
                TransferButton(R.string.archive_confirm_restore, busy) { restore() }
                TransferSecondary(R.string.back, busy) {
                    archiveBytes?.fill(0)
                    archiveBytes = null
                    preview = null
                    step = TransferStep.IMPORT
                }
            }
            TransferStep.IMPORT_DONE -> {
                TransferHeading(R.string.archive_restore_done)
                Text(stringResource(R.string.archive_added, restoredCount))
                TransferButton(R.string.archive_done, busy, onBack)
            }
        }
        error?.let { id ->
            Text(
                stringResource(id),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TransferHeading(id: Int) {
    Text(
        stringResource(id),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
    )
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.archive_passphrase)) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TransferButton(id: Int, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(id))
    }
}

@Composable
private fun TransferSecondary(id: Int, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(id)) }
}

private fun readArchive(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = input.read(chunk)
        if (count < 0) break
        require(output.size() + count <= MAX_ARCHIVE_FILE_BYTES) { "Archive is too large" }
        output.write(chunk, 0, count)
    }
    return output.toByteArray()
}
