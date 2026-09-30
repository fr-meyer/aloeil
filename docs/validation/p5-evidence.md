# P5 validation evidence and release gate

Status: in progress. All automated fixtures are synthetic. A passing emulator run is evidence for the tested build only; it is not a representative-device or intended-user acceptance.

## Current operational status — 2026-09-30

PR 7 baseline is `4d076cbae4a1d95d81f2ee5127739d685821b12a`.
The CSV follow-up in [PR 8](https://github.com/fr-meyer/aloeil/pull/8) is
`70ec1ce1859d606359a0d2306f1b0f70dd405d0d`, a 12-file delta over that baseline.
[PR 9](https://github.com/fr-meyer/aloeil/pull/9) is the combined user-authored
candidate to `dev`, on `aloeil/p5-csv-usability`. It retains the original commit
history, while PRs 7 and 8 remain intact as source evidence. Its initial published
application tree is the same as the tested CSV follow-up; publication metadata
and each later head require their own CI and exact-base/head review evidence.

Signed 0.1.0 was installed on the intended phone on 2026-09-28. On
2026-09-30 the owner confirmed restoration of the original encrypted archive,
then confirmed phone security cleanup complete. The temporary USB keep-awake
setting was independently restored to its recorded original value and read back.
No readings, passphrase, archive contents or device identifier are recorded here.

Earlier PR 7 preparation notes saying “no signed install” describe an earlier
point in that session; this record supersedes that operational status.
The source PRs have not been edited. Owner-reported recovery is not an independent
comparison of every historical record and does not certify a new patch build.
The already-confirmed phone restoration and cleanup need no repeat phone session.

The CSV follow-up corrects the one-hour cache documentation, explains the
cache before first sharing, preserves non-sensitive save feedback through Activity
recreation, and adds synthetic review/transfer coverage at 2× EN/FR/KO.
History date/load errors now expose assertive live-region semantics. These
semantics do not validate physical TalkBack gestures, pronunciation, or spoken
announcement cadence. The follow-up has its own successful automated evidence
below; those results do not certify physical-device or intended-user acceptance.

Local checks for this follow-up: `git diff --check` passed; Python XML parsing
passed for all nine resource/manifest XML files; EN/FR/KO resource IDs and
positional-format placeholders matched (with the intentional `app_name`
fallback), and all 23 string references in the new test exist.

[Android CI run 36673921277](https://github.com/fr-meyer/aloeil/actions/runs/36673921277),
attempt 1, passed for PR 8 head `70ec1ce1859d606359a0d2306f1b0f70dd405d0d`.
The actual `refs/pull/8/merge` checkout was
`98f225fce0aba4d8a5edbc01e1330b4f68bd379e`, whose tree
`dd6a3857c900544df293487a4c0976970b793278` equals that head's tree.
Kotlin compilation and the synthetic JVM task passed; the API-30 console recorded
71 tests starting and finishing, and the workflow completed successfully. No
individual JUnit report was uploaded, so this count comes from the console and
workflow result. The workflow also uploaded the matching synthetic debug APKs
and checksums. No artifact was downloaded or installed on the owner's phone.

Android tests did not run locally: this Mac has JDK 8, no Android SDK and no Gradle
distribution/dependency cache. The unchanged GitHub workflow supplies JDK 17,
Gradle 8.11.1, SDK/build-tools 35 and the API-30 emulator. No local toolchain was
downloaded. For every new candidate head, record the new workflow run and actual
merge checkout; earlier green results are evidence for their recorded head only.
The existing CI command is:

```sh
./gradlew --no-daemon :app:testSyntheticDebugUnitTest :app:pixel2api30DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

## Remaining intended-user acceptance — no health values required

Record only pass/fail and wording or navigation feedback; do not collect readings,
archives, passphrases or screenshots of personal history.

1. Confirm comfortable eye selection, device-range wording, entry/correction and
   History/filter/detail navigation with the intended tonometer workflow.
2. On a separate synthetic test profile, check readable/reachable controls at large
   text, including History, archive and CSV screens. If TalkBack is used, assess
   physical gestures, pronunciation and announcement cadence separately from
   automated semantic checks.
3. Accept the storage boundary: payloads are encrypted, but SQLite IDs, counts,
   revisions, tombstone IDs and retry timing remain visible metadata. The current
   build relies on the phone lock; a separate app lock is future scope.
4. Confirm the chosen encrypted-backup location and separate passphrase custody,
   without disclosing either secret or file contents. Recovery is already
   owner-confirmed; another restore is not required for this checklist.
5. Understand that CSV is plaintext, chooser cancellation does not erase its cache
   immediately, scheduled cleanup can be delayed, and local clearing cannot revoke
   recipient/provider copies. Sharing remains a deliberate choice.
6. Obtain separate consent before a new private pilot, replica or new CSV recipient.
   Resolve pilot feedback before merge/release; restoration alone is not that consent.

## Automated evidence to record at the exact pull-request head

| Area | Test or inspection | Required result |
| --- | --- | --- |
| Capture and integrity | `CaptureFlowDeviceTest`, `ReadingRepositoryDeviceTest` | Start, save, correct, undo, and confirmed delete preserve only the intended current fact; transactions, revisions, tombstones, retries, interruption and restart do not duplicate or resurrect data. |
| Keyboard navigation | `KeyboardNavigationDeviceTest` | Tab and Shift+Tab reach adjacent controls without changing numeric, note or passphrase input; verify the actual device at its maximum font setting. |
| Saved history | `HistoryRestartDeviceTest`, history JVM fixtures | A saved and finished reading is selectable after reopening the database; eye/date filters and graph/list transformations retain exact facts. |
| Backup and restore | Repository device tests and synthetic archive fixtures | Fresh-profile restore, v1–v4 compatibility, wrong passphrase, malformed archive, duplicate and deleted IDs, and history preservation follow the documented rules. |
| Export and sharing | Synthetic CSV JVM fixtures, `CsvSaveCancellationDeviceTest`, `ReviewTransferLocaleDeviceTest` and UI inspection | CSV escaping, formula protection, date/eye fields, disclosure before first share, synthetic cancel/clear states, completed-save recreation and subsequent cancellation follow the documented rules. |
| Review/transfer accessibility | `ReviewTransferLocaleDeviceTest` | At 2× EN/FR/KO on a narrow viewport: History filters/detail, archive password/error and CSV actions are reachable; targets meet 48 dp; errors/results expose appropriate live regions. This does not certify speech or physical gestures. |
| Installed privacy | `InstalledPrivacyDeviceTest` | Merged app has no Internet permission and no automatic Android backup; share provider is private and grants only temporary URI access. |
| Build | GitHub Actions API 30 managed emulator and JVM suite | All tests pass on the exact PR head; record run URL and head SHA before claiming a gate. |

The automated suite cannot prove that a particular phone, screen reader, file provider, or caregiver workflow works. Record every failed check with reproduction steps and the corrected exact-head build. Do not store screenshots, logs, archives, or CSV files containing real readings in GitHub or CI.

## Synthetic device build handoff

For the representative-device checks, download `aloeil-synthetic-validation-debug` from the successful GitHub Actions CI run for the exact PR head under test. The artifact contains the debug APK, its matching instrumentation-test APK, and SHA-256 checksums and expires after 14 days. Compare the APK checksum before installing it on the test device, and record the run URL, commit SHA, device model, Android version and locale in the acceptance notes. The debug APK is a test build, not a release build.

The matching instrumentation APK can run the synthetic suite on an authorized USB-connected phone. Run it only on a fresh test installation: recovery fixtures reset Aloeil app data. Remove the test APK afterward so its synthetic picker/provider cannot affect normal file selection. Passing instrumentation still does not prove TalkBack speech, an actual file provider, or intended-user acceptance.

Use labelled synthetic readings throughout the checklist. Keep pass/fail notes and reproduction steps, without reading values or personal health data. A new commit requires a new run and a new device acceptance record.

## Representative-device acceptance checklist

Use labelled synthetic readings on the intended Android phone and at least one non-Samsung configuration. Record the model, Android version, app commit, locale, and whether TalkBack was on. Store only pass/fail notes, without reading values or identifying health data.

1. In English, French and Korean, complete start → eye → numeric or device range state → optional note → review → save → finish; repeat with two eyes and multiple sittings. Confirm no diagnostic, threshold, treatment, or alarm language.
2. At normal, 2× and maximum supported font size, with the keyboard open and a narrow viewport, verify every heading, field, action, error and saved state is visible and reachable. Check the 48 dp targets and contrast requirements in `docs/ux/accessibility-localization-validation.md`.
3. With TalkBack and keyboard or switch access, verify heading-first focus, eye names and selection state, mmHg and error announcements, correction, undo, delete confirmation, history list and chart equivalent, backup and share actions. Count repeated success/error announcements.
4. Force close after entering a draft and after a committed save; reopen. Confirm the draft remains marked unsaved, the saved item remains available through history, and a retry does not duplicate it. Repeat after finishing a sitting.
5. Save an encrypted archive to a user-chosen location. Recreate the Activity during the save, confirm the write completes, and inspect the resulting file; discard any file left incomplete by a force-stop. On a fresh app profile, restore with the passphrase, recreate the Activity during restore, and compare every synthetic reading, sitting, revision, note and deletion marker without duplicates. Try a wrong passphrase and corrupt file; neither may change existing phone data. Keep file and passphrase separately.
6. Export CSV and open the preview before saving or sharing. Inspect the actual destination file and recipient chooser. Confirm that plaintext is only created after the user's action and that old share cache can be cleared. Test cancellation at each step.
7. Inspect the installed app and device settings for network permission, automatic backup, analytics, logs and crash reporting. Check that the encrypted local database still exposes random IDs, row counts, revision numbers and retry timing as metadata. Have the intended user accept that residual metadata explicitly.
8. Rehearse a lost-phone scenario and a rollback to the previous build using synthetic data. Confirm the chosen archive can restore on a fresh profile and document any version incompatibility or recovery limitation.

## P6 private-pilot gate

Before any real reading: the intended user must separately consent to collection on their phone, accept the local storage and residual metadata model, understand where an encrypted backup and passphrase will be kept, and choose whether and with whom to share a CSV. Their own device must pass the checklist above. A small private pilot can then test actual usability; capture feedback without putting readings in repository, CI, public issues, logs or the GCP server. Release is blocked until pilot findings are resolved and a new exact-head validation record passes.

Save start race: CSV, encrypted export and restore consult the process-owned StateFlow value before interpreting Idle as process interruption. The held CSV provider regression asserts that saving remains disabled and no interruption message appears before and after Activity recreation.

CSV cleanup cancellation: JobScheduler stop cancels the owned coroutine, cleanup checks cancellation between deletions, and completion is gated by the active job identity on Main. A stopped-cleanup regression leaves the remaining files for retry and verifies repeated cleanup is harmless.

Abandoned correction regression: changing eye, going Back, then correcting a numeric value preserves the saved eye. Each newly chosen correction resets fields from the committed reading; numeric correction also uses the committed eye. Real Galaxy Tab and Shift+Tab passed at Samsung maximum 2.0x; Compose key tests explicitly establish keyboard input mode before direct key injection.
