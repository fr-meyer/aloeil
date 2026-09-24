# Local data implementation (P2 in progress)

This branch introduces a Room database for manually entered readings. A successful save
commits the reading and a retryable outbox row in one transaction. A reading has a
caller-reserved ID, so a repeated save attempt for that ID returns the existing row instead
of creating a second measurement. The phone remains authoritative; no network call is
made by saving.

The eye, exact decimal value, sitting association, and event timestamp are encrypted
with AES-256-GCM before Room stores them. Sitting start/finish times and draft
content are encrypted too. Localized digits and either decimal separator normalize
to exact decimal text without rounding or a clinical range check. Random row IDs,
revision counters, and retry scheduling remain visible as database metadata; the
SQLite file itself is not wholly encrypted. Full-file encryption would require a
separately approved dependency or a different storage design.

A portable archive uses an independent passphrase-derived AES-256-GCM key. It verifies
the entire archive before importing any rows. Import is additive and transactional:
existing phone rows are kept, and new rows enter the outbox as pending. The archive
format is versioned; no previous released database version exists yet. The Keystore key
is never exported. A user must retain the archive passphrase to restore it.

Protected draft checkpoints and sitting state are stored locally; the draft payload is
encrypted with the same Keystore key. On resume, a pending reading ID can be checked
against the saved rows before the UI claims success.

Corrections create a new reading revision and keep the previous encrypted payload
for persistent undo. An operation ID prevents retries from applying a correction twice.
A stale expected revision is rejected. The portable archive currently contains only the
latest reading revision, so correction and undo history is not yet portable.

This is an implementation slice. Portable correction/sitting history, the remaining metadata privacy decision,
migration and process-restart tests, UI wiring, and a replica client are still required
to complete P2. Development uses synthetic fixtures only.
