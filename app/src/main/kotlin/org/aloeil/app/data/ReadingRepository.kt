package org.aloeil.app.data

/** A save completes once Room commits the reading and its retryable outbox row. */
class ReadingRepository(
    private val dao: ReadingDao,
    private val cipher: ReadingCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
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
        val sealed = cipher.seal("${eye.name}|$value".toByteArray(Charsets.UTF_8))
        val row = ReadingRow(readingId, sittingId, time, sealed.nonce, sealed.ciphertext, 1, 0)
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
        dao.insertSitting(SittingRow(id, now(), null))
        return id
    }

    suspend fun openSitting(): SittingRow? = dao.openSitting()

    suspend fun finishSitting(id: String): Boolean {
        if (dao.finishSitting(id, now()) == 1) return true
        return dao.sitting(id)?.finishedAtMillis != null
    }

    suspend fun saveDraft(draft: DraftCheckpoint) {
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

    suspend fun clearDraft() = dao.clearDraft()

    suspend fun all(): List<Reading> = dao.allReadings().map(::decode)

    suspend fun exportArchive(passphrase: CharArray): ByteArray =
        ArchiveCodec.encode(all(), passphrase)

    suspend fun importArchive(archive: ByteArray, passphrase: CharArray): Int {
        // Parse and authenticate the entire archive before beginning the database transaction.
        val readings = ArchiveCodec.decode(archive, passphrase)
        val rows = readings.map { reading ->
            val sealed = cipher.seal("${reading.eye.name}|${reading.value}".toByteArray(Charsets.UTF_8))
            ReadingRow(
                reading.id, reading.sittingId, reading.recordedAtMillis,
                sealed.nonce, sealed.ciphertext, reading.revision, 0,
            ) to OutboxRow(reading.id, reading.id, reading.revision, 0, now())
        }
        return dao.restoreMissing(rows)
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
        val sealed = cipher.seal("${eye.name}|$value".toByteArray(Charsets.UTF_8))
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
        val plaintext = cipher.open(SealedPayload(prior.nonce, prior.ciphertext))
            .toString(Charsets.UTF_8).split('|')
        require(plaintext.size == 2) { "Invalid prior reading" }
        return correct(
            operationId,
            readingId,
            expectedRevision,
            Eye.valueOf(plaintext[0]),
            plaintext[1],
        )
    }

    suspend fun dueForReplica(): List<OutboxRow> = dao.dueOutbox(now())

    suspend fun retryLater(id: String, retryAtMillis: Long) = dao.retryLater(id, retryAtMillis)

    /** Only an acknowledgement for the current exact revision can change backup status. */
    suspend fun acknowledgeReplica(id: String, revision: Long): Boolean =
        dao.acknowledgeReplica(id, revision)

    private fun decode(row: ReadingRow): Reading {
        val plain = cipher.open(SealedPayload(row.nonce, row.ciphertext))
            .toString(Charsets.UTF_8).split('|')
        require(plain.size == 2) { "Invalid encrypted reading" }
        return Reading(
            row.id, row.sittingId, row.recordedAtMillis,
            Eye.valueOf(plain[0]), plain[1],
            row.revision, row.replicaConfirmedRevision,
        )
    }
}
