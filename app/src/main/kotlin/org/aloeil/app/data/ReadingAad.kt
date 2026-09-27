package org.aloeil.app.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Stable, length-prefixed context so a ciphertext only belongs to one row and revision. */
object ReadingAad {
    private fun value(type: String, id: String, revision: Long = 0, replica: Long = 0): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            listOf("aloeil-row-v2", type, id).forEach { part ->
                val utf8 = part.toByteArray(Charsets.UTF_8)
                output.writeInt(utf8.size)
                output.write(utf8)
            }
            output.writeLong(revision)
            output.writeLong(replica)
        }
        return bytes.toByteArray()
    }

    fun reading(id: String, revision: Long, replica: Long): ByteArray =
        value("reading", id, revision, replica)
    fun version(id: String, revision: Long): ByteArray = value("version", id, revision)
    fun sitting(id: String): ByteArray = value("sitting", id)
    fun draft(): ByteArray = value("draft", "1")
}
