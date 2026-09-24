package org.aloeil.app

import java.util.Base64

import org.aloeil.app.data.ArchiveBundle
import org.aloeil.app.data.ArchiveCodec
import org.aloeil.app.data.ArchivedOperation
import org.aloeil.app.data.ArchivedVersion
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
 * All example values below are synthetic test data and carry no clinical meaning.
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
        val sitting = Sitting("synthetic-sitting-1", 1_700_000_000_000L, 1_700_000_001_000L)
        val corrected = synthetic.copy(eye = Eye.RIGHT, value = "12.34", revision = 2)
        val priorPayload = ReadingPayload(
            synthetic.sittingId, synthetic.recordedAtMillis, synthetic.eye, synthetic.value,
        )
        val bundle = ArchiveBundle(
            readings = listOf(corrected),
            sittings = listOf(sitting),
            versions = listOf(ArchivedVersion(corrected.id, 1, priorPayload)),
            operations = listOf(ArchivedOperation("synthetic-operation-1", corrected.id, 2)),
        )
        val archive = ArchiveCodec.encode(bundle, passphrase)
        check(ArchiveCodec.decode(archive, passphrase) == bundle)
        // Fixed synthetic version-1 archive with revision 3 but no prior history.
        val legacyBytes = Base64.getDecoder().decode(
            "QUxPRUlMMDEAAAABAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwAAAFZ3E05J0ZXhSZwwoSVE54VZX9vyU3fYjy980rw13JmTaM9G7KnRbZgZbjOueHujSU1mGsIVixhajy6Dlr6FS/jOTs+ny00KRUE9oOPCU6zxja5rtSUAqQ==",
        )
        val legacyTime = 1_700_000_000_000L
        val legacyBundle = ArchiveBundle(
            readings = listOf(
                Reading("synthetic-v1-1", "synthetic-sitting-v1", legacyTime, Eye.LEFT, "11.2", 1, 0),
            ),
            sittings = listOf(Sitting("synthetic-sitting-v1", legacyTime, legacyTime)),
            versions = emptyList(),
            operations = emptyList(),
        )
        check(ArchiveCodec.decode(legacyBytes, passphrase) == legacyBundle)
        check(runCatching {
            ArchiveCodec.decode(legacyBytes, "wrong-passphrase".toCharArray())
        }.isFailure)
        val tampered = archive.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        check(runCatching { ArchiveCodec.decode(tampered, passphrase) }.isFailure)
        check(runCatching { ArchiveCodec.decode(archive, "wrong-passphrase".toCharArray()) }.isFailure)
        check(runCatching {
            ArchiveCodec.encode(bundle.copy(readings = listOf(corrected, corrected)), passphrase)
        }.isFailure)
        check(runCatching {
            ArchiveCodec.encode(
                bundle.copy(versions = listOf(ArchivedVersion("missing", 1, priorPayload))),
                passphrase,
            )
        }.isFailure)
        check(runCatching {
            ArchiveCodec.encode(bundle.copy(versions = emptyList()), passphrase)
        }.isFailure)
        check(runCatching {
            ArchiveCodec.encode(bundle.copy(operations = emptyList()), passphrase)
        }.isFailure)
        val missingMiddleRevision = bundle.copy(
            readings = listOf(corrected.copy(revision = 3)),
            operations = listOf(
                ArchivedOperation("synthetic-operation-1", corrected.id, 2),
                ArchivedOperation("synthetic-operation-2", corrected.id, 3),
            ),
        )
        check(runCatching { ArchiveCodec.encode(missingMiddleRevision, passphrase) }.isFailure)

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
        check(SittingPayloadCodec.decode(sitting.id, SittingPayloadCodec.encode(sitting)) == sitting)
    }
}

private data class SyntheticBuildFixture(
    val builder: String,
    val records: List<String>,
)
