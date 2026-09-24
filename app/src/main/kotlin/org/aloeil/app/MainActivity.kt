package org.aloeil.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.CancellationException
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
import org.aloeil.app.data.UnreadableLocalStoreException
import org.aloeil.app.data.Sitting
import org.aloeil.app.data.ReadingValue
import org.aloeil.app.data.ReadingValueResult
import org.aloeil.app.data.RangeState
import org.aloeil.app.data.Reason
import org.aloeil.app.data.newReadingId
import org.aloeil.app.data.restoredDraftStep

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        val database = ReadingDatabase.open(appContext)
        val cipher = AndroidKeystoreReadingCipher()
        val repository = ReadingRepository(database.readings(), cipher)
        setContent {
            AloeilApp(repository, resetUnreadableStore = {
                withContext(Dispatchers.IO) {
                    ReadingDatabase.resetUnreadableStore(appContext)
                    cipher.deleteKeyForRecovery()
                }
                recreate()
            })
        }
    }
}

/** Explicit in-app Back owns navigation while a draft or write is active. */
@Composable
internal fun BlockSystemBackWhenUnsafe(enabled: Boolean) {
    BackHandler(enabled = enabled) { }
}

private enum class Step {
    LOADING, START, EYE, VALUE, NOTE, REVIEW, SAVED,
    CORRECT_CHOICE, CORRECT_EYE, CORRECT_VALUE, CORRECT_NOTE,
    CORRECT_REVIEW_EYE, CORRECT_REVIEW_VALUE, CORRECT_REVIEW_NOTE, CORRECT_SAVED, UNDO_DONE,
    FINISH, FINISHED, DELETE_CONFIRM, DELETED, HISTORY, HISTORY_READING, CSV_EXPORT, ARCHIVE,
    RECOVERY, RECOVERY_CONFIRM,
}

@Composable
internal fun AloeilApp(
    repository: ReadingRepository,
    resetUnreadableStore: (suspend () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.LOADING) }
    var archiveReturnPending by rememberSaveable { mutableStateOf(false) }
    var sittingId by remember { mutableStateOf("") }
    var readingId by remember { mutableStateOf("") }
    var eye by remember { mutableStateOf<Eye?>(null) }
    var value by remember { mutableStateOf("") }
    var rangeState by remember { mutableStateOf<RangeState?>(null) }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Reading?>(null) }
    var selectedSitting by remember { mutableStateOf<Sitting?>(null) }
    var fromHistory by remember { mutableStateOf(false) }
    var historyReturnToFinished by remember { mutableStateOf(false) }
    var csvReturnStep by remember { mutableStateOf(Step.START) }
    var captureSittingId by remember { mutableStateOf("") }
    var hasOpenSitting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var valueError by remember { mutableStateOf<Int?>(null) }

    val editingDraft = step in setOf(
        Step.EYE, Step.VALUE, Step.NOTE, Step.REVIEW,
        Step.CORRECT_CHOICE, Step.CORRECT_EYE, Step.CORRECT_VALUE, Step.CORRECT_NOTE,
        Step.CORRECT_REVIEW_EYE, Step.CORRECT_REVIEW_VALUE, Step.CORRECT_REVIEW_NOTE,
    )
    BlockSystemBackWhenUnsafe(busy || editingDraft)

    LaunchedEffect(step) {
        if (step == Step.ARCHIVE) archiveReturnPending = true
        else if (step != Step.LOADING) archiveReturnPending = false
    }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { repository.verifyReadable() }
            val recovered = withContext(Dispatchers.IO) { repository.recoverDraft() }
            if (recovered != null) {
                val (draft, committed) = recovered
                sittingId = draft.sittingId
                readingId = draft.readingId
                eye = draft.eye
                value = draft.input
                rangeState = draft.rangeState
                note = draft.note
                fromHistory = draft.fromHistory
                val open = withContext(Dispatchers.IO) { repository.openSitting() }
                hasOpenSitting = open != null
                if (fromHistory) {
                    captureSittingId = open?.id.orEmpty()
                    selectedSitting = withContext(Dispatchers.IO) {
                        repository.allSittings().firstOrNull { it.id == draft.sittingId }
                    }
                }
                saved = committed
                step = runCatching { Step.valueOf(restoredDraftStep(draft, committed)) }
                    .getOrDefault(Step.EYE)
            } else {
                val open = withContext(Dispatchers.IO) { repository.openSitting() }
                if (open != null) {
                    sittingId = open.id
                    hasOpenSitting = true
                }
                step = if (archiveReturnPending) Step.ARCHIVE else Step.START
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnreadableLocalStoreException) {
            message = null
            step = Step.RECOVERY
        } catch (_: Exception) {
            message = R.string.error_draft_restore
            val open = runCatching {
                withContext(Dispatchers.IO) { repository.openSitting() }
            }.getOrNull()
            sittingId = open?.id.orEmpty()
            hasOpenSitting = open != null
            fromHistory = false
            step = Step.START
        }
    }

    LaunchedEffect(step, sittingId, readingId, eye, value, rangeState, note, fromHistory, busy) {
        if (!busy && sittingId.isNotEmpty() && readingId.isNotEmpty() && step in setOf(
                Step.EYE, Step.VALUE, Step.NOTE, Step.REVIEW, Step.CORRECT_CHOICE,
                Step.CORRECT_EYE, Step.CORRECT_VALUE, Step.CORRECT_NOTE,
                Step.CORRECT_REVIEW_EYE, Step.CORRECT_REVIEW_VALUE, Step.CORRECT_REVIEW_NOTE,
            )
        ) {
            try {
                withContext(Dispatchers.IO) {
                    repository.saveDraft(
                        DraftCheckpoint(
                            sittingId, readingId, step.name, eye, value,
                            when (step) {
                                Step.VALUE, Step.CORRECT_VALUE -> "reading"
                                Step.NOTE, Step.CORRECT_NOTE -> "note"
                                Step.EYE, Step.CORRECT_EYE -> "eye"
                                else -> "heading"
                            },
                            baseRevision = if (step.name.startsWith("CORRECT_")) saved?.revision else null,
                            rangeState = rangeState,
                            note = note,
                            fromHistory = fromHistory,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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
                rangeState = null
                note = ""
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
                Reason.LENGTH -> R.string.error_length
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
                    if (rangeState == null) {
                        repository.record(readingId, sittingId, selected, value, note)
                    } else {
                        repository.recordRange(readingId, sittingId, selected, rangeState!!, note)
                    }
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
                    val operationId = current.id + ":correct:" + (current.revision + 1)
                    when (step) {
                        Step.CORRECT_REVIEW_EYE ->
                            repository.correctEye(operationId, current.id, current.revision, selected)
                        Step.CORRECT_REVIEW_NOTE ->
                            repository.correctNote(operationId, current.id, current.revision, note)
                        Step.CORRECT_REVIEW_VALUE ->
                            if (rangeState == null) {
                                repository.correct(operationId, current.id, current.revision, selected, value)
                            } else {
                                repository.correctRange(operationId, current.id, current.revision, rangeState!!)
                            }
                        else -> null
                    }
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
                            rangeState = current.rangeState, note = current.note.orEmpty(),
                            fromHistory = fromHistory,
                        ),
                    )
                }
            }.isSuccess
            if (recorded) {
                eye = current.eye
                value = current.value
                rangeState = current.rangeState
                note = current.note.orEmpty()
                step = Step.SAVED
            } else {
                message = R.string.error_storage
            }
            busy = false
        }
    }

    fun deleteReading() {
        val current = saved ?: return
        busy = true
        message = null
        scope.launch {
            val deleted = runCatching {
                withContext(Dispatchers.IO) {
                    repository.deleteReading(current.id, current.revision)
                }
            }.getOrDefault(false)
            if (deleted) {
                runCatching { withContext(Dispatchers.IO) { repository.clearDraft() } }
                saved = null
                readingId = ""
                eye = null
                value = ""
                rangeState = null
                note = ""
                step = Step.DELETED
            } else {
                message = R.string.error_delete
            }
            busy = false
        }
    }

    fun enterCsv() {
        csvReturnStep = step
        step = Step.CSV_EXPORT
    }

    fun enterHistory() {
        // Only Start and Finished currently expose History. Keep the return target safe
        // even if another screen gains a History entry point later.
        historyReturnToFinished = step == Step.FINISHED
        captureSittingId = sittingId
        step = Step.HISTORY
    }

    fun returnToHistory() {
        busy = true
        scope.launch {
            val cleared = runCatching {
                withContext(Dispatchers.IO) { repository.clearDraft() }
            }.isSuccess
            if (cleared) {
                sittingId = captureSittingId
                saved = null
                selectedSitting = null
                fromHistory = false
                step = Step.HISTORY
                message = null
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
                    Step.RECOVERY -> {
                        Heading(R.string.recovery_unreadable_title)
                        Text(stringResource(R.string.recovery_unreadable_body))
                        Text(stringResource(R.string.recovery_keep_backup))
                        Action(R.string.recovery_prepare_reset, busy || resetUnreadableStore == null) {
                            step = Step.RECOVERY_CONFIRM
                        }
                    }
                    Step.RECOVERY_CONFIRM -> {
                        Heading(R.string.recovery_confirm_title)
                        Text(stringResource(R.string.recovery_confirm_body))
                        Action(R.string.recovery_confirm_reset, busy || resetUnreadableStore == null) {
                            busy = true
                            scope.launch {
                                try {
                                    resetUnreadableStore?.invoke()
                                        ?: error("Recovery reset is unavailable")
                                } catch (_: Exception) {
                                    message = R.string.recovery_reset_error
                                    step = Step.RECOVERY
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        Secondary(R.string.back, busy) { step = Step.RECOVERY }
                    }
                    Step.START -> {
                        Heading(if (hasOpenSitting) R.string.interrupted_title else R.string.start_sitting)
                        if (hasOpenSitting) Text(stringResource(R.string.interrupted_body))
                        Action(if (hasOpenSitting) R.string.resume_sitting else R.string.start_sitting, busy) {
                            beginSitting()
                        }
                        Secondary(R.string.archive_title, busy) { step = Step.ARCHIVE }
                        Secondary(R.string.history_title, busy) { enterHistory() }
                        Secondary(R.string.csv_title, busy) { enterCsv() }
                    }
                    Step.EYE, Step.CORRECT_EYE -> {
                        Heading(if (step == Step.EYE) R.string.choose_eye else R.string.correct_eye)
                        EyeOptions(eye) { eye = it }
                        Action(R.string.continue_action, busy || eye == null) {
                            step = if (step == Step.EYE) Step.VALUE else Step.CORRECT_REVIEW_EYE
                        }
                        Secondary(R.string.back, busy) {
                            if (step == Step.CORRECT_EYE) {
                                step = Step.CORRECT_CHOICE
                            } else {
                                busy = true
                                scope.launch {
                                    val cleared = runCatching {
                                        withContext(Dispatchers.IO) { repository.clearDraft() }
                                    }.isSuccess
                                    if (cleared) {
                                        readingId = ""
                                        eye = null
                                        value = ""
                                        rangeState = null
                                        note = ""
                                        step = Step.START
                                    } else {
                                        message = R.string.error_storage
                                    }
                                    busy = false
                                }
                            }
                        }
                    }
                    Step.VALUE, Step.CORRECT_VALUE -> {
                        Heading(if (step == Step.VALUE) R.string.enter_reading else R.string.correct_value)
                        ValueField(value, valueError, {
                            value = it
                            rangeState = null
                            valueError = null
                        }, onTooLong = { valueError = R.string.error_length })
                        Action(R.string.continue_action, busy) {
                            if (validateValue()) {
                                rangeState = null
                                step = if (step == Step.VALUE) Step.NOTE else Step.CORRECT_REVIEW_VALUE
                            }
                        }
                        Text(stringResource(R.string.range_state_explain))
                        Secondary(R.string.below_range, busy) {
                            value = ""
                            rangeState = RangeState.BELOW_RANGE
                            valueError = null
                            step = if (step == Step.VALUE) Step.NOTE else Step.CORRECT_REVIEW_VALUE
                        }
                        Secondary(R.string.above_range, busy) {
                            value = ""
                            rangeState = RangeState.ABOVE_RANGE
                            valueError = null
                            step = if (step == Step.VALUE) Step.NOTE else Step.CORRECT_REVIEW_VALUE
                        }
                        Secondary(R.string.back, busy) {
                            step = if (step == Step.VALUE) Step.EYE else Step.CORRECT_CHOICE
                        }
                    }
                    Step.NOTE -> {
                        Heading(R.string.add_note)
                        NoteField(note) { note = it }
                        Action(R.string.continue_action, busy) { step = Step.REVIEW }
                        Secondary(R.string.back, busy) { step = Step.VALUE }
                    }
                    Step.REVIEW -> {
                        Heading(R.string.review_before_save)
                        Text(stringResource(R.string.not_saved))
                        Text(stringResource(R.string.eye_summary, eyeLabel(eye)))
                        Text(stringResource(R.string.reading_summary, readingLabel(value, rangeState)))
                        if (note.isNotEmpty()) Text(stringResource(R.string.note_summary, note))
                        Action(if (busy) R.string.saving else R.string.save_reading, busy) { saveReading() }
                        Secondary(R.string.back, busy) { step = Step.NOTE }
                    }
                    Step.SAVED -> {
                        Heading(R.string.saved_on_phone)
                        BackupStatus(saved)
                        if (fromHistory) {
                            Action(R.string.history_back, busy) { returnToHistory() }
                        } else {
                            Action(R.string.add_another, busy) {
                                readingId = newReadingId()
                                eye = null
                                value = ""
                                rangeState = null
                                note = ""
                                valueError = null
                                saved = null
                                message = null
                                step = Step.EYE
                            }
                        }
                        Secondary(R.string.correct_reading, busy) {
                            val current = saved ?: return@Secondary
                            readingId = current.id
                            eye = current.eye
                            value = current.value
                            rangeState = current.rangeState
                            note = current.note.orEmpty()
                            step = Step.CORRECT_CHOICE
                        }
                        if (!fromHistory) {
                            Secondary(R.string.finish_sitting, busy) { step = Step.FINISH }
                        }
                        Secondary(R.string.delete_reading, busy) { step = Step.DELETE_CONFIRM }
                    }
                    Step.CORRECT_CHOICE -> {
                        Heading(R.string.choose_correction)
                        Action(R.string.correct_eye, busy) { step = Step.CORRECT_EYE }
                        Secondary(R.string.correct_value, busy) { step = Step.CORRECT_VALUE }
                        Secondary(R.string.correct_note, busy) { step = Step.CORRECT_NOTE }
                        Secondary(R.string.back, busy) { if (!busy) abandonCorrection() }
                    }
                    Step.CORRECT_NOTE -> {
                        Heading(R.string.correct_note)
                        NoteField(note) { note = it }
                        Action(R.string.continue_action, busy) { step = Step.CORRECT_REVIEW_NOTE }
                        Secondary(R.string.back, busy) { step = Step.CORRECT_CHOICE }
                    }
                    Step.CORRECT_REVIEW_EYE, Step.CORRECT_REVIEW_VALUE, Step.CORRECT_REVIEW_NOTE -> {
                        Heading(R.string.review_correction)
                        val current = saved
                        if (step == Step.CORRECT_REVIEW_EYE) {
                            Text(stringResource(R.string.previous_eye, eyeLabel(current?.eye)))
                            Text(stringResource(R.string.corrected_eye, eyeLabel(eye)))
                        } else if (step == Step.CORRECT_REVIEW_NOTE) {
                            Text(stringResource(R.string.previous_note, current?.note.orEmpty()))
                            Text(stringResource(R.string.corrected_note, note))
                        } else {
                            Text(stringResource(
                                R.string.previous_reading,
                                readingLabel(current?.value.orEmpty(), current?.rangeState),
                            ))
                            Text(stringResource(R.string.corrected_reading, readingLabel(value, rangeState)))
                        }
                        Action(if (busy) R.string.correction_saving else R.string.save_correction, busy) {
                            saveCorrection()
                        }
                        Secondary(R.string.back, busy) {
                            step = when (step) {
                                Step.CORRECT_REVIEW_EYE -> Step.CORRECT_EYE
                                Step.CORRECT_REVIEW_NOTE -> Step.CORRECT_NOTE
                                else -> Step.CORRECT_VALUE
                            }
                        }
                    }
                    Step.CORRECT_SAVED -> {
                        Heading(R.string.correction_saved)
                        BackupStatus(saved)
                        if (fromHistory) {
                            Action(R.string.history_back, busy) { returnToHistory() }
                        } else {
                            Action(R.string.add_another, busy) {
                                readingId = newReadingId()
                                eye = null
                                value = ""
                                rangeState = null
                                note = ""
                                valueError = null
                                saved = null
                                message = null
                                step = Step.EYE
                            }
                        }
                        Secondary(R.string.undo_correction, busy) { undoCorrection() }
                        if (!fromHistory) {
                            Secondary(R.string.finish_sitting, busy) { step = Step.FINISH }
                        }
                        Secondary(R.string.delete_reading, busy) { step = Step.DELETE_CONFIRM }
                    }
                    Step.UNDO_DONE -> {
                        Heading(R.string.undo_done)
                        BackupStatus(saved)
                        if (fromHistory) {
                            Action(R.string.history_back, busy) { returnToHistory() }
                        } else {
                            Action(R.string.add_another, busy) {
                                readingId = newReadingId()
                                eye = null
                                value = ""
                                rangeState = null
                                note = ""
                                valueError = null
                                saved = null
                                message = null
                                step = Step.EYE
                            }
                        }
                        if (!fromHistory) {
                            Secondary(R.string.finish_sitting, busy) { step = Step.FINISH }
                        }
                        Secondary(R.string.delete_reading, busy) { step = Step.DELETE_CONFIRM }
                    }
                    Step.FINISH -> {
                        Heading(R.string.finish_title)
                        Text(stringResource(R.string.finish_body))
                        Action(R.string.finish, busy) { finishSitting() }
                        Secondary(R.string.keep_recording, busy) {
                            if (saved != null) step = Step.SAVED else beginSitting()
                        }
                    }
                    Step.FINISHED -> {
                        Heading(R.string.sitting_finished)
                        Action(R.string.start_sitting, busy) { beginSitting() }
                        Secondary(R.string.archive_title, busy) { step = Step.ARCHIVE }
                        Secondary(R.string.history_title, busy) { enterHistory() }
                        Secondary(R.string.csv_title, busy) { enterCsv() }
                    }
                    Step.DELETE_CONFIRM -> {
                        Heading(R.string.delete_confirm_title)
                        Text(stringResource(R.string.eye_summary, eyeLabel(saved?.eye)))
                        Text(stringResource(
                            R.string.reading_summary,
                            readingLabel(saved?.value.orEmpty(), saved?.rangeState),
                        ))
                        Text(stringResource(R.string.delete_confirm_body))
                        Action(R.string.confirm_delete, busy) { deleteReading() }
                        Secondary(R.string.keep_reading, busy) {
                            step = if (fromHistory) Step.HISTORY_READING else Step.SAVED
                        }
                    }
                    Step.DELETED -> {
                        Heading(R.string.deleted_on_phone)
                        Text(stringResource(R.string.deleted_backup_warning))
                        if (fromHistory) {
                            Action(R.string.history_back, busy) { returnToHistory() }
                        } else {
                            Action(R.string.add_another, busy) { beginSitting() }
                            Secondary(R.string.finish_sitting, busy) { step = Step.FINISH }
                        }
                    }
                    Step.HISTORY -> HistoryScreen(
                        repository = repository,
                        onSelect = { reading, sitting ->
                            fromHistory = true
                            saved = reading
                            selectedSitting = sitting
                            sittingId = reading.sittingId
                            readingId = reading.id
                            eye = reading.eye
                            value = reading.value
                            rangeState = reading.rangeState
                            note = reading.note.orEmpty()
                            step = Step.HISTORY_READING
                        },
                        onBack = { step = if (historyReturnToFinished) Step.FINISHED else Step.START },
                    )
                    Step.HISTORY_READING -> {
                        val current = saved
                        if (current != null) {
                            HistoryReadingDetail(
                                reading = current,
                                sitting = selectedSitting,
                                busy = busy,
                                onCorrect = { step = Step.CORRECT_CHOICE },
                                onUndo = { undoCorrection() },
                                onDelete = { step = Step.DELETE_CONFIRM },
                                onBack = { returnToHistory() },
                            )
                        }
                    }
                    Step.CSV_EXPORT -> CsvExportScreen(repository) { step = csvReturnStep }
                    Step.ARCHIVE -> ArchiveTransferScreen(repository) {
                        busy = true
                        step = Step.LOADING
                        scope.launch {
                            try {
                                val open = withContext(Dispatchers.IO) {
                                    repository.openSitting()
                                }
                                sittingId = open?.id.orEmpty()
                                hasOpenSitting = open != null
                                step = Step.START
                            } catch (_: Exception) {
                                message = R.string.error_storage
                                step = Step.ARCHIVE
                            } finally {
                                busy = false
                            }
                        }
                    }
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
internal fun Secondary(id: Int, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
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
private fun ValueField(
    value: String,
    error: Int?,
    onChange: (String) -> Unit,
    onTooLong: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { proposed ->
            if (proposed.length <= ReadingValue.MAX_LENGTH) onChange(proposed) else onTooLong()
        },
        label = { Text(stringResource(R.string.reading_label)) },
        supportingText = { Text(stringResource(error ?: R.string.reading_hint)) },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun eyeLabel(eye: Eye?): String = when (eye) {
    Eye.LEFT -> stringResource(R.string.left_eye)
    Eye.RIGHT -> stringResource(R.string.right_eye)
    null -> ""
}

@Composable
private fun NoteField(note: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = note,
        onValueChange = { if (it.length <= 1000) onChange(it) },
        label = { Text(stringResource(R.string.note_label)) },
        supportingText = { Text(stringResource(R.string.note_hint)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun readingLabel(value: String, rangeState: RangeState?): String = when (rangeState) {
    RangeState.BELOW_RANGE -> stringResource(R.string.below_range)
    RangeState.ABOVE_RANGE -> stringResource(R.string.above_range)
    null -> stringResource(R.string.numeric_reading, value)
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
