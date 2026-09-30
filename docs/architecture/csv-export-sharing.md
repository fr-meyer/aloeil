# Deliberate CSV export and sharing

The CSV file is **unencrypted plain text**. It is a human-readable snapshot
of current readings, not a recovery backup. The preview names its contents,
shows the count and first three values, and lets the user cancel. Saving
opens Android's document picker so the user chooses the destination.
Sharing opens Android's chooser so the user chooses an app or recipient.
Aloeil never automatically sends the CSV and cannot confirm delivery or
revoke a copy made by another app or file provider.

The file is UTF-8 and follows RFC 4180 quoting with CRLF rows. Schema
version 1 has one row per current reading. Column names and order are
stable:

```text
aloeil_csv_version,reading_id,sitting_id,sitting_started_at_utc,sitting_finished_at_utc,recorded_at_utc,recorded_time_zone,eye,reading_kind,value_mmhg,note,revision,created_at_utc,updated_at_utc
```

UTC timestamps use ISO 8601. The recorded IANA time-zone ID is a separate
column. The reading kind is `NUMERIC`, `BELOW_RANGE`, or
`ABOVE_RANGE`; range-state rows have an empty numeric value. Missing
legacy fields are empty cells. Each row includes its sitting start and
finish times. User-controlled text cells receive a leading apostrophe when their first
non-whitespace character could trigger a spreadsheet formula. The original
note remains exact in the encrypted archive.

CSV contains no deleted readings, deletion markers, or earlier correction
versions. Importing this CSV as a recovery source would silently lose undo
history, so Aloeil does not offer CSV import. The existing passphrase-
encrypted archive is the lossless export/preview/import path; its tests
cover fresh-profile restore, duplicate handling, corrupted files, and
correction history.

For chooser sharing, Aloeil writes the CSV into a dedicated app-private
cache directory, passes only a content URI through FileProvider, and grants
temporary read access. Before the first Share action, the preview explains
this plaintext cache, cancellation, cleanup and recipient copies. Canceling
the chooser does not immediately erase the prepared file. The user can clear
it on the export screen after canceling, or after the receiving app has read it.

Each file has a one-hour lease measured using elapsed time within the current
boot. Its persisted JobScheduler cleanup is scheduled for the lease expiry,
with a five-minute requested deadline slack; Android scheduling may delay
execution, so this is not a guaranteed deletion time. Opening the export screen
also removes expired files and ensures remaining files have cleanup jobs.
The provider refuses expired files, including leases invalidated by a reboot,
and attempts to delete them on access; denial does not depend on deletion
succeeding. Explicit clearing removes the local prepared files. Copies held by recipients or document
providers remain outside Aloeil's control. Android app backup and device-to-device
extraction of app files are disabled.

Completed-save feedback retains only its message resource ID across Activity
recreation. Starting a new save clears that old feedback before the picker opens;
canceling the new picker must not restore the previous success message. Reading
snapshots and CSV contents are not placed in Activity saved state.

This feature uses synthetic fixtures in development and CI. Existing baseline
technical evidence is recorded in `docs/validation/p5-evidence.md` and PR 7.
The local follow-up needs its own exact-head tests. Intended-user accessibility,
privacy acceptance and separate pilot consent remain open.
