# Android stack decision

Status: selected for P2. P1 records the decision and contains no application code.

## Decision

Aloeil will be a native, offline-first Android application with this initial stack:

| Concern | Selection | Intended use |
| --- | --- | --- |
| Language and UI | Kotlin and Jetpack Compose | A single Android codebase using Android-native UI, semantics, text scaling, input, and testing APIs. |
| Navigation | Navigation Compose | Explicit routes for the one-job-per-screen capture flow, with only small identifiers in route arguments. Sensitive values stay in local state, not route strings. |
| Screen state | AndroidX Lifecycle `ViewModel` | Own screen state across configuration changes and expose events as explicit functions. |
| Concurrency and observable state | Kotlin coroutines and `StateFlow` | Run storage work off the main thread and expose one immutable `UiState` per screen. Compose collects it with lifecycle awareness. |
| Structured persistence | Room, beginning in P2 | Store sittings, individual readings, correction history, migration metadata, and later outbox records in transactions. Room supplies schema and migration tooling; **Room does not encrypt the database or its fields**. |
| Non-sensitive settings | DataStore | Store only preferences such as completed onboarding or display choices. Readings, drafts, encryption keys, and other sensitive content do not belong in DataStore. |
| Deferred work | WorkManager, when backup/outbox work begins | Retry optional replica work under declared constraints. It is never on the local save path and never decides whether a reading is saved on the phone. |
| Sensitive local data | Android Keystore-backed envelope encryption | Encrypt sensitive payloads with a random data-encryption key and protect that key with a non-exportable key held by `AndroidKeyStore`. Use supported platform cryptography APIs, not the deprecated AndroidX Security Crypto library. |
| Dependency injection | Manual constructor injection | Create dependencies in one application composition root, pass interfaces into repositories and view models, and avoid a DI framework until the dependency graph justifies one. |

The app is offline-first: a save completes only after the local Room transaction succeeds. UI state separately represents local save and any later replica state. Network absence, replica failure, or an empty replica cannot block a save or delete a local fact.

## Architecture boundary

The initial dependency direction is:

`Compose screen -> ViewModel -> use case/repository -> local Room data source`

A later optional replica adds an outbox consumer behind the repository; it does not replace or outrank the local data source. View models expose stable state such as editing, saving, saved-on-phone, or error. One-shot UI effects are derived from acknowledged events so recomposition cannot repeat a save or announcement.

P2 should keep these rules explicit:

- Each sitting and reading has an application-generated stable identifier. Legitimately repeated values remain separate rows; content equality is never a deduplication key.
- A reading save and any local outbox insertion occur in one local transaction. WorkManager may process the outbox only after that transaction commits.
- Corrections target one stable reading identifier and retain enough local version information to undo the correction without changing another reading.
- Database access, encryption, time, and identifier generation sit behind narrow interfaces so migrations, process restart, retry, and failure paths can be tested with synthetic fixtures.

## Encryption boundary

P2 must define a versioned envelope format before storing sensitive fields. The intended design is a random data-encryption key for payload encryption, authenticated encryption through supported Java Cryptography Architecture primitives, and a Keystore-held key-encryption key that protects the data-encryption key. The wrapped key, algorithm/version metadata, and ciphertext may be stored in app-private storage; plaintext keys and sensitive values must not be logged or exported.

Keystore availability and hardware backing vary by Android device, so hardware-backed storage is used when available but is not assumed. Key loss, reinstall, restore, migration, and optional app-lock behavior need explicit recovery tests before a pilot.

This application-layer protection is defense in depth. It does **not** replace Android device encryption, a secure lock screen, OS updates, or Android's application sandbox. Room remains the persistence layer and must not be described as providing encryption itself.

## Why native Android instead of Flutter

The product is Android-first, and its highest-risk interaction is accessibility-sensitive manual capture. Kotlin and Compose give direct access to Android semantics, TalkBack behavior, font scaling, window insets, back navigation, input methods, lifecycle, Accessibility Scanner findings, and Android test tooling without a cross-platform abstraction layer. That makes focus order, state announcements, Samsung/non-Samsung differences, and process restoration easier to inspect in the platform that will actually ship.

Flutter could implement the flow, but there is no current second-platform benefit to offset an additional runtime, a second widget/semantics model, plugin boundaries around Android persistence and Keystore, and another layer when diagnosing vendor-specific accessibility behavior. Flutter can be reconsidered if a supported non-Android product becomes a real requirement; it is not selected for P2.

## Delivery consequence

P1 is design-only. It does not establish that the app builds or that any emulator or physical device has passed accessibility checks.

Code-bearing P2 requires a reproducible Android environment before implementation evidence can be claimed. The P2 bootstrap must record and pin the JDK, Gradle wrapper, Android Gradle Plugin, Kotlin and library versions, Android SDK platform/build tools, and test device or emulator configuration. A clean checkout must be able to run the documented build, unit-test, migration-test, and UI-test commands without relying on an unrecorded local IDE state.
