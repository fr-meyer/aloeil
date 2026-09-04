# Accessibility, localization, and validation

Status: P1 design specification and P2 validation plan. No emulator, screen-reader, Samsung, non-Samsung, or physical-device test has been run in P1.

## Accessibility behavior

### Text and layout

- Use Android system font scaling and Compose `sp` typography without capping the user's font scale. Validate at normal text, at `2.0x`, and at the device's maximum setting when it is larger.
- Text wraps rather than truncates. At large text or narrow width, eye choices and actions reflow into a single vertical column in the same logical order. No essential text, unit, error, or action relies on ellipsis or horizontal scrolling.
- Containers grow with content; do not give text controls fixed heights. Vertical scrolling is allowed, and the focused field plus its error and primary action remain reachable when the keyboard is open.
- Keep the heading, one task, and one primary action visually clear. Selection, error, saved state, and eye identity use text plus shape/icon/state; colour is supplementary only. There are no diagnostic or risk colours.

### Targets and contrast

- Every interactive target is at least `48 x 48 dp`, including Back, eye choices, correction, undo, and finish. Adjacent targets have enough separation to avoid accidental activation at large text.
- Normal text has at least `4.5:1` contrast. Large text, essential icons, focus indicators, control boundaries, and selected/unselected affordances have at least `3:1` contrast against adjacent colours.
- Disabled controls remain legible, state is conveyed in text, and the enabled primary action is not distinguished by colour alone.

### TalkBack semantics and focus

- Each screen exposes one heading. On forward navigation or resume, accessibility focus starts at that heading, then follows visible top-to-bottom order.
- The deterministic order is: heading, restored/status message, task control(s), inline error or help, primary action, then secondary actions. For eye choice, Left eye precedes Right eye in both visual and semantic order even after vertical reflow.
- Eye choices form one radio group. Each announces the localized eye name and selected/not-selected state. The position/icon is a redundant visual cue; its icon is hidden from accessibility when its name would duplicate the text.
- The numeric field exposes a localized label, `mmHg` unit, current text, required state, error state, and concise input hint. Placeholder text is never its only label.
- Buttons use action-specific labels such as Save reading, Save correction, Undo correction, or Finish; avoid ambiguous labels such as OK. Loading state prevents activation and announces Saving without repeatedly announcing on recomposition.
- “Saved on this phone,” save failure, correction saved, undo complete, and recovery failure remain visibly present and are announced once through an appropriate live region. Success may be polite; blocking errors are assertive. Focus moves to the field for a correctable input error and to the error summary for a storage failure.
- Snackbar messages may reinforce a result but are never the only location for saved, error, or undo state. Undo correction remains keyboard, switch-access, and TalkBack reachable without a short timeout.

### Numeric-input recovery

- Request a decimal-capable numeric keyboard, while accepting hardware keyboard, paste, switch access, and voice input. Never depend on the soft keyboard exposing a particular separator key.
- Accept localized digits plus one decimal separator (`.` or `,`) and normalize the separator for storage and locale display. Do not accept grouping separators or silently round, average, or reinterpret the entered value.
- Syntax errors cover empty input, non-numeric input, and multiple decimal separators only. There is no clinical minimum, maximum, warning band, or risk interpretation.
- On error, retain the exact input, display the localized error beside the field, set the semantic error state, announce it once, and place focus on the field with the cursor available for correction. Back and retry never clear the draft.
- A storage error returns to review with the eye and exact input intact. Duplicate submission reports the already-saved state rather than creating another row for the same operation identifier.

## Co-equal localized copy

English, French, and Korean strings below are co-equal product copy keyed by the same stable identifiers; none is a runtime source language for another. Every locale implements every row in this order, preserves the placeholders, and keeps the same non-clinical meaning. `sitting` means a grouping session only: French uses *séance de saisie* and Korean uses *기록 세션*.

| ID | English | Français | 한국어 |
| --- | --- | --- | --- |
| `start_sitting` | Start a sitting | Commencer une séance de saisie | 기록 세션 시작 |
| `resume_sitting` | Resume the sitting | Reprendre la séance de saisie | 기록 세션 계속 |
| `sitting_in_progress` | Sitting in progress | Séance de saisie en cours | 기록 세션 진행 중 |
| `interrupted_title` | Sitting interrupted | Séance de saisie interrompue | 기록이 중단되었습니다 |
| `interrupted_body` | Your saved readings are still on this phone. Continue where you stopped. | Vos mesures enregistrées sont toujours sur ce téléphone. Continuez là où vous en étiez. | 저장한 측정값은 이 휴대전화에 그대로 있습니다. 중단한 단계부터 계속하세요. |
| `choose_eye` | Choose an eye | Choisir un œil | 눈 선택 |
| `left_eye` | Left eye | Œil gauche | 왼쪽 눈 |
| `right_eye` | Right eye | Œil droit | 오른쪽 눈 |
| `eye_selected` | {eye}, selected | {eye}, sélectionné | {eye}, 선택됨 |
| `eye_not_selected` | {eye}, not selected | {eye}, non sélectionné | {eye}, 선택되지 않음 |
| `enter_reading` | Enter the reading | Saisir la mesure | 측정값 입력 |
| `reading_label` | Reading (mmHg) | Mesure (mmHg) | 측정값(mmHg) |
| `reading_hint` | Enter a number in mmHg. | Saisissez un nombre en mmHg. | mmHg 단위 숫자를 입력하세요. |
| `not_saved` | Not saved yet | Pas encore enregistrée | 아직 저장되지 않음 |
| `continue` | Continue | Continuer | 계속 |
| `review_before_save` | Review before saving | Vérifier avant d'enregistrer | 저장 전 확인 |
| `eye_summary` | Eye: {eye} | Œil : {eye} | 눈: {eye} |
| `reading_summary` | Reading: {value} mmHg | Mesure : {value} mmHg | 측정값: {value} mmHg |
| `back` | Back | Retour | 뒤로 |
| `save_reading` | Save reading | Enregistrer la mesure | 측정값 저장 |
| `saving` | Saving… | Enregistrement… | 저장 중… |
| `saved_on_phone` | Saved on this phone | Enregistrée sur ce téléphone | 이 휴대전화에 저장됨 |
| `already_saved` | This reading is already saved on this phone. | Cette mesure est déjà enregistrée sur ce téléphone. | 이 측정값은 이미 이 휴대전화에 저장되었습니다. |
| `add_another` | Add another reading | Ajouter une autre mesure | 측정값 추가 |
| `correct_reading` | Correct this reading | Corriger cette mesure | 이 측정값 수정 |
| `choose_correction` | Choose what to correct | Choisir l'élément à corriger | 수정할 항목 선택 |
| `correct_eye` | Correct the eye | Corriger l'œil | 눈 수정 |
| `correct_value` | Correct the reading | Corriger la mesure | 측정값 수정 |
| `review_correction` | Review the correction | Vérifier la correction | 수정 내용 확인 |
| `previous_eye` | Previous eye: {eye} | Œil précédent : {eye} | 이전 눈: {eye} |
| `corrected_eye` | Corrected eye: {eye} | Œil corrigé : {eye} | 수정한 눈: {eye} |
| `previous_reading` | Previous reading: {value} mmHg | Mesure précédente : {value} mmHg | 이전 측정값: {value} mmHg |
| `corrected_reading` | Corrected reading: {value} mmHg | Mesure corrigée : {value} mmHg | 수정한 측정값: {value} mmHg |
| `save_correction` | Save correction | Enregistrer la correction | 수정 내용 저장 |
| `correction_saving` | Saving correction… | Enregistrement de la correction… | 수정 내용 저장 중… |
| `correction_saved` | Correction saved | Correction enregistrée | 수정 내용이 저장되었습니다 |
| `undo_correction` | Undo correction | Annuler la correction | 수정 실행 취소 |
| `undo_done` | Previous eye and reading restored | Œil et mesure précédents restaurés | 이전 눈과 측정값으로 복원되었습니다 |
| `nothing_to_undo` | There is no correction to undo. | Il n'y a aucune correction à annuler. | 실행 취소할 수정 내용이 없습니다. |
| `finish_sitting` | Finish sitting | Terminer la séance de saisie | 기록 세션 종료 |
| `finish_title` | Finish this sitting? | Terminer cette séance de saisie ? | 이 기록 세션을 종료할까요? |
| `finish_body` | The sitting will close and remain available for review. | La séance sera clôturée et restera disponible pour consultation. | 기록 세션이 종료되며 나중에 다시 확인할 수 있습니다. |
| `keep_recording` | Keep recording | Continuer la saisie | 계속 기록 |
| `finish` | Finish | Terminer | 종료 |
| `sitting_finished` | Sitting finished | Séance de saisie terminée | 기록 세션이 종료되었습니다 |
| `error_empty` | Enter a reading. | Saisissez une mesure. | 측정값을 입력하세요. |
| `error_number` | Enter a number. | Saisissez un nombre. | 숫자로 입력하세요. |
| `error_decimal` | Use only one decimal separator. | Utilisez un seul séparateur décimal. | 소수점 구분 기호는 하나만 입력하세요. |
| `error_save` | The reading could not be saved. Your entry is still here. Try again. | Impossible d'enregistrer la mesure. Votre saisie est toujours affichée. Réessayez. | 측정값을 저장하지 못했습니다. 입력한 내용은 그대로 있습니다. 다시 시도하세요. |
| `error_correction_save` | The correction could not be saved. Your changes are still here. Try again. | Impossible d'enregistrer la correction. Vos modifications sont toujours affichées. Réessayez. | 수정 내용을 저장하지 못했습니다. 변경한 내용은 그대로 있습니다. 다시 시도하세요. |
| `error_draft_restore` | The draft could not be restored. Saved readings are still available. Choose an eye to continue. | Impossible de restaurer le brouillon. Les mesures enregistrées restent disponibles. Choisissez un œil pour continuer. | 입력 중인 내용을 복원하지 못했습니다. 저장된 측정값은 그대로 있습니다. 계속하려면 눈을 선택하세요. |
| `try_again` | Try again | Réessayer | 다시 시도 |

For numeric-format testing, the only example in this specification is explicitly synthetic in every locale: **Synthetic test data: 12.3 mmHg** / **Données de test synthétiques : 12,3 mmHg** / **합성 테스트 데이터: 12.3 mmHg**. It is an input-format fixture only and carries no clinical meaning.

Localization checks must also verify that French apostrophes/accents and Korean text are preserved, values use locale-appropriate decimal display, `mmHg` is not translated, placeholders do not change meaning, and no screen falls back to a mixed locale. Translations may rephrase naturally, but may not add urgency, diagnosis, reassurance about the value, thresholds, treatment, or advice.

## Validation matrix

P1 defines the checks; execution begins with code-bearing P2. “Planned” below is not a claim that a device or test has passed.

| Scenario | Required configurations | Pass criteria | P1 evidence state |
| --- | --- | --- | --- |
| Normal fonts | Each locale at `1.0x`; portrait | Every string and action is visible in canonical order; no overlap or mixed locale. | Specified; not device-tested |
| Large fonts | Each locale at `2.0x` and device maximum; portrait and keyboard open | Text wraps, controls grow/reflow vertically, focus remains visible, and every action is reachable without horizontal scrolling. | Specified; not device-tested |
| Screen reader | TalkBack on representative Samsung One UI and a non-Samsung Android device/emulator; each locale | Heading-first deterministic focus, correct radio states and hints, one announcement per save/error/state change, and correction/undo operable without sight. | Specified; not device-tested |
| Portrait/narrow layout | Representative narrow Android viewport in each locale; normal and large fonts | Left/right identity retains text plus icon/position, no clipping, and visual order equals semantic order. | Specified; not device-tested |
| Interrupted sitting | Activity recreation and process restart from eye choice, value entry, review, and save pending | Committed rows remain; drafts are marked not saved; pending IDs reconcile without duplicates; focus resumes at heading. | Specified; not implementation-tested |
| Numeric recovery | Empty, non-numeric, repeated-separator, paste, and locale-separator inputs using only explicitly labelled synthetic test data | Exact input remains editable, localized error is visible and announced, no threshold language appears, and retry creates at most one row per operation ID. | Specified; not implementation-tested |
| Correction and undo | Correct eye; correct value; fail/retry; invoke undo with TalkBack in each locale | Only the selected reading changes, prior version is restored exactly, repeated facts remain separate, and status is announced once. | Specified; not implementation-tested |
| Locale parity | Run the complete start/resume-to-finish path in French, English, and Korean | Same screens, choices, task order, placeholders, storage meaning, and non-clinical safety boundary in all locales. | Copy inventory completed; not device-tested |

The Android test plan must include Compose semantics tests for labels, roles, state, focus order, live-region text, and minimum target bounds; screenshot/layout checks at normal and large fonts; Room/repository tests for interruption and idempotency; and manual TalkBack passes on at least one Samsung and one non-Samsung configuration. Use mainstream Android APIs only—no Samsung-only behavior or API may be required for success.
