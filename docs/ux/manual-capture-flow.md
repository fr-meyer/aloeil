# Accessible manual capture flow

Status: P1 interaction specification. No application or device validation is claimed.

## Model and invariants

A **sitting** is a user-created group of readings entered together. It has no diagnostic meaning. A **reading** is one saved value associated with exactly one sitting and one eye.

- Every successfully completed intentional save cycle creates one reading with its own stable identifier.
- Repeated values for the same eye remain separate facts, even when their content is identical. The capture flow never averages, merges, replaces, ranks, colours, or interprets them.
- Eye identity is always written as **Left eye** or **Right eye** and paired with a position/icon cue. Colour may reinforce selection but is never the only cue.
- The only unit shown for a reading is `mmHg`. The UI performs syntax validation, not clinical threshold or risk validation.
- Unsaved input is labelled as not saved. “Saved on this phone” appears only after the local transaction succeeds; optional backup state is separate and cannot gate capture.
- All prototypes, fixtures, screenshots, and tests use synthetic test data only. Any displayed example measurement must include a visible “synthetic test data” label.

## One job per screen

Each screen has one primary job and one primary action. Back, help, correction, undo, and finish are secondary actions and stay reachable without competing with the primary action.

1. **Start or resume.** With no open sitting, the primary action starts one. With an open sitting, the screen states that the sitting was interrupted and the primary action resumes it. Starting never creates a reading.
2. **Choose an eye.** Present two large radio-style choices: Left eye on the left with a left-position icon, and Right eye on the right with a right-position icon. Each choice exposes its text and selected state to TalkBack. Continue is unavailable until one is selected.
3. **Enter a value.** Show one labelled numeric field with `mmHg` as visible unit text. Continue performs syntactic validation and preserves the entry on error. It does not decide whether the value is healthy, expected, or urgent.
4. **Review before saving.** Show the selected eye and exact entered value. Back returns to the value screen without clearing input. Save is the only primary action.
5. **Confirm local save.** Show “Saved on this phone” only after durable local success. The primary action adds another reading and returns to eye choice. Secondary actions offer correction of this reading and finishing the sitting.
6. **Choose a correction.** For the selected saved reading, choose exactly one field to change: eye or value. The editor then reuses the eye-choice or value-entry screen with a correction heading.
7. **Review and save the correction.** Show the prior and proposed field values with text labels, then save only that change. The confirmation offers Undo correction as a persistent action, not only a timed snackbar.
8. **Confirm finish.** Explain that the sitting will close and remain available for later review. Keep recording returns to the last saved confirmation. Finish closes the sitting idempotently and shows a finished state.

The next-reading loop is therefore: saved confirmation -> add another -> choose eye -> enter value -> review -> local save -> saved confirmation. Eye choice resets for every new reading so a previous eye cannot be carried forward accidentally.

## State transitions

| State | Persisted fact | Event | Next state |
| --- | --- | --- | --- |
| No open sitting | None | Start sitting | Open sitting / choose eye |
| Open sitting / choose eye | Sitting exists; no new reading | Select left or right, then Continue | Value entry |
| Value entry | Eye selection and protected draft checkpoint; no reading yet | Valid Continue | Review, marked not saved |
| Value entry | Draft unchanged | Empty or malformed Continue | Same screen with inline error and focus on the field |
| Review | Draft only | Back | Value entry with exact input preserved |
| Review | Draft only | Save | Save pending |
| Save pending | Stable reading identifier reserved | Local transaction succeeds | Saved confirmation |
| Save pending | No committed reading for the operation | Local transaction fails | Review with exact input preserved and retry available |
| Saved confirmation | One distinct reading committed | Add another | Choose eye with no selection |
| Saved confirmation | Reading committed | Correct this reading | Choose correction field |
| Correction editor/review | Original reading remains active | Save correction | Correction pending |
| Correction pending | Stable reading identifier and expected version | Local update succeeds | Correction saved with Undo correction available |
| Correction pending | Original reading unchanged | Local update fails | Correction review with proposed input preserved |
| Correction saved | Corrected version active; prior version retained for undo | Undo correction | Prior eye and value restored; undo confirmed |
| Saved confirmation or correction confirmation | Open sitting with saved readings | Finish sitting | Finish confirmation |
| Finish confirmation | Sitting still open | Keep recording | Last saved confirmation |
| Finish confirmation | Sitting still open | Finish | Sitting finished |

A correction is not a new repeated measurement. It changes only the chosen field of one reading identified on the review screen, retains the same reading identifier, and records the prior version for undo. Undo restores that exact prior eye and value; it does not delete, edit, or combine any other reading. If there is no completed correction to undo, the UI says so and changes nothing.

## Interruption and resume

The flow checkpoint comprises the open sitting identifier, current step, selected eye, protected draft input if present, pending operation identifier, and the last focused logical control. P2 must keep sensitive draft content in the protected local-data boundary, not in non-sensitive settings or navigation arguments.

On activity recreation, process restart, or return from another app:

- Reconcile any pending operation by its stable identifier. If the Room transaction exists, show saved confirmation; otherwise restore review with the draft marked not saved. Never create a second reading automatically.
- Resume the last incomplete job. Announce the screen heading first, followed by the restored state. Do not move focus straight into the numeric keyboard without a user action.
- Preserve committed readings even if an uncommitted draft cannot be restored. Explain the recovery error and return to eye choice; do not imply that the missing draft was saved.
- A finished sitting opens in read-only history, not back in capture. Starting again creates a new sitting identifier.

## Duplicate-tap and concurrency protection

Disabling Save while it is pending provides immediate feedback but is not the integrity mechanism. Each save attempt uses one preallocated operation/reading identifier, and Room enforces uniqueness in the same transaction that writes the reading. Recomposition, double taps, retry after process death, and two collectors handling the same event therefore resolve to one result for that identifier.

Every deliberate Add another cycle allocates a new identifier, even when eye and value match an earlier reading. Corrections similarly carry an operation identifier plus the expected reading version. Repeating the same correction event is idempotent; a conflicting version returns to review instead of overwriting silently. Finish is also idempotent: repeated requests leave the same sitting closed.

## Safety language

Capture copy reports actions and storage state only. It contains no clinical thresholds, diagnosis, risk colours, treatment language, recommendations, alarms, or advice. Localization and accessibility copy is canonical in [accessibility-localization-validation.md](accessibility-localization-validation.md).
