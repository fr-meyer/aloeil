package org.aloeil.app

import java.util.Base64
import java.time.Instant
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

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
import org.aloeil.app.data.RangeState
import org.aloeil.app.data.restoredDraftStep
import org.aloeil.app.data.filterHistory

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
        check(runCatching {
            ArchiveCodec.encode(bundle, "short".toCharArray())
        }.isFailure)
        check(runCatching {
            ArchiveCodec.encode(bundle, "x".repeat(ArchiveCodec.MAX_PASSPHRASE_LENGTH + 1).toCharArray())
        }.isFailure)
        val longIdSuffix = "x".repeat(59_980)
        val oversized = ArchiveBundle(
            readings = emptyList(),
            sittings = List(280) { index ->
                Sitting("synthetic-$index-$longIdSuffix", 1_700_000_000_000L, null)
            },
            versions = emptyList(),
            operations = emptyList(),
        )
        val overflow = runCatching { ArchiveCodec.encode(oversized, passphrase) }.exceptionOrNull()
        check(overflow is IllegalArgumentException && overflow.message == "Archive is too large")
        val archive = ArchiveCodec.encode(bundle, passphrase)
        check(ArchiveCodec.decode(archive, passphrase) == bundle)
        // Fixed synthetic v2 archive preserves the prior revision and operation ID.
        val version2Bytes = Base64.getDecoder().decode(
            "QUxPRUlMMDIAAAACAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwAAAPl3E05J0ZXhSZwwoSVE54VZQI7yU3ffjy980rw13JmTaM9G7KnRbZgZKQKuefHnY8AOGsdDjhpUjy6Clr2FSszOTs+ny00JVKriL3Er1pqtwnzCQfPRGQwHvU6tzxakKEX9ckrcvZjpgoV2gQInsQkBpAZOPsnroLH32UtSoR3JE428AqUE1UhqLJXkSRGBWf6G/vpxpOg5LmklVvB2b1ZtLfZJVY0J3LWYOTwp7PIo81G9jLg2YmSOIOPj4QxFHS3YgABLjzjRu3LL32tlDpvw2INYRKAKTUvhdol7zfibds6nTMLHql08h2mrBbMf9VNp+GNRb50uv4g=",
        )
        check(ArchiveCodec.decode(version2Bytes, passphrase) == bundle)
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

        val range = Reading(
            id = "synthetic-range-1",
            sittingId = sitting.id,
            recordedAtMillis = legacyTime + 10,
            eye = Eye.RIGHT,
            value = "",
            revision = 1,
            replicaConfirmedRevision = 0,
            rangeState = RangeState.BELOW_RANGE,
            timeZoneId = "Asia/Seoul",
            note = "Synthetic device state",
            createdAtMillis = legacyTime + 10,
            updatedAtMillis = legacyTime + 10,
        )
        val rangeBundle = ArchiveBundle(listOf(range), listOf(sitting), emptyList(), emptyList())
        check(ArchiveCodec.decode(ArchiveCodec.encode(rangeBundle, passphrase), passphrase) == rangeBundle)
        val rangePayload = ReadingPayload(
            range.sittingId, range.recordedAtMillis, range.eye, range.value,
            range.rangeState, range.timeZoneId, range.note,
            range.createdAtMillis, range.updatedAtMillis,
        )
        check(ReadingPayloadCodec.decode(ReadingPayloadCodec.encode(rangePayload)) == rangePayload)
        check(runCatching {
            ArchiveCodec.encode(rangeBundle.copy(
                readings = listOf(range.copy(value = "12.3")),
            ), passphrase)
        }.isFailure)
        check(runCatching {
            ArchiveCodec.encode(rangeBundle.copy(
                readings = listOf(range.copy(timeZoneId = "Invalid/Imaginary")),
            ), passphrase)
        }.isFailure)

        val draft = DraftCheckpoint(
            sittingId = "synthetic-sitting-1",
            readingId = "synthetic-id-2",
            step = "review",
            eye = Eye.RIGHT,
            input = "12.3",
            focusedControl = "save",
        )
        check(DraftCodec.decode(DraftCodec.encode(draft)) == draft)
        check(runCatching {
            DraftCodec.encode(draft.copy(note = "n".repeat(1001)))
        }.isFailure)
        check(runCatching {
            DraftCodec.encode(draft.copy(input = "1".repeat(ReadingValue.MAX_LENGTH + 1)))
        }.isFailure)
        val rangeDraft = draft.copy(
            step = "NOTE", input = "", rangeState = RangeState.ABOVE_RANGE,
            note = "Synthetic note",
        )
        check(DraftCodec.decode(DraftCodec.encode(rangeDraft)) == rangeDraft)
        check(restoredDraftStep(rangeDraft, synthetic) == "SAVED")
        val priorDraft = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(2)
                out.writeUTF(draft.sittingId)
                out.writeUTF(draft.readingId)
                out.writeUTF("CORRECT_CHOICE")
                out.writeUTF(Eye.RIGHT.name)
                out.writeUTF(draft.input)
                out.writeUTF(draft.focusedControl)
                out.writeLong(1)
            }
        }.toByteArray()
        check(DraftCodec.decode(priorDraft) == draft.copy(step = "CORRECT_CHOICE", baseRevision = 1))
        val oversizedDraft = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(2)
                out.writeUTF(draft.sittingId)
                out.writeUTF(draft.readingId)
                out.writeUTF(draft.step)
                out.writeUTF(Eye.RIGHT.name)
                out.writeUTF("1".repeat(ReadingValue.MAX_LENGTH + 1))
                out.writeUTF(draft.focusedControl)
                out.writeLong(1)
            }
        }.toByteArray()
        check(runCatching { DraftCodec.decode(oversizedDraft) }.isFailure)
        val correctionDraft = draft.copy(step = "CORRECT_CHOICE", baseRevision = 1)
        check(DraftCodec.decode(DraftCodec.encode(correctionDraft)) == correctionDraft)
        check(restoredDraftStep(correctionDraft, synthetic) == "CORRECT_CHOICE")
        // Back from correction persists a terminal checkpoint before showing the saved screen.
        val abandoned = correctionDraft.copy(step = "SAVED")
        check(restoredDraftStep(DraftCodec.decode(DraftCodec.encode(abandoned)), synthetic) == "SAVED")
        // If the correction committed before the process stopped, stale active UI is skipped.
        check(restoredDraftStep(correctionDraft, synthetic.copy(revision = 2)) == "CORRECT_SAVED")
        check(restoredDraftStep(draft.copy(step = "REVIEW"), synthetic) == "SAVED")
        val historyDraft = correctionDraft.copy(fromHistory = true, note = "Synthetic history note")
        check(DraftCodec.decode(DraftCodec.encode(historyDraft)) == historyDraft)
        val boundary = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()
        val west = synthetic.copy(
            id = "synthetic-west", recordedAtMillis = boundary, timeZoneId = "America/Los_Angeles",
        )
        val east = synthetic.copy(
            id = "synthetic-east", recordedAtMillis = boundary, eye = Eye.RIGHT,
            timeZoneId = "Asia/Seoul",
        )
        val duplicate = west.copy(id = "synthetic-west-duplicate")
        val history = listOf(west, east, duplicate)
        val oldDay = filterHistory(history, null, "2025-12-31", "2025-12-31")
        check(oldDay.readings.map { it.id }.toSet() == setOf(west.id, duplicate.id))
        val newDay = filterHistory(history, Eye.RIGHT, "2026-01-01", "2026-01-01")
        check(newDay.readings == listOf(east))
        check(filterHistory(history, null, "bad", "").invalidDate)
        val laterRight = east.copy(id = "synthetic-right-later", recordedAtMillis = boundary + 1000)
        val invalidWithEye = filterHistory(listOf(east, west, laterRight), Eye.RIGHT, "bad", "")
        check(invalidWithEye.invalidDate)
        check(invalidWithEye.readings.map { it.id } == listOf(laterRight.id, east.id))
        check(filterHistory(history, null, "2026-01-02", "2026-01-01").invalidDate)
        check(filterHistory(history, null, "", "").readings.size == 3)
        check(ReadingValue.parse("١٢,٣٤") == ReadingValueResult.Valid("12.34"))
        check(ReadingValue.parse("0") == ReadingValueResult.Valid("0"))
        check(ReadingValue.parse("12.3.4") == ReadingValueResult.Invalid(Reason.DECIMAL))
        check(ReadingValue.parse("") == ReadingValueResult.Invalid(Reason.EMPTY))
        check(ReadingValue.parse("12 3") == ReadingValueResult.Invalid(Reason.NUMBER))
        val maxReading = "1".repeat(ReadingValue.MAX_LENGTH)
        check(ReadingValue.parse(maxReading) == ReadingValueResult.Valid(maxReading))
        check(ReadingValue.parse(maxReading + "1") ==
            ReadingValueResult.Invalid(Reason.LENGTH))
        check(ReadingValue.parse("١".repeat(ReadingValue.MAX_LENGTH + 1)) ==
            ReadingValueResult.Invalid(Reason.LENGTH))
        val payload = ReadingPayload(
            synthetic.sittingId, synthetic.recordedAtMillis, synthetic.eye, synthetic.value,
        )
        check(ReadingPayloadCodec.decode(ReadingPayloadCodec.encode(payload)) == payload)
        check(SittingPayloadCodec.decode(sitting.id, SittingPayloadCodec.encode(sitting)) == sitting)
        val reversedSitting = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(1)
                out.writeLong(sitting.startedAtMillis)
                out.writeBoolean(true)
                out.writeLong(sitting.startedAtMillis - 1)
            }
        }.toByteArray()
        check(runCatching { SittingPayloadCodec.decode(sitting.id, reversedSitting) }.isFailure)
    }
}

private data class SyntheticBuildFixture(
    val builder: String,
    val records: List<String>,
)
