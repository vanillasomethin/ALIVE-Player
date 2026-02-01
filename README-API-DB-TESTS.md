# API Endpoints + Database + Acceptance Tests Spec

This document defines API endpoint contracts (OpenAPI-style listing), database table fields, and acceptance tests per module.

## API Endpoints (OpenAPI-Style List)

### POST /device/claim
- **Summary:** Exchange claim code for device token.
- **Request Body:**
  - `claim_code` (string, required)
  - `device_model` (string, required)
  - `device_serial` (string, required)
  - `firmware_version` (string, required)
- **Responses:**
  - `200 OK`: `{ "device_token": "string", "expires_at": "ISO-8601" }`
  - `400 Bad Request`: `{ "error": "invalid_claim_code" }`

### GET /device/plan
- **Summary:** Fetch playout plan with ETag.
- **Headers:**
  - `If-None-Match` (optional)
  - `Authorization: Bearer <device_token>`
- **Responses:**
  - `200 OK` (ETag header + JSON body)
  - `304 Not Modified`
  - `401 Unauthorized`

### POST /device/events
- **Summary:** Upload proof-of-play events.
- **Headers:**
  - `Idempotency-Key` (UUID)
  - `Authorization: Bearer <device_token>`
- **Request Body:**
  - `events` (array)
    - `event_id` (UUID)
    - `content_id` (string)
    - `event_type` (enum: `PLAY_START`, `PLAY_END`)
    - `timestamp` (ISO-8601)
- **Responses:**
  - `200 OK`: `{ "accepted": true }`
  - `409 Conflict`: `{ "error": "duplicate_event" }`

### GET /device/ping
- **Summary:** Lightweight health check.
- **Responses:**
  - `200 OK`

## Database Table Fields

### `proof_of_play_events`

| Column | Type | Notes |
| --- | --- | --- |
| id | INTEGER (PK, auto) | Internal DB ID |
| event_id | TEXT (UUID) | Unique event ID |
| content_id | TEXT | Content identifier |
| event_type | TEXT | `PLAY_START` or `PLAY_END` |
| timestamp | TEXT (ISO-8601) | Event time |
| uploaded | INTEGER (0/1) | Sync status |
| retry_count | INTEGER | Upload retry counter |
| created_at | TEXT (ISO-8601) | Local create time |

### `playout_schedule`

| Column | Type | Notes |
| --- | --- | --- |
| id | INTEGER (PK, auto) | Internal DB ID |
| plan_etag | TEXT | Server ETag for plan |
| plan_payload | TEXT | JSON of full plan |
| effective_from | TEXT (ISO-8601) | Plan start time |
| effective_to | TEXT (ISO-8601) | Plan end time |
| last_fetched_at | TEXT (ISO-8601) | Last fetch timestamp |

## Acceptance Tests per Module

### A1 — Bootstrap
- Launch app → full-screen playback Activity appears.
- Settings screen reachable via remote control.
- Foreground service stays alive after app backgrounding.
- Room DB created on first launch.
- WorkManager scheduled jobs registered.
- Disk cache folder structure created at startup.

### A2 — Pairing Flow
- First launch shows claim code and device metadata.
- Successful claim stores device token in EncryptedSharedPreferences.
- Invalid claim shows error and retry option.
- Reset device clears token and re-enters pairing flow.

### A3 — Playout Plan Fetch
- GET /device/plan uses ETag for cache validation.
- 304 response keeps previous plan in use.
- Offline mode uses last successful plan.
- Plan stored locally after 200 response.

### A4 — Downloader + Cache Integrity
- Asset downloads resume after interruption.
- SHA-256 mismatch triggers re-download.
- Storage cap enforced with LRU eviction.
- Asset addressing uses `content_id + version + hash`.

### A5 — Playback Engine
- Plays mp4 video full-screen without chrome.
- Displays images with correct duration.
- Web URL plays in Chromium WebView.
- Dayparting chooses correct playlist by time.
- Fallback playlist used when plan invalid.
- Smooth transitions between items.

### A6 — Proof-of-Play
- PLAY_START and PLAY_END events emitted per item.
- Events stored locally before upload.
- Batch upload retries with backoff on failure.
- Idempotency prevents duplicate server records.

### A7 — Watchdog
- Detects stalled playback (no progress > threshold).
- Detects black screen events.
- Restarts player session after repeated errors.
- Logs incident for diagnostics.
