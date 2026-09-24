package org.aloeil.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, passphrase-encrypted export. The phone's non-exportable Keystore key never leaves
 * the device. Authentication is checked before any imported row reaches Room.
 */
object ArchiveCodec {
    private val magic = "ALOEIL01".toByteArray(Charsets.US_ASCII)
    private const val version = 1
    private const val maxBytes = 16 * 1024 * 1024
    private const val maxReadings = 100_000
    private const val iterations = 210_000
    private val random = SecureRandom()

    fun encode(readings: List<Reading>, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "An export passphrase is required" }
        require(readings.size <= maxReadings) { "Too many readings for one archive" }
        require(readings.map { it.id }.toSet().size == readings.size) { "Duplicate reading ID" }
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(readings.size)
                readings.forEach { reading ->
                    out.writeUTF(reading.id)
                    out.writeUTF(reading.sittingId)
                    out.writeLong(reading.recordedAtMillis)
                    out.writeUTF(reading.eye.name)
                    out.writeInt(reading.valueTenths)
                    out.writeLong(reading.revision)
                }
            }
        }.toByteArray()
        require(plain.size <= maxBytes) { "Archive is too large" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        val encrypted = cipher.doFinal(plain)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(magic)
                out.writeInt(version)
                out.write(salt)
                out.write(nonce)
                out.writeInt(encrypted.size)
                out.write(encrypted)
            }
        }.toByteArray()
    }

    fun decode(archive: ByteArray, passphrase: CharArray): List<Reading> {
        require(passphrase.isNotEmpty()) { "An export passphrase is required" }
        require(archive.size in 57..maxBytes + 64) { "Invalid archive size" }
        val input = DataInputStream(ByteArrayInputStream(archive))
        val header = ByteArray(magic.size).also(input::readFully)
        require(header.contentEquals(magic)) { "Not an Aloeil archive" }
        require(input.readInt() == version) { "Unsupported archive version" }
        val salt = ByteArray(16).also(input::readFully)
        val nonce = ByteArray(12).also(input::readFully)
        val length = input.readInt()
        require(length in 16..maxBytes + 16 && length == input.available()) { "Invalid archive length" }
        val encrypted = ByteArray(length).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        val plain = cipher.doFinal(encrypted)
        val values = DataInputStream(ByteArrayInputStream(plain))
        val count = values.readInt()
        require(count in 0..maxReadings) { "Invalid reading count" }
        val readings = (0 until count).map {
            val id = values.readUTF()
            val sitting = values.readUTF()
            val time = values.readLong()
            val eye = Eye.valueOf(values.readUTF())
            val value = values.readInt()
            val revision = values.readLong()
            require(id.isNotBlank() && sitting.isNotBlank() && value > 0 && revision > 0) {
                "Invalid archived reading"
            }
            Reading(id, sitting, time, eye, value, revision, 0)
        }
        require(values.available() == 0) { "Unexpected archive data" }
        require(readings.map { it.id }.toSet().size == readings.size) { "Duplicate reading ID" }
        return readings
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
