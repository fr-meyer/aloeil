package org.aloeil.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ArchivedVersion(
    val readingId: String,
    val revision: Long,
    val payload: ReadingPayload,
)

data class ArchivedOperation(
    val id: String,
    val readingId: String,
    val resultingRevision: Long,
)

data class ArchiveBundle(
    val readings: List<Reading>,
    val sittings: List<Sitting>,
    val versions: List<ArchivedVersion>,
    val operations: List<ArchivedOperation>,
)

/** Authenticated portable backup. The Android Keystore key never leaves the phone. */
object ArchiveCodec {
    private val magicV2 = "ALOEIL02".toByteArray(Charsets.US_ASCII)
    private val magicV1 = "ALOEIL01".toByteArray(Charsets.US_ASCII)
    private const val version = 2
    private const val maxBytes = 16 * 1024 * 1024
    private const val maxItems = 100_000
    private const val iterations = 210_000
    private val random = SecureRandom()

    fun encode(bundle: ArchiveBundle, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "An export passphrase is required" }
        validate(bundle)
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(bundle.readings.size)
                bundle.readings.forEach { out.writeReading(it) }
                out.writeInt(bundle.sittings.size)
                bundle.sittings.forEach { sitting ->
                    out.writeUTF(sitting.id)
                    out.writeLong(sitting.startedAtMillis)
                    out.writeBoolean(sitting.finishedAtMillis != null)
                    sitting.finishedAtMillis?.let(out::writeLong)
                }
                out.writeInt(bundle.versions.size)
                bundle.versions.forEach { item ->
                    out.writeUTF(item.readingId)
                    out.writeLong(item.revision)
                    out.writePayload(item.payload)
                }
                out.writeInt(bundle.operations.size)
                bundle.operations.forEach { item ->
                    out.writeUTF(item.id)
                    out.writeUTF(item.readingId)
                    out.writeLong(item.resultingRevision)
                }
            }
        }.toByteArray()
        require(plain.size <= maxBytes) { "Archive is too large" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magicV2)
        val encrypted = cipher.doFinal(plain)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(magicV2)
                out.writeInt(version)
                out.write(salt)
                out.write(nonce)
                out.writeInt(encrypted.size)
                out.write(encrypted)
            }
        }.toByteArray()
    }

    /** Version 1 archives are migrated additively; they had no sitting or correction history. */
    fun decode(archive: ByteArray, passphrase: CharArray): ArchiveBundle {
        require(passphrase.isNotEmpty()) { "An export passphrase is required" }
        require(archive.size in 57..maxBytes + 64) { "Invalid archive size" }
        val input = DataInputStream(ByteArrayInputStream(archive))
        val magic = ByteArray(8).also(input::readFully)
        val archiveVersion = input.readInt()
        require(
            (magic.contentEquals(magicV2) && archiveVersion == version) ||
                (magic.contentEquals(magicV1) && archiveVersion == 1),
        ) { "Unsupported Aloeil archive" }
        val salt = ByteArray(16).also(input::readFully)
        val nonce = ByteArray(12).also(input::readFully)
        val length = input.readInt()
        require(length in 16..maxBytes + 16 && length == input.available()) { "Invalid archive length" }
        val encrypted = ByteArray(length).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        val plain = cipher.doFinal(encrypted)
        val result = if (archiveVersion == 1) decodeLegacy(plain) else decodeV2(plain)
        validate(result)
        return result
    }

    private fun decodeV2(plain: ByteArray): ArchiveBundle {
        val input = DataInputStream(ByteArrayInputStream(plain))
        val readings = List(input.readCount()) { input.readReading() }
        val sittings = List(input.readCount()) {
            val id = input.readUTF()
            val started = input.readLong()
            val finished = if (input.readBoolean()) input.readLong() else null
            Sitting(id, started, finished)
        }
        val versions = List(input.readCount()) {
            ArchivedVersion(input.readUTF(), input.readLong(), input.readPayload())
        }
        val operations = List(input.readCount()) {
            ArchivedOperation(input.readUTF(), input.readUTF(), input.readLong())
        }
        require(input.available() == 0) { "Unexpected archive data" }
        return ArchiveBundle(readings, sittings, versions, operations)
    }

    private fun decodeLegacy(plain: ByteArray): ArchiveBundle {
        val readings = runCatching { readLegacyReadings(plain, stringValues = true) }
            .getOrElse { readLegacyReadings(plain, stringValues = false) }
        val sittings = readings.groupBy { it.sittingId }.map { (id, values) ->
            Sitting(id, values.minOf { it.recordedAtMillis }, values.maxOf { it.recordedAtMillis })
        }
        return ArchiveBundle(readings, sittings, emptyList(), emptyList())
    }

    private fun readLegacyReadings(plain: ByteArray, stringValues: Boolean): List<Reading> {
        val input = DataInputStream(ByteArrayInputStream(plain))
        val readings = List(input.readCount()) {
            val id = input.readUTF()
            val sittingId = input.readUTF()
            val time = input.readLong()
            val eye = Eye.valueOf(input.readUTF())
            val value = if (stringValues) input.readUTF() else {
                BigDecimal(input.readInt()).movePointLeft(1).toPlainString()
            }
            val revision = input.readLong()
            Reading(id, sittingId, time, eye, value, revision, 0)
        }
        require(input.available() == 0) { "Unexpected legacy archive data" }
        return readings
    }

    private fun validate(bundle: ArchiveBundle) {
        require(
            bundle.readings.size <= maxItems && bundle.sittings.size <= maxItems &&
                bundle.versions.size <= maxItems && bundle.operations.size <= maxItems,
        ) { "Too many archive items" }
        require(bundle.readings.map { it.id }.toSet().size == bundle.readings.size) {
            "Duplicate reading ID"
        }
        require(bundle.sittings.map { it.id }.toSet().size == bundle.sittings.size) {
            "Duplicate sitting ID"
        }
        require(bundle.versions.map { it.readingId to it.revision }.toSet().size == bundle.versions.size) {
            "Duplicate reading revision"
        }
        require(bundle.operations.map { it.id }.toSet().size == bundle.operations.size) {
            "Duplicate correction operation"
        }
        val readings = bundle.readings.associateBy { it.id }
        val sittings = bundle.sittings.map { it.id }.toSet()
        bundle.readings.forEach {
            require(it.id.isNotBlank() && it.sittingId in sittings && it.revision > 0)
            require(ReadingValue.parse(it.value) == ReadingValueResult.Valid(it.value))
        }
        bundle.sittings.forEach {
            require(it.id.isNotBlank())
            require(it.finishedAtMillis == null || it.finishedAtMillis >= it.startedAtMillis)
        }
        bundle.versions.forEach {
            val current = readings[it.readingId] ?: error("Orphaned reading revision")
            require(it.revision in 1L until current.revision)
            require(it.payload.sittingId == current.sittingId)
            require(it.payload.recordedAtMillis == current.recordedAtMillis)
            require(ReadingValue.parse(it.payload.value) == ReadingValueResult.Valid(it.payload.value))
        }
        bundle.operations.forEach {
            val current = readings[it.readingId] ?: error("Orphaned correction operation")
            require(it.id.isNotBlank() && it.resultingRevision in 2L..current.revision)
        }
    }

    private fun DataOutputStream.writeReading(reading: Reading) {
        writeUTF(reading.id)
        writePayload(
            ReadingPayload(reading.sittingId, reading.recordedAtMillis, reading.eye, reading.value),
        )
        writeLong(reading.revision)
    }

    private fun DataInputStream.readReading(): Reading {
        val id = readUTF()
        val payload = readPayload()
        return Reading(
            id, payload.sittingId, payload.recordedAtMillis, payload.eye, payload.value,
            readLong(), 0,
        )
    }

    private fun DataOutputStream.writePayload(payload: ReadingPayload) {
        writeUTF(payload.sittingId)
        writeLong(payload.recordedAtMillis)
        writeUTF(payload.eye.name)
        writeUTF(payload.value)
    }

    private fun DataInputStream.readPayload(): ReadingPayload =
        ReadingPayload(readUTF(), readLong(), Eye.valueOf(readUTF()), readUTF())

    private fun DataInputStream.readCount(): Int = readInt().also {
        require(it in 0..maxItems) { "Invalid archive item count" }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }
}
