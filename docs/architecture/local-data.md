# Local data implementation (P2 in progress)

This branch introduces a Room database for manually entered readings. A successful save
commits the reading and a retryable outbox row in one transaction. A reading has a
caller-reserved ID, so a repeated save attempt for that ID returns the existing row instead
of creating a second measurement. The phone remains authoritative; no network call is
made by saving.

The eye and value are encrypted with AES-256-GCM before Room stores them. The key is
non-exportable in Android Keystore. Row IDs, sitting IDs, timestamps, revision numbers,
and backup state are currently database metadata and are **not encrypted**. Full-file
encryption remains open before real data may be used. The app does not yet send an outbox
entry to a replica. The displayed backup state must remain pending until a future replica
acknowledges the exact current revision.

A portable archive uses an independent passphrase-derived AES-256-GCM key. It verifies
the entire archive before importing any rows. Import is additive and transactional:
existing phone rows are kept, and new rows enter the outbox as pending. The archive
format is versioned; no previous released database version exists yet. The Keystore key
is never exported. A user must retain the archive passphrase to restore it.

Protected draft checkpoints and sitting state are stored locally; the draft payload is
encrypted with the same Keystore key. On resume, a pending reading ID can be checked
against the saved rows before the UI claims success.

This is an implementation slice. Corrections with undo, full database encryption,
migration and process-restart tests, UI wiring, and a replica client are still required
to complete P2. Development uses synthetic fixtures only.
