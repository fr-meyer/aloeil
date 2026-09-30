# GitHub Actions CI builder

Status: Android builder with synthetic JVM/API-30 tests and release-validation preparation. Each workflow change needs a successful run at its own exact candidate head before it becomes validation evidence.

GitHub Actions CI is Aloeil's builder. The OpenClaw VM is not a builder, a GCP extra disk is not a builder, and there is no 24/7 builder VM. A clean GitHub-hosted runner obtains the declared JDK and Android SDK, then uses the repository's Gradle wrapper.

Pull requests run `:app:testSyntheticDebugUnitTest` and the managed API-30
`pixel2api30DebugAndroidTest` suite. Fixtures use synthetic data. CI creates its
own emulator; it never connects to the owner's phone. The workflow also builds
the debug app and instrumentation APKs for a separately authorized fresh-profile
validation session. Those tests can reset app data and must not be run against
the restored owner installation.

The release-validation step runs `:app:assembleRelease` and `:app:lintRelease`.
It compiles the release variant, whose source/manifest inputs differ from debug,
and runs release-specific lint. No release signing configuration or private key
is supplied. This unsigned build check does not produce an installable signed
release, prove a signing identity, or authorize distribution. Existing test gates,
the 45-minute job budget and lint's default failure behavior remain in force.

Room exports compiler-generated JSON to `app/schemas`. CI 195 at `277845d`
passed the test and release checks, but retained only debug APK/checksum files;
its KSP log entries do not prove that schema JSON survived the discarded runner.
The schema-retention addition removes only the current generated `3.json`, then
forces `:app:kspReleaseKotlin` with Gradle build-cache reuse and KSP incremental
processing disabled. It copies the resulting bytes without editing them into
`aloeil-room-schema-v3`, alongside a SHA-256 checksum and run/attempt, actual
checkout/tree, PR head/base, generation-command and source-input provenance.
The three explicit artifact paths expire after 14 days. The unchanged tests,
unsigned release checks and debug artifact upload still run; no release APK is
uploaded. The added KSP pass reruns its dependencies within the same 45-minute
budget. CI 196 at `51192fa` passed all existing gates and completed the extra KSP
pass in 20 seconds; the full job took 9m53s. Its verified v3 compiler JSON is
retained unchanged at `app/schemas/org.aloeil.app.data.ReadingDatabase/3.json`,
with the exact artifact-origin record in
`docs/validation/room-schema-v3-provenance.json`. The seven tables, columns,
primary keys and outbox index match the unchanged source; checksum/source hashes
and the actual merge checkout/tree were verified. This is schema structure only,
with no reading rows. The newly retained baseline still needs its own candidate
CI and complete named review; versions 1 and 2 were not reconstructed. The
[schema retention handoff](../validation/p5-evidence.md#release-validation-follow-ups)
records the remaining evidence work without inventing a generated baseline.

## Pinned build inputs

| Input | Pin |
| --- | --- |
| JDK | Eclipse Temurin 17 LTS via `actions/setup-java` |
| Gradle | 8.11.1 via `gradle/wrapper/gradle-wrapper.properties` |
| Gradle binary SHA-256 | `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` |
| Android Gradle Plugin | 8.10.0 |
| Kotlin and Compose compiler plugin | 2.2.10 |
| Android compile/target SDK | 35 |
| Android build tools | 35.0.0 |

`gradle/wrapper/gradle-wrapper.jar` is the official Gradle 8.11.1 wrapper binary. Its published SHA-256 is `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`. `gradle/actions/setup-gradle` validates wrapper JARs and provides the Gradle dependency cache in CI.

The workflow has read-only repository contents permission. It defines no secrets,
does not request write permission for `GITHUB_TOKEN`, and performs no release,
deployment or phone installation. Its debug APK uploads are CI evidence.

Android's [command-line build guide](https://developer.android.com/build/building-cmdline)
documents variant-specific assembly and separate release signing. Its
[lint guide](https://developer.android.com/studio/write/lint#commandline)
documents `lintRelease`; lint must be invoked explicitly to validate that variant.
