# Repository Folder Structure Spec

This document defines the expected folder structure for the Android TV player app.

## Top-Level Layout

```
.
├── app/                       # Android application module
├── build-logic/               # Gradle convention plugins and build logic
├── docs/                      # Extended documentation and specs
├── gradle/                    # Gradle wrapper and version catalogs
├── scripts/                   # Dev scripts (lint, formatting, CI helpers)
├── .github/                   # GitHub Actions workflows and templates
├── settings.gradle.kts
└── build.gradle.kts
```

## `app/` Module Structure

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/alive/player/
│   │   ├── core/              # Logging, analytics, feature flags
│   │   ├── data/              # Room DB, repositories, data sources
│   │   ├── download/          # Downloader, cache, integrity checks
│   │   ├── network/           # API client, DTOs, interceptors
│   │   ├── playback/          # ExoPlayer session, renderer, watchdog
│   │   ├── schedule/          # Playout plan parsing, dayparting
│   │   ├── service/           # Foreground playback service
│   │   ├── settings/          # Settings UI + storage
│   │   ├── ui/                # Activities, fragments, compose views
│   │   └── worker/            # WorkManager jobs
│   ├── res/
│   └── assets/
│       └── cache/             # Placeholder for disk cache root
│   └── kotlin/                # Optional if Kotlin source set split
├── src/test/                  # Unit tests
└── src/androidTest/           # Instrumentation tests
```

## `docs/` Specs

```
docs/
├── api/                       # API endpoint specs
├── database/                  # DB schema and migrations
├── testing/                   # Acceptance test specs per module
└── architecture/              # System diagrams, ADRs
```

## Cache Folder Layout

```
<cache_root>/
├── media/
│   ├── <content_id>/
│   │   ├── <version>/
│   │   │   ├── <hash>/
│   │   │   │   └── asset.bin
│   │   └── .lru
│   └── .index
├── images/
│   └── ...
├── web/
│   └── ...
└── temp/
```

## Module Ownership Mapping

- **A1 Bootstrap** → `playback/`, `service/`, `settings/`, `data/`.
- **A2 Pairing Flow** → `settings/`, `network/`, `data/`.
- **A3 Playout Plan Fetch** → `schedule/`, `network/`, `data/`.
- **A4 Downloader + Cache Integrity** → `download/`.
- **A5 Playback Engine** → `playback/`, `schedule/`.
- **A6 Proof-of-Play** → `data/`, `worker/`, `network/`.
- **A7 Watchdog** → `playback/`.
