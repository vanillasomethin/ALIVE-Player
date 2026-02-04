# Implementation Notes

This repository now contains a starter skeleton aligned with the Android TV player blueprint:

- `app/src/main/java/com/alive/player/ui` → Activities for playback and settings.
- `app/src/main/java/com/alive/player/service` → Foreground playback service.
- `app/src/main/java/com/alive/player/worker` → WorkManager jobs for plan fetch, downloads, and POP upload.
- `app/src/main/java/com/alive/player/data` → Room entities and database placeholders.
- `app/src/main/java/com/alive/player/network` → API contracts and auth interceptor placeholder.
- `app/src/main/java/com/alive/player/download` → Asset downloader placeholder.
- `app/src/main/java/com/alive/player/playback` → Playback engine + watchdog placeholders.
- `app/src/main/java/com/alive/player/schedule` → Plan model definitions.

Refer to the root-level `PLAYER_IMPLEMENTATION_BLUEPRINT.md` for the full behavior expectations.
