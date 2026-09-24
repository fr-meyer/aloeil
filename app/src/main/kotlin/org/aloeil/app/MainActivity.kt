package org.aloeil.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aloeil.app.data.AndroidKeystoreReadingCipher
import org.aloeil.app.data.BackupState
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.Eye
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingDatabase
import org.aloeil.app.data.ReadingRepository
import org.aloeil.app.data.ReadingValue
import org.aloeil.app.data.ReadingValueResult
import org.aloeil.app.data.Reason
import org.aloeil.app.data.newReadingId
import org.aloeil.app.data.restoredDraftStep

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = ReadingDatabase.open(applicationContext)
        val repository = ReadingRepository(database.readings(), AndroidKeystoreReadingCipher())
        setContent { AloeilApp(repository) }
    }
}

private enum class Step {
    LOADING, START, EYE, VALUE, REVIEW, SAVED,
    CORRECT_CHOICE, CORRECT_EYE, CORRECT_VALUE,
    CORRECT_REVIEW_EYE, CORRECT_REVIEW_VALUE, CORRECT_SAVED, UNDO_DONE,
    FINISH, FINISHED, ARCHIVE,
}

@Composable
private fun AloeilApp(repository: ReadingRepository) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.LOADING) }
    var sittingId by remember { mutableStateOf("") }
    var readingId by remember { mutableStateOf("") }
    var eye by remember { mutableStateOf<Eye?>(null) }
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Reading?>(null) }
    var hasOpenSitting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var valueError by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        try {
            val recovered = withContext(Dispatchers.IO) { repository.recoverDraft() }
            if (recovered != null) {
                val (draft, committed) = recovered
                sittingId = draft.sittingId
                readingId = draft.readingId
                eye = draft.eye
                value = draft.input
                saved = committed
                step = runCatching { Step.valueOf(restoredDraftStep(draft, committed)) }
                    .getOrDefault(Step.EYE)
                hasOpenSitting = true
            } else {
                val open = withContext(Dispatchers.IO) { repository.openSitting() }
                if (open != null) {
                    sittingId = open.id
                    hasOpenSitting = true
                }
                step = Step.START
            }
        } catch (_: Exception) {
            message = R.string.error_draft_restore
            step = Step.START
        }
    }

    LaunchedEffect(step, sittingId, readingId, eye, value, busy) {
        if (!busy && sittingId.isNotEmpty() && readingId.isNotEmpty() && step in setOf(
                Step.EYE, Step.VALUE, Step.REVIEW, Step.CORRECT_CHOICE,
                Step.CORRECT_EYE, Step.CORRECT_VALUE,
                Step.CORRECT_REVIEW_EYE, Step.CORRECT_REVIEW_VALUE,
            )
        ) {
            try {
                withContext(Dispatchers.IO) {
                    repository.saveDraft(
                        DraftCheckpoint(
                            sittingId, readingId, step.name, eye, value,
                            when (step) {
                                Step.VALUE, Step.CORRECT_VALUE -> "reading"
                                Step.EYE, Step.CORRECT_EYE -> "eye"
                                else -> "heading"
                            },
                            baseRevision = if (step.name.startsWith("CORRECT_")) saved?.revision else null,
                        ),
                    )
                }
            } catch (_: Exception) {
                message = R.string.error_storage
            }
        }
    }

    fun beginSitting() {
        busy = true
        message = null
        scope.launch {
            try {
                if (!hasOpenSitting) {
                    sittingId = withContext(Dispatchers.IO) { repository.startSitting() }
                    hasOpenSitting = true
                }
                readingId = newReadingId()
                eye = null
                value = ""
                saved = null
                step = Step.EYE
            } catch (_: Exception) {
                message = R.string.error_storage
            } finally {
                busy = false
            }
        }
    }

    fun validateValue(): Boolean {
        valueError = when (val result = ReadingValue.parse(value)) {
            is ReadingValueResult.Valid -> null
            is ReadingValueResult.Invalid -> when (result.reason) {
                Reason.EMPTY -> R.string.error_empty
                Reason.NUMBER -> R.string.error_number
                Reason.DECIMAL -> R.string.error_decimal
            }
        }
        return valueError == null
    }

    fun saveReading() {
        val selected = eye ?: return
        busy = true
        message = null
        scope.launch {
            val committed = try {
                withContext(Dispatchers.IO) {
                    repository.record(readingId, sittingId, selected, value)
                }
            } catch (_: Exception) {
                null
            }
            if (committed == null) {
                message = R.string.error_save
                step = Step.REVIEW
            } else {
                saved = committed
                step = Step.SAVED
                runCatching { withContext(Dispatchers.IO) { repository.clearDraft() } }
            }
            busy = false
        }
    }

    fun saveCorrection() {
        val current = saved ?: return
        val selected = eye ?: return
        busy = true
        message = null
        scope.launch {
            val corrected = try {
                withContext(Dispatchers.IO) {
                    repository.correct(
                        current.id + ":correct:" + (current.revision + 1),
                        current.id, current.revision, selected, value,
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (corrected == null) {
                message = R.string.error_correction_save
            } else {
                saved = corrected
                step = Step.CORRECT_SAVED
                runCatching { withContext(Dispatchers.IO) { repository.clearDraft() } }
            }
            busy = false
        }
    }

    fun undoCorrection() {
        val current = saved ?: return
        busy = true
        scope.launch {
            val undone = try {
                withContext(Dispatchers.IO) {
                    repository.undoCorrection(
                        current.id + ":undo:" + (current.revision + 1),
                        current.id, current.revision,
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (undone == null) {
                message = R.string.nothing_to_undo
            } else {
                saved = undone
                step = Step.UNDO_DONE
            }
            busy = false
        }
    }

    fun abandonCorrection() {
        val current = saved ?: return
        busy = true
        message = null
        scope.launch {
            val recorded = runCatching {
                withContext(Dispatchers.IO) {
                    repository.saveDraft(
                        DraftCheckpoint(
                            sittingId, current.id, Step.SAVED.name,
                            current.eye, current.value, "heading",
                        ),
                    )
                }
            }.isSuccess
            if (recorded) {
                eye = current.eye
                value = current.value
                step = Step.SAVED
            } else {
                message = R.string.error_storage
            }
            busy = false
        }
    }

    fun finishSitting() {
        busy = true
        scope.launch {
            val finished = runCatching {
                withContext(Dispatchers.IO) { repository.finishSitting(sittingId) }
            }.getOrDefault(false)
            if (finished) {
                runCatching { withContext(Dispatchers.IO) { repository.clearDraft() } }
                hasOpenSitting = false
                step = Step.FINISHED
            } else {
                message = R.string.error_storage
            }
            busy = false
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    Step.LOADING -> Heading(R.string.loading)
                    Step.START -> {
                        Heading(if (hasOpenSitting) R.string.interrupted_title else R.string.start_sitting)
                        if (hasOpenSitting) Text(stringResource(R.string.interrupted_body))
                        Action(if (hasOpenSitting) R.string.resume_sitting else R.string.start_sitting, busy) {
                            beginSitting()
                        }
                        Secondary(R.string.archive_title) { step = Step.ARCHIVE }
                    }
                    Step.EYE, Step.CORRECT_EYE -> {
                        Heading(if (step == Step.EYE) R.string.choose_eye else R.string.correct_eye)
                        EyeOptions(eye) { eye = it }
                        Action(R.string.continue_action, busy || eye == null) {
                            step = if (step == Step.EYE) Step.VALUE else Step.CORRECT_REVIEW_EYE
                        }
                    }
                    Step.VALUE, Step.CORRECT_VALUE -> {
                        Heading(if (step == Step.VALUE) R.string.enter_reading else R.string.correct_value)
                        ValueField(value, valueError, { value = it; valueError = null })
                        Action(R.string.continue_action, busy) {
                            if (validateValue()) {
                                step = if (step == Step.VALUE) Step.REVIEW else Step.CORRECT_REVIEW_VALUE
                            }
                        }
                        Secondary(R.string.back) {
                            step = if (step == Step.VALUE) Step.EYE else Step.CORRECT_CHOICE
                        }
                    }
                    Step.REVIEW -> {
                        Heading(R.string.review_before_save)
                        Text(stringResource(R.string.not_saved))
                        Text(stringResource(R.string.eye_summary, eyeLabel(eye)))
                        Text(stringResource(R.string.reading_summary, value))
                        Action(if (busy) R.string.saving else R.string.save_reading, busy) { saveReading() }
                        Secondary(R.string.back) { step = Step.VALUE }
                    }
                    Step.SAVED -> {
                        Heading(R.string.saved_on_phone)
                        BackupStatus(saved)
                        Action(R.string.add_another, busy) {
                            readingId = newReadingId()
                            eye = null
                            value = ""
                            valueError = null
                            saved = null
                            message = null
                            step = Step.EYE
                        }
                        Secondary(R.string.correct_reading) {
                            val current = saved ?: return@Secondary
                            readingId = current.id
                            eye = current.eye
                            value = current.value
                            step = Step.CORRECT_CHOICE
                        }
                        Secondary(R.string.finish_sitting) { step = Step.FINISH }
                    }
                    Step.CORRECT_CHOICE -> {
                        Heading(R.string.choose_correction)
                        Action(R.string.correct_eye, busy) { step = Step.CORRECT_EYE }
                        Secondary(R.string.correct_value) { step = Step.CORRECT_VALUE }
                        Secondary(R.string.back) { if (!busy) abandonCorrection() }
                    }
                    Step.CORRECT_REVIEW_EYE, Step.CORRECT_REVIEW_VALUE -> {
                        Heading(R.string.review_correction)
                        val current = saved
                        if (step == Step.CORRECT_REVIEW_EYE) {
                            Text(stringResource(R.string.previous_eye, eyeLabel(current?.eye)))
                            Text(stringResource(R.string.corrected_eye, eyeLabel(eye)))
                        } else {
                            Text(stringResource(R.string.previous_reading, current?.value.orEmpty()))
                            Text(stringResource(R.string.corrected_reading, value))
                        }
                        Action(if (busy) R.string.correction_saving else R.string.save_correction, busy) {
                            saveCorrection()
                        }
                        Secondary(R.string.back) {
                            step = if (step == Step.CORRECT_REVIEW_EYE) Step.CORRECT_EYE else Step.CORRECT_VALUE
                        }
                    }
                    Step.CORRECT_SAVED -> {
                        Heading(R.string.correction_saved)
                        BackupStatus(saved)
                        Action(R.string.add_another, busy) {
                            readingId = newReadingId()
                            eye = null
                            value = ""
                            valueError = null
                            saved = null
                            message = null
                            step = Step.EYE
                        }
                        Secondary(R.string.undo_correction) { undoCorrection() }
                        Secondary(R.string.finish_sitting) { step = Step.FINISH }
                    }
                    Step.UNDO_DONE -> {
                        Heading(R.string.undo_done)
                        BackupStatus(saved)
                        Action(R.string.add_another, busy) {
                            readingId = newReadingId()
                            eye = null
                            value = ""
                            valueError = null
                            saved = null
                            message = null
                            step = Step.EYE
                        }
                        Secondary(R.string.finish_sitting) { step = Step.FINISH }
                    }
                    Step.FINISH -> {
                        Heading(R.string.finish_title)
                        Text(stringResource(R.string.finish_body))
                        Action(R.string.finish, busy) { finishSitting() }
                        Secondary(R.string.keep_recording) {
                            step = if (saved != null) Step.SAVED else Step.EYE
                        }
                    }
                    Step.FINISHED -> {
                        Heading(R.string.sitting_finished)
                        Action(R.string.start_sitting, busy) { beginSitting() }
                        Secondary(R.string.archive_title) { step = Step.ARCHIVE }
                    }
                    Step.ARCHIVE -> ArchiveTransferScreen(repository) { step = Step.START }
                }
                message?.let {
                    Text(
                        stringResource(it),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(id: Int) {
    Text(
        stringResource(id),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
    )
}

@Composable
private fun Action(id: Int, disabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !disabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(id)) }
}

@Composable
private fun Secondary(id: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(id)) }
}

@Composable
private fun EyeOptions(selected: Eye?, onSelect: (Eye) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        for (choice in listOf(Eye.LEFT, Eye.RIGHT)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = selected == choice,
                        role = Role.RadioButton,
                        onClick = { onSelect(choice) },
                    ),
            ) {
                RadioButton(selected = selected == choice, onClick = null)
                Text(stringResource(if (choice == Eye.LEFT) R.string.left_eye else R.string.right_eye))
            }
        }
    }
}

@Composable
private fun ValueField(value: String, error: Int?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.reading_label)) },
        supportingText = { Text(stringResource(error ?: R.string.reading_hint)) },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun eyeLabel(eye: Eye?): String = when (eye) {
    Eye.LEFT -> stringResource(R.string.left_eye)
    Eye.RIGHT -> stringResource(R.string.right_eye)
    null -> ""
}

@Composable
private fun BackupStatus(reading: Reading?) {
    val id = if (reading?.backupState == BackupState.CONFIRMED) {
        R.string.backup_confirmed
    } else {
        R.string.backup_pending
    }
    Text(stringResource(id), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
