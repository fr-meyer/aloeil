package org.aloeil.app

import org.aloeil.app.data.ArchiveCodec
import org.aloeil.app.data.BackupState
import org.aloeil.app.data.Eye
import org.aloeil.app.data.Reading

/**
 * JVM-only verification with an explicitly synthetic fixture.
 *
 * The fixture contains build labels only: no health data, units, or sample readings.
 */
object SyntheticFixtureJvmTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = SyntheticBuildFixture(
            builder = "github-actions",
            records = emptyList(),
        )

        check(fixture.builder == "github-actions")
        check(fixture.records.isEmpty())

        val synthetic = Reading(
            id = "synthetic-id-1",
            sittingId = "synthetic-sitting-1",
            recordedAtMillis = 1_700_000_000_000L,
            eye = Eye.LEFT,
            valueTenths = 123,
            revision = 1,
            replicaConfirmedRevision = 0,
        )
        check(synthetic.backupState == BackupState.PENDING)
        val passphrase = "synthetic-test-only".toCharArray()
        val archive = ArchiveCodec.encode(listOf(synthetic), passphrase)
        check(ArchiveCodec.decode(archive, passphrase) == listOf(synthetic))
        val tampered = archive.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        check(runCatching { ArchiveCodec.decode(tampered, passphrase) }.isFailure)
        check(runCatching { ArchiveCodec.decode(archive, "wrong-passphrase".toCharArray()) }.isFailure)
        check(runCatching { ArchiveCodec.encode(listOf(synthetic, synthetic), passphrase) }.isFailure)
    }
}

private data class SyntheticBuildFixture(
    val builder: String,
    val records: List<String>,
)
