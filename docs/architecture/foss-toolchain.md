# FOSS Android toolchain

Status: approved for the GitHub Actions bootstrap. This decision does not implement Room, encryption, backup, or an outbox.

## Required toolchain

Aloeil builds with Eclipse Temurin 17 LTS. Oracle JDK is not permitted. The Gradle wrapper pins Gradle 8.11.1 and verifies the binary distribution checksum; the Android project pins Android Gradle Plugin 8.9.1, Kotlin and the Compose compiler plugin 2.2.10, Android SDK 35, and each current AndroidX dependency through `gradle/libs.versions.toml`.

The application stack is Kotlin, Jetpack Compose, Room, DataStore, and Android Keystore-backed cryptography. Kotlin and the selected AndroidX components are Apache-2.0 licensed. Keystore access uses Android platform and Java Cryptography Architecture APIs; no proprietary helper SDK is introduced. Room and DataStore are approved for later P2 work but are deliberately not dependencies of this CI bootstrap.

Any added application dependency must be available as source under Apache-2.0, be pinned to an exact version or a pinned BOM, and have its license checked before merge. Play services, Firebase, Crashlytics, ads, Oracle JDK, and every artifact in the `com.google.android.gms` namespace are forbidden. Hilt and Dagger are not part of the bootstrap; manual constructor injection remains the intended approach.

## Distribution shape

The project has one F-Droid-shaped application flavour: the default open-source variant, with no proprietary product flavour or service-specific source set. Distribution is sideload-first. An F-Droid recipe may be added later, after the application has enough functionality to package.

## Unavoidable platform residue

The known unavoidable non-source residue is limited to official Android SDK prebuilt tooling and platform packages used to compile the app, plus the OEM implementation of Android Keystore and any device TEE used at runtime. These are platform/toolchain boundaries, not application services, analytics, or network dependencies. Their presence does not permit adding Play services or other proprietary application libraries.
