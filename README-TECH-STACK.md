# Tech Stack + Commands Spec

This document defines the expected technology stack and common commands for development and CI.

## Platform + Language

- **Android TV** (minimum SDK aligned with target devices).
- **Kotlin** for application code.
- **Gradle (Kotlin DSL)** for build configuration.

## Core Libraries

- **Media3 ExoPlayer** for playback.
- **Room** for local persistence.
- **WorkManager** for background tasks (plan refresh, event upload, cleanup).
- **EncryptedSharedPreferences** for device token storage.
- **OkHttp + Retrofit** for network API calls.
- **Coil / Glide** for image loading (as needed).
- **Kotlin Coroutines + Flow** for async data streams.

## Services

- **ForegroundService** for resilient playback sessions.
- **Download Manager** for asset caching + integrity verification.

## Common Commands

### Build + Run

```bash
./gradlew clean assembleDebug
./gradlew installDebug
```

### Unit Tests

```bash
./gradlew testDebugUnitTest
```

### Instrumentation Tests

```bash
./gradlew connectedDebugAndroidTest
```

### Static Analysis

```bash
./gradlew lintDebug
./gradlew detekt
```

### Formatting

```bash
./gradlew spotlessApply
```

## CI Expectations

- Run unit tests, lint, and format checks on every PR.
- Run instrumentation tests on a nightly schedule.
- Store build artifacts (APK + mapping files) for releases.
