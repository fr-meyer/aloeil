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

Room is configured to export compiler-generated JSON to `app/schemas`, but the
workflow does not retain those files and no generated schema JSON is tracked.
These describe database structure rather than reading values. The
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
