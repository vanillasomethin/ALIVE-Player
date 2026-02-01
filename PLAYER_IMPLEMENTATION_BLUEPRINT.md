# Player (Android TV) Implementation Blueprint

## A1 — Bootstrap

### Modules

- **app** (UI + Activities)
- **core-playback** (engine, renderer switching)
- **core-data** (Room, DAOs, entities)
- **core-network** (Retrofit/OkHttp, auth, ETag)
- **core-download** (WorkManager download pipeline, integrity)
- **core-watchdog** (health checks, restart logic)

### Key components

- **PlaybackActivity** (full-screen, immersive, no chrome)
- **SettingsActivity/Fragment** (pairing status, reset, diagnostics)
- **ForegroundService**: owns the playback session + notification + wake locks
- **WorkManager**: download queue + POP upload + plan fetch retries

### Room DB

- **PlanCache** (1 row: json + etag + ts)
- **Asset** (content_id, version, sha256, path, size, last_accessed)
- **DownloadJob** (state, retries, bytes, error)
- **ProofEvent** (event_id, content_version_id, type, ts, duration fields)
- **Incident** (type, ts, metadata)

### Disk cache structure

```
/Android/data/<pkg>/files/cache/
  assets/
    <content_id>/
      <version>/
        <sha256>.<ext>
  tmp/
  logs/
  db/
```

## A2 — Pairing flow

### First launch

Show:

- claim code (short, e.g., 6–8 alphanum)
- device model, android version, app version, locale/timezone

Call backend:

- `POST /device/claim` (or `POST /pair/claim`) to create claim code

Poll or “Continue” button:

- `POST /device/claim/exchange` with `claim_code` → returns `device_token + device_id`

### Storage

**EncryptedSharedPreferences:**

- `device_token`
- `device_id`
- `paired_at`

### Settings → Reset device

- clear encrypted prefs
- clear Room tables: plan cache, downloads, assets, events
- delete `/assets` folder
- restart to pairing screen

## A3 — Playout plan fetch (ETag + offline)

OkHttp interceptor adds `Authorization: Bearer <device_token>`

### Request

- `GET /device/plan?hours=72` (or no param if fixed at 72)
- `If-None-Match: <etag>` if present

### Response handling

- **200**: store `plan_json`, `etag`, `fetched_ts`
- **304**: keep existing plan
- **Offline / timeout**: load last plan from Room; if none → fallback playlist only

## A4 — Downloader + cache integrity

### Requirements checklist

- Resume support → use OkHttp streaming + Range requests (or DownloadManager if you accept less control; for SHA + LRU, custom is better)
- Verify SHA-256 after full download
- Address assets by `content_id + version + hash`
- Storage cap + LRU eviction
- LRU based on `last_accessed` (updated whenever asset is played)
- Evict only assets not referenced by current plan (optional but recommended)

### WorkManager queue

- `DownloadWorker` per asset (unique by asset key)
- backoff + constraints (network required, charging optional)

## A5 — Playback engine (dayparting + fallback)

### Plan model (device-side)

- `PlanWindow(start_ts, end_ts, playlist_items[])`
- Each playlist item references a `content_version_id` and duration rules

### Renderers

- **Video**: Media3 ExoPlayer
- **Image**: ImageView with preloading + timed display
- **Web**: Chromium WebView (full screen), JS disabled unless needed, cache configured

### Smooth transitions

Preload next item:

- **Video**: prepare next `MediaSource`
- **Image**: decode bitmap ahead
- **Web**: pre-warm WebView instance (or keep one and change URL)

### No system UI

- immersive sticky flags
- hide navigation/status

### Fallback

If:

- no plan
- plan invalid
- asset missing and cannot download

→ fallback playlist (local bundled “house ads” or static screen)

## A6 — Proof-of-play (POP)

### Events

- **PLAY_START**: when rendering starts (first frame rendered for video; image displayed; page load finished for web)
- **PLAY_END**: when duration ends or item is replaced

### Local schema

- store `event_id`, `device_id`, `content_version_id`, `event_type`, `ts_utc`, `session_id`

For durations:

- compute `duration_ms = end_ts - start_ts`, ensure non-negative

### Upload

WorkManager `PopUploadWorker`

Batch:

- `POST /device/events` with `{ events: [...] }`

Retry:

- exponential backoff
- keep events until server ack

Idempotency:

- backend ignores duplicates by `event_id`

## A7 — Watchdog

Detect:

- “stuck playback” (no position progress for N seconds)
- repeated ExoPlayer errors
- black screen heuristic:
  - for video: if `onRenderedFirstFrame` never happens
  - for image/web: if render callback not reached

Actions:

- restart current item once
- if repeats > threshold:
  - restart player session (recreate ExoPlayer/WebView)
  - log Incident
- if still failing:
  - switch to fallback playlist
  - schedule plan refetch
