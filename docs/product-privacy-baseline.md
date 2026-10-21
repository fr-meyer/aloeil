# Product and privacy baseline

Status: approved product baseline. Signed 0.1.0 is installed on the intended phone, with owner-confirmed restoration and phone security cleanup on 2026-09-30. No hosted service, account, or automatic health-data upload is implemented. This operational progress does not close intended-user privacy/comfort acceptance or authorize a private pilot.

## Product

Aloeil is an installed Android journal for manually entered home eye-pressure readings. French, English, and Korean are its initial supported languages. It targets mainstream Android phones without vendor-specific APIs.

A sitting may contain any number of measurements for either eye. Every measurement remains its own recorded fact. History and graphs always distinguish left and right eyes and never add diagnostic zones, risk scores, or treatment guidance.

## Local-first guarantee

- The phone is the authoritative working copy.
- Saving a reading never requires the network.
- An optional encrypted replica may catch up later but cannot become a save gate.
- Replica outage or an empty server must never erase local rows.
- Migrations, export, restore, and conflict handling must have loss-prevention tests.

## Privacy boundary

- Default community installation is local-only.
- No public signup, advertising, third-party analytics, hidden upload, or model training on readings.
- Real readings, exports, device identifiers, credentials, and private deployment details are forbidden in source control and test fixtures.
- Development and automated tests use synthetic values only.
- A private replica must be isolated from OpenClaw production and require a separate deployment approval.
- Reading, sitting and draft payloads are encrypted with an Android Keystore key. The SQLite file is not wholly encrypted: random IDs, row counts, revisions, tombstone IDs and retry timing remain visible metadata.
- The current build has no separate app lock or app authentication. The phone's lock screen protects access; an optional app lock is future scope.
- CSV is deliberately unencrypted. Canceling sharing does not immediately remove its temporary local cache; cleanup is scheduled after one hour and Android may delay it. Explicit clearing affects only Aloeil's local cache, not copies kept by recipients or file providers.
- Keep an encrypted backup and its passphrase separately. Consent to restoration is not consent to a new pilot, replica or CSV recipient.

## Safety boundary

Aloeil records and displays facts. It does not diagnose, interpret clinical risk, prescribe, recommend treatment, define alarm thresholds, or replace professional medical interpretation. Unsupported tonometer USB scraping is out of scope.

## MVP journeys

1. Record several left- and right-eye readings in one sitting while offline.
2. See that a reading is saved on the phone and separately whether backup is confirmed.
3. Review eye-specific history and graphs.
4. Correct a wrong eye or value with undo.
5. Continue working while the replica is unavailable.
6. Restore synthetic test history to prove recovery before any consented pilot.
