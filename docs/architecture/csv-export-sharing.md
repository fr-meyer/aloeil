# Deliberate CSV export and sharing (P4 in progress)

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

\`\`\`text
aloeil_csv_version,reading_id,sitting_id,sitting_started_at_utc,sitting_finished_at_utc,recorded_at_utc,recorded_time_zone,eye,reading_kind,value_mmhg,note,revision,created_at_utc,updated_at_utc
\`\`\`

UTC timestamps use ISO 8601. The recorded IANA time-zone ID is a separate
column. The reading kind is \`NUMERIC\`, \`BELOW_RANGE\`, or
\`ABOVE_RANGE\`; range-state rows have an empty numeric value. Missing
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
temporary read access. The user can clear these prepared files after the
receiving app has read them. Files older than 24 hours are removed the
next time the export screen opens. Copies held by recipients or document
providers remain outside Aloeil's control. Android app backup is disabled.

This feature uses synthetic fixtures in development and CI. P5 must still
inspect the merged manifest, cache behavior, chooser cancellation,
accessibility, and real-device interaction before a consented pilot.
