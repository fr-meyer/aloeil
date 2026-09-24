# P5 validation evidence and release gate

Status: in progress. All automated fixtures are synthetic. A passing emulator run is evidence for the tested build only; it is not a representative-device or intended-user acceptance.

## Automated evidence to record at the exact pull-request head

| Area | Test or inspection | Required result |
| --- | --- | --- |
| Capture and integrity | `CaptureFlowDeviceTest`, `ReadingRepositoryDeviceTest` | Start, save, correct, undo, and confirmed delete preserve only the intended current fact; transactions, revisions, tombstones, retries, interruption and restart do not duplicate or resurrect data. |
| Saved history | `HistoryRestartDeviceTest`, history JVM fixtures | A saved and finished reading is selectable after reopening the database; eye/date filters and graph/list transformations retain exact facts. |
| Backup and restore | Repository device tests and synthetic archive fixtures | Fresh-profile restore, v1–v4 compatibility, wrong passphrase, malformed archive, duplicate and deleted IDs, and history preservation follow the documented rules. |
| Export and sharing | Synthetic CSV JVM fixtures and UI inspection | CSV escaping, formula protection, date and eye fields, preview, deliberate save/share, and temporary content access follow the documented rules. |
| Installed privacy | `InstalledPrivacyDeviceTest` | Merged app has no Internet permission and no automatic Android backup; share provider is private and grants only temporary URI access. |
| Build | GitHub Actions API 30 managed emulator and JVM suite | All tests pass on the exact PR head; record run URL and head SHA before claiming a gate. |

The automated suite cannot prove that a particular phone, screen reader, file provider, or caregiver workflow works. Record every failed check with reproduction steps and the corrected exact-head build. Do not store screenshots, logs, archives, or CSV files containing real readings in GitHub or CI.

## Representative-device acceptance checklist

Use labelled synthetic readings on the intended Android phone and at least one non-Samsung configuration. Record the model, Android version, app commit, locale, and whether TalkBack was on. Store only pass/fail notes, without reading values or identifying health data.

1. In English, French and Korean, complete start → eye → numeric or device range state → optional note → review → save → finish; repeat with two eyes and multiple sittings. Confirm no diagnostic, threshold, treatment, or alarm language.
2. At normal, 2× and maximum supported font size, with the keyboard open and a narrow viewport, verify every heading, field, action, error and saved state is visible and reachable. Check the 48 dp targets and contrast requirements in `docs/ux/accessibility-localization-validation.md`.
3. With TalkBack and keyboard or switch access, verify heading-first focus, eye names and selection state, mmHg and error announcements, correction, undo, delete confirmation, history list and chart equivalent, backup and share actions. Count repeated success/error announcements.
4. Force close after entering a draft and after a committed save; reopen. Confirm the draft remains marked unsaved, the saved item remains available through history, and a retry does not duplicate it. Repeat after finishing a sitting.
5. Save an encrypted archive to a user-chosen location. On a fresh app profile, restore with the passphrase and compare every synthetic reading, sitting, revision, note and deletion marker. Try a wrong passphrase and corrupt file; neither may change existing phone data. Keep file and passphrase separately.
6. Export CSV and open the preview before saving or sharing. Inspect the actual destination file and recipient chooser. Confirm that plaintext is only created after the user's action and that old share cache can be cleared. Test cancellation at each step.
7. Inspect the installed app and device settings for network permission, automatic backup, analytics, logs and crash reporting. Check that the encrypted local database still exposes random IDs, row counts, revision numbers and retry timing as metadata. Have the intended user accept that residual metadata explicitly.
8. Rehearse a lost-phone scenario and a rollback to the previous build using synthetic data. Confirm the chosen archive can restore on a fresh profile and document any version incompatibility or recovery limitation.

## P6 private-pilot gate

Before any real reading: the intended user must separately consent to collection on their phone, accept the local storage and residual metadata model, understand where an encrypted backup and passphrase will be kept, and choose whether and with whom to share a CSV. Their own device must pass the checklist above. A small private pilot can then test actual usability; capture feedback without putting readings in repository, CI, public issues, logs or the GCP server. Release is blocked until pilot findings are resolved and a new exact-head validation record passes.
