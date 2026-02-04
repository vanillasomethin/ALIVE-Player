# Contributing

Thanks for helping build the ALIVE Android TV player. This repo currently contains a scaffolding layout and implementation blueprint.

## Requirements

- JDK (version aligned with the Android Gradle plugin used by the project)
- Android SDK + Build Tools
- Gradle wrapper (`./gradlew`)

## Build + Run

```bash
./gradlew clean assembleDebug
./gradlew installDebug
```

## Unit Tests

```bash
./gradlew testDebugUnitTest
```

## Instrumentation Tests

```bash
./gradlew connectedDebugAndroidTest
```

## Static Analysis

```bash
./gradlew lintDebug
./gradlew detekt
```

## Formatting

```bash
./gradlew spotlessApply
```

## PR Expectations

- Keep changes focused and scoped to a single concern.
- Update documentation and tests when behavior changes.
- Run the relevant checks before opening a PR.
