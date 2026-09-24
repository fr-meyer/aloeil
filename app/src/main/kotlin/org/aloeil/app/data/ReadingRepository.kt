package org.aloeil.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ArchivePreview(val readingCount: Int, val sittingCount: Int)

/** A save completes once Room commits the reading and its retryable outbox row. */
class ReadingRepository(
    private val dao: ReadingDao,
    private val cipher: ReadingCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val draftMutex = Mutex()

    suspend fun record(
        readingId: String,
        sittingId: String,
        eye: Eye,
        valueInput: String,
    ): Reading {
        val value = (ReadingValue.parse(valueInput) as? ReadingValueResult.Valid)?.canonical
            ?: throw IllegalArgumentException("Invalid reading syntax")
        require(sittingId.isNotBlank()) { "Sitting ID is required" }
        require(readingId.isNotBlank()) { "Reading ID is required" }
        val time = now()
        val sealed = cipher.seal(
            ReadingPayloadCodec.encode(ReadingPayload(sittingId, time, eye, value)),
        )
        val row = ReadingRow(readingId, sealed.nonce, sealed.ciphertext, 1, 0)
        val stored = dao.saveOnPhone(
            row,
            OutboxRow(readingId, readingId, 1, 0, time),
        )
        val result = decode(stored)
        require(result.sittingId == sittingId && result.eye == eye && result.value == value) {
            "Reading ID was already used for different content"
        }
        return result
    }

    suspend fun startSitting(id: String = newSittingId()): String {
        require(id.isNotBlank()) { "Sitting ID is required" }
        val sealed = cipher.seal(SittingPayloadCodec.encode(Sitting(id, now(), null)))
        dao.insertSitting(SittingRow(id, sealed.nonce, sealed.ciphertext))
        return id
    }

    suspend fun openSitting(): Sitting? = dao.allSittings()
        .map(::decodeSitting)
        .filter { it.finishedAtMillis == null }
        .maxByOrNull { it.startedAtMillis }

    suspend fun finishSitting(id: String): Boolean {
        val stored = dao.sitting(id) ?: return false
        val current = decodeSitting(stored)
        if (current.finishedAtMillis != null) return true
        val sealed = cipher.seal(SittingPayloadCodec.encode(current.copy(finishedAtMillis = now())))
        return dao.updateSitting(stored.copy(nonce = sealed.nonce, ciphertext = sealed.ciphertext)) == 1
    }

    suspend fun saveDraft(draft: DraftCheckpoint) = draftMutex.withLock {
        require(draft.sittingId.isNotBlank() && draft.readingId.isNotBlank())
        val sealed = cipher.seal(DraftCodec.encode(draft))
        dao.saveDraft(DraftRow(nonce = sealed.nonce, ciphertext = sealed.ciphertext))
    }

    suspend fun recoverDraft(): Pair<DraftCheckpoint, Reading?>? {
        val row = dao.draft() ?: return null
        val draft = DraftCodec.decode(cipher.open(SealedPayload(row.nonce, row.ciphertext)))
        val saved = dao.reading(draft.readingId)?.let(::decode)
        require(saved == null || saved.sittingId == draft.sittingId) { "Draft ID conflict" }
        return draft to saved
    }

    suspend fun clearDraft() = draftMutex.withLock { dao.clearDraft() }

    suspend fun all(): List<Reading> = dao.allReadings().map(::decode)
        .sortedWith(compareByDescending<Reading> { it.recordedAtMillis }.thenByDescending { it.id })

    suspend fun exportArchive(passphrase: CharArray): ByteArray {
        val snapshot = dao.archiveSnapshot()
        val bundle = ArchiveBundle(
            readings = snapshot.readings.map(::decode),
            sittings = snapshot.sittings.map(::decodeSitting),
            versions = snapshot.versions.map { row ->
                ArchivedVersion(
                    row.readingId,
                    row.revision,
                    ReadingPayloadCodec.decode(
                        cipher.open(SealedPayload(row.nonce, row.ciphertext)),
                    ),
                )
            },
            operations = snapshot.operations.map {
                ArchivedOperation(it.id, it.readingId, it.resultingRevision)
            },
        )
        return ArchiveCodec.encode(bundle, passphrase)
    }

    fun previewArchive(archive: ByteArray, passphrase: CharArray): ArchivePreview {
        val bundle = ArchiveCodec.decode(archive, passphrase)
        return ArchivePreview(bundle.readings.size, bundle.sittings.size)
    }

    suspend fun importArchive(archive: ByteArray, passphrase: CharArray): Int {
        // Authenticate and validate every item before the database transaction.
        val bundle = ArchiveCodec.decode(archive, passphrase)
        val sittings = bundle.sittings.map { sitting ->
            val sealed = cipher.seal(SittingPayloadCodec.encode(sitting))
            SittingRow(sitting.id, sealed.nonce, sealed.ciphertext) to sitting
        }
        val rows = bundle.readings.map { reading ->
            val sealed = cipher.seal(
                ReadingPayloadCodec.encode(
                    ReadingPayload(
                        reading.sittingId, reading.recordedAtMillis,
                        reading.eye, reading.value,
                    ),
                ),
            )
            ReadingRow(
                reading.id, sealed.nonce, sealed.ciphertext, reading.revision, 0,
            ) to OutboxRow(
                reading.id + ":" + reading.revision, reading.id, reading.revision, 0, now(),
            )
        }
        val versions = bundle.versions.map { item ->
            val sealed = cipher.seal(ReadingPayloadCodec.encode(item.payload))
            ReadingVersionRow(item.readingId, item.revision, sealed.nonce, sealed.ciphertext)
        }
        val operations = bundle.operations.map { item ->
            CorrectionOperationRow(item.id, item.readingId, item.resultingRevision)
        }
        return dao.restoreArchive(sittings, rows, versions, operations, bundle.readings, cipher)
    }

    /** A correction keeps the reading ID and records the old encrypted version for undo. */
    suspend fun correct(
        operationId: String,
        readingId: String,
        expectedRevision: Long,
        eye: Eye,
        valueInput: String,
    ): Reading? {
        require(operationId.isNotBlank())
        val value = (ReadingValue.parse(valueInput) as? ReadingValueResult.Valid)?.canonical
            ?: throw IllegalArgumentException("Invalid reading syntax")
        dao.correctionOperation(operationId)?.let { applied ->
            if (applied.readingId != readingId || applied.resultingRevision != expectedRevision + 1) {
                return null
            }
            return dao.reading(readingId)?.let(::decode)
        }
        val old = dao.reading(readingId) ?: return null
        if (old.revision != expectedRevision) return null
        val oldPayload = decode(old)
        val sealed = cipher.seal(
            ReadingPayloadCodec.encode(
                ReadingPayload(oldPayload.sittingId, oldPayload.recordedAtMillis, eye, value),
            ),
        )
        val updated = old.copy(
            nonce = sealed.nonce,
            ciphertext = sealed.ciphertext,
            revision = old.revision + 1,
        )
        val outbox = OutboxRow(
            "$readingId:${updated.revision}",
            readingId,
            updated.revision,
            0,
            now(),
        )
        if (!dao.applyCorrection(operationId, expectedRevision, updated, outbox)) return null
        return dao.reading(readingId)?.let(::decode)
    }

    /** Undo is itself a new revision, preserving both earlier facts and the correction. */
    suspend fun undoCorrection(
        operationId: String,
        readingId: String,
        expectedRevision: Long,
    ): Reading? {
        require(expectedRevision > 1)
        val prior = dao.version(readingId, expectedRevision - 1) ?: return null
        val priorPayload = ReadingPayloadCodec.decode(
            cipher.open(SealedPayload(prior.nonce, prior.ciphertext)),
        )
        return correct(
            operationId,
            readingId,
            expectedRevision,
            priorPayload.eye,
            priorPayload.value,
        )
    }

    suspend fun dueForReplica(): List<OutboxRow> = dao.dueOutbox(now())

    suspend fun retryLater(id: String, retryAtMillis: Long) = dao.retryLater(id, retryAtMillis)

    /** Only an acknowledgement for the current exact revision can change backup status. */
    suspend fun acknowledgeReplica(id: String, revision: Long): Boolean =
        dao.acknowledgeReplica(id, revision)

    private fun decode(row: ReadingRow): Reading {
        val payload = ReadingPayloadCodec.decode(
            cipher.open(SealedPayload(row.nonce, row.ciphertext)),
        )
        return Reading(
            row.id, payload.sittingId, payload.recordedAtMillis,
            payload.eye, payload.value, row.revision, row.replicaConfirmedRevision,
        )
    }

    private fun decodeSitting(row: SittingRow): Sitting =
        SittingPayloadCodec.decode(
            row.id,
            cipher.open(SealedPayload(row.nonce, row.ciphertext)),
        )
}
