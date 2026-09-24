package org.aloeil.app

import org.aloeil.app.data.ArchiveCodec
import org.aloeil.app.data.DraftCheckpoint
import org.aloeil.app.data.DraftCodec
import org.aloeil.app.data.BackupState
import org.aloeil.app.data.Eye
import org.aloeil.app.data.Reading
import org.aloeil.app.data.ReadingPayload
import org.aloeil.app.data.ReadingPayloadCodec
import org.aloeil.app.data.Sitting
import org.aloeil.app.data.SittingPayloadCodec
import org.aloeil.app.data.ReadingValue
import org.aloeil.app.data.ReadingValueResult
import org.aloeil.app.data.Reason

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
            value = "12.3",
            revision = 1,
            replicaConfirmedRevision = 0,
        )
        check(synthetic.backupState == BackupState.PENDING)
        check(synthetic.copy(revision = 2, replicaConfirmedRevision = 1).backupState == BackupState.PENDING)
        check(synthetic.copy(replicaConfirmedRevision = 1).backupState == BackupState.CONFIRMED)
        val passphrase = "synthetic-test-only".toCharArray()
        val archive = ArchiveCodec.encode(listOf(synthetic), passphrase)
        check(ArchiveCodec.decode(archive, passphrase) == listOf(synthetic))
        val tampered = archive.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        check(runCatching { ArchiveCodec.decode(tampered, passphrase) }.isFailure)
        check(runCatching { ArchiveCodec.decode(archive, "wrong-passphrase".toCharArray()) }.isFailure)
        check(runCatching { ArchiveCodec.encode(listOf(synthetic, synthetic), passphrase) }.isFailure)

        val draft = DraftCheckpoint(
            sittingId = "synthetic-sitting-1",
            readingId = "synthetic-id-2",
            step = "review",
            eye = Eye.RIGHT,
            input = "12.3",
            focusedControl = "save",
        )
        check(DraftCodec.decode(DraftCodec.encode(draft)) == draft)
        check(ReadingValue.parse("١٢,٣٤") == ReadingValueResult.Valid("12.34"))
        check(ReadingValue.parse("0") == ReadingValueResult.Valid("0"))
        check(ReadingValue.parse("12.3.4") == ReadingValueResult.Invalid(Reason.DECIMAL))
        check(ReadingValue.parse("") == ReadingValueResult.Invalid(Reason.EMPTY))
        check(ReadingValue.parse("12 3") == ReadingValueResult.Invalid(Reason.NUMBER))
        val payload = ReadingPayload(
            synthetic.sittingId, synthetic.recordedAtMillis, synthetic.eye, synthetic.value,
        )
        check(ReadingPayloadCodec.decode(ReadingPayloadCodec.encode(payload)) == payload)
        val sitting = Sitting("synthetic-sitting-1", 1_700_000_000_000L, null)
        check(SittingPayloadCodec.decode(sitting.id, SittingPayloadCodec.encode(sitting)) == sitting)
    }
}

private data class SyntheticBuildFixture(
    val builder: String,
    val records: List<String>,
)
