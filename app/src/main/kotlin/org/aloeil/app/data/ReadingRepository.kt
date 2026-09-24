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
        valueTenths: Int,
    ): Reading {
        require(valueTenths > 0) { "Value must be positive" }
        require(sittingId.isNotBlank()) { "Sitting ID is required" }
        require(readingId.isNotBlank()) { "Reading ID is required" }
        val time = now()
        val sealed = cipher.seal("${eye.name}|$valueTenths".toByteArray(Charsets.UTF_8))
        val row = ReadingRow(readingId, sittingId, time, sealed.nonce, sealed.ciphertext, 1, 0)
        val stored = dao.saveOnPhone(
            row,
            OutboxRow(readingId, readingId, 1, 0, time),
        )
        val result = decode(stored)
        require(result.sittingId == sittingId && result.eye == eye && result.valueTenths == valueTenths) {
            "Reading ID was already used for different content"
        }
        return result
    }

    suspend fun all(): List<Reading> = dao.allReadings().map(::decode)

    suspend fun exportArchive(passphrase: CharArray): ByteArray =
        ArchiveCodec.encode(all(), passphrase)

    suspend fun importArchive(archive: ByteArray, passphrase: CharArray): Int {
        // Parse and authenticate the entire archive before beginning the database transaction.
        val readings = ArchiveCodec.decode(archive, passphrase)
        val rows = readings.map { reading ->
            val sealed = cipher.seal("${reading.eye.name}|${reading.valueTenths}".toByteArray(Charsets.UTF_8))
            ReadingRow(
                reading.id, reading.sittingId, reading.recordedAtMillis,
                sealed.nonce, sealed.ciphertext, reading.revision, 0,
            ) to OutboxRow(reading.id, reading.id, reading.revision, 0, now())
        }
        return dao.restoreMissing(rows)
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
            Eye.valueOf(plain[0]), plain[1].toInt(),
            row.revision, row.replicaConfirmedRevision,
        )
    }
}
