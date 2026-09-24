# Local data and recovery (P2 in progress)

## On-phone facts

Opening a sitting is transactional and permits at most one open sitting.
Repeating the same sitting ID is idempotent; starting a different sitting
while one is open fails instead of hiding the earlier one.

A successful save commits a reading and a retryable outbox row in one Room
transaction. The phone remains authoritative and saving never waits for the
network. A caller reserves the reading ID before saving; repeating a save
with the same ID and content returns the same row. A conflicting reuse fails.

The encrypted reading payload contains the sitting ID, event time, eye,
exact decimal text or a device range state, original time-zone ID, optional
note, and creation/update audit times. Numeric values are stored as entered
after digit and decimal-separator normalization, without rounding, clinical
thresholds, or diagnosis. Range states are distinct facts and never
converted to guessed numbers. A correction retains its prior encrypted
payload and stable reading ID, increments the revision, and records an
operation ID for retry safety. Undo creates a further revision.

Reading, sitting, and draft payloads use AES-256-GCM with an Android
Keystore-held key. Random IDs, row counts, revision numbers, tombstone IDs,
and retry timing remain visible as SQLite metadata. The file is not wholly
encrypted. The app does not contain analytics, a crash reporter, or its own
network permission. Android Auto Backup is disabled. Explicit backup and
data-extraction rules exclude app files from cloud and device-to-device
transfer, including Android 12 and later. The Android Keystore key cannot
be exported to a new phone; a transferred database without it would be
unreadable. Recovery on a new phone uses the user-chosen encrypted archive
and passphrase instead.

A confirmed delete transaction erases the reading, previous encrypted
versions, correction operations, and pending outbox entries. It retains
only a random reading ID and deletion time. The marker stops an older archive
from silently restoring the reading on that phone. It does not erase copies
in backup files the user previously saved. Import never deletes an existing
phone reading. An incoming deletion marker for an active phone reading is
ignored; unrelated missing records in the same archive can still be restored.

Room schema version 2 adds deletion markers to version 1 with an explicit
migration. A synthetic device test opens a version 1 database, migrates it,
and checks that encrypted rows and correction history survive.

## Portable backups

The user chooses a file through Android's document picker and supplies a
passphrase of at least 12 characters. The archive uses an independently
derived AES-256-GCM key; the Android Keystore key never leaves the phone.
The app authenticates and validates the whole file before a transactional
import. The preview reports reading, sitting, and deletion-marker counts.
An archive write does not mark a reading as having a confirmed replica:
the chosen file provider may move or delete it later. The export screen confirms
that a file write completed, while the reading screen states that no
separate replica has been confirmed.

Archive version 4 contains current readings, sittings, complete prior
revision/operation history, and deletion markers. Earlier version 3, 2,
and 1 archives remain readable. Version 1 has no recoverable correction
history, so its last recorded value becomes the restored baseline at
revision 1. Restore adds missing readings and deletion markers. Existing
readings are never replaced. An incoming deletion marker for an active phone
reading is ignored, while unrelated missing records can still be restored.
An existing phone tombstone prevents an old backup from restoring that ID.
Conflicting reading, sitting, or correction
history aborts the entire restore without changing phone rows. An archive that
would add another open sitting also fails atomically, so an imported sitting
cannot replace the phone's current capture session. A sitting whose archive
readings are all suppressed by local deletion markers is not re-created.
Finish the current sitting or restore on a fresh profile before importing
an archive with a genuinely open sitting.

A backup can be opened on a fresh profile only with both the file and its
passphrase. A user must keep them separately. The file provider may sync
the encrypted file; Aloeil does not upload it automatically. The MVP uses user-chosen encrypted files and no automatic private replica.
This follows the approved local-only default. A future automatic replica
would require its own privacy, destination, and deployment decision.

## Remaining acceptance

CI uses synthetic JVM fixtures and an API 30 emulator for Room transaction,
restart, migration, archive, correction, and deletion tests. A
representative user-device accessibility and recovery drill, residual
metadata privacy acceptance are still needed before P2 can close. An
automatic replica is outside the current MVP. If added later, its
destination/client and exact-revision, unavailable/empty-replica behavior
need separate implementation and tests.
Real health readings are excluded from development and tests.
