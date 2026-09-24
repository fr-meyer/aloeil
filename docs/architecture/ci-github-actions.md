# GitHub Actions CI builder

Status: approved for the P2 Android bootstrap.

GitHub Actions CI is Aloeil's builder. The OpenClaw VM is not a builder, a GCP extra disk is not a builder, and there is no 24/7 builder VM. A clean GitHub-hosted runner obtains the declared JDK and Android SDK, then uses the repository's Gradle wrapper.

Pull requests run JVM unit tests only. The workflow does not start an emulator and must not run `connectedAndroidTest` or any other connected-device task. The current synthetic fixture test contains build-state labels only and no health data or sample readings.

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

The workflow has read-only repository contents permission. It defines no secrets, does not request write permission for `GITHUB_TOKEN`, and performs no publication or deployment.
