# Signage UX Uplift — Implementation Checklist

- [x] 1. QR Code on Pairing Screen (ZXing + two QRs: admin URL + raw key)
- [x] 2. Restructured Waiting Screen — State Cards (icon/headline/detail per FetchStatus)
- [x] 3. Download Progress on Waiting Screen (doneCount/totalCount DAO queries + progress bar)
- [x] 4. Offline Playback Badge (NetworkCallback, offline_badge TextView)
- [x] 5. Network Indicator on Waiting Screen (network_dot + network_label)
- [x] 6. Diagnostic Overlay (long-press → PIN → overlay with device info)
- [x] 7. Enhanced Settings Screen (relative timestamps, network, storage, pending, clear cache)
- [x] 8. Settings Access from Playback Screen (5-tap on waiting overlay)

## Review
All 8 features implemented across:
- `app/build.gradle.kts` — ZXing dependency
- `AndroidManifest.xml` — ACCESS_NETWORK_STATE permission
- `activity_pairing.xml` — QR row (admin URL + raw key)
- `PairingActivity.kt` — QR bitmap generation
- `data/DownloadJobDao.kt` — doneCount() + totalCount()
- `activity_playback.xml` — status card, network dot, offline badge, diag overlay
- `PlaybackActivity.kt` — NetworkCallback, updateStatusCard, diag overlay, 5-tap
- `activity_settings.xml` — network, storage, pending uploads rows + clear cache button
- `settings/SettingsFragment.kt` — relative timestamps, new rows, clear cache action

---

# Autostart Hardening + Silent OTA — Implementation Checklist

Goal: close the two real gaps found in the competitive audit — (1) no OEM-proof
autostart, (2) no working OTA on the sideload-distributed fleet. Decision from
user: full Device Owner provisioning (existing fleet will be re-enrolled),
implement both fixes in one pass since they share the same Device Owner base.

## A. Device Owner foundation
- [x] 1. `AliveDeviceAdminReceiver.kt` — DeviceAdminReceiver subclass
- [x] 2. `res/xml/device_admin.xml` — policy descriptor (minimal, no special policies needed)
- [x] 3. AndroidManifest.xml — register receiver w/ BIND_DEVICE_ADMIN + meta-data

## B. Autostart fix (HOME claim, not OEM-intent chasing)
- [x] 4. AndroidManifest.xml — add CATEGORY_HOME to PairingActivity's intent-filter
- [x] 5. `OwnerSetup.kt` — once device-owner, silently claim persistent-preferred HOME
      via `DevicePolicyManager.addPersistentPreferredActivity` (no user prompt)
- [x] 6. `OemAutostartHelper.kt` — manufacturer-detection fallback (Xiaomi/Realme/
      OnePlus/Vivo autostart-settings intents) for the pre-enrollment window /
      defense-in-depth on non-owner installs

## C. Silent OTA
- [x] 7. studio: `GET /api/device/update-check` — returns latest versionCode/
      versionName/apkUrl/sha256 from env vars
- [x] 8. studio: document new endpoint in `ALIVE_PLAYER_API.md`
- [x] 9. Player: `DeviceApiProvider.checkForUpdate()` — calls the new endpoint
- [x] 10. Player: `UpdateCheckWorker.kt` + real `UpdateScheduler` (periodic
      WorkManager, replaces the no-op) — checks version, downloads APK via
      existing `AssetDownloader` (reused as-is, it's already generic), installs
      via `PackageInstaller`
- [x] 11. Silent-install path: API 31+ uses `SessionParams.setRequireUserAction
      (USER_ACTION_NOT_REQUIRED)` (device-owner only, truly silent). API 26-30
      has no documented silent-install API for device owner — falls back to the
      standard confirm-dialog PackageInstaller flow. This is a real OS
      limitation, not a stub — must be stated plainly, not glossed over.
- [x] 12. AndroidManifest.xml — REQUEST_INSTALL_PACKAGES permission

## D. Ops
- [x] 13. `PROVISIONING.md` — zero-touch QR enrollment steps for re-provisioning
      already-deployed devices into Device Owner mode (factory reset required —
      operational step, not code)

## Verification
- [x] `./gradlew compileDebugKotlin` — **could not run**: this sandbox has no
      Android SDK installed and network policy blocks `dl.google.com` (where
      AGP/Android SDK artifacts are hosted), so Gradle can't even resolve the
      `com.android.application` plugin. No prior Gradle/AGP cache exists either.
      This is an environment limitation, not a code issue. In lieu of a real
      compile, did a full manual read-through of every new/changed file
      (manifest, `OwnerSetup.kt`, `AliveDeviceAdminReceiver.kt`,
      `OemAutostartHelper.kt`, `BootReceiver.kt`, `PairingActivity.kt`,
      `DeviceApiProvider.kt`/`DeviceApi.kt`, `UpdateCheckWorker.kt`,
      `UpdateInstallReceiver.kt`, `UpdateScheduler.kt`) cross-checking imports,
      method signatures against existing call sites (e.g. `AssetDownloader.download`
      named-arg signature, `DevicePrefs.getDeviceToken()`), API-level guards
      (`USER_ACTION_NOT_REQUIRED` gated on `SDK_INT >= S`), and manifest
      component registration. No issues found. **Recommend running a real
      `./gradlew assembleDebug` in CI or a local Android dev environment before
      shipping to the fleet.**
- [x] `npx tsc --noEmit` + `npm run build` clean in studio — both pass,
      `/api/device/update-check` compiles and builds as a dynamic route.
- [x] Commit + push both repos to `claude/ecstatic-heisenberg-j93BW`

## Review
Implemented both fixes on the shared Device Owner foundation, per user
decision (full-fleet Device Owner re-provisioning, both fixes in one pass):

**Autostart (HOME claim):** `PairingActivity` now also declares a
`CATEGORY_HOME` intent-filter. Once a device is enrolled as Device Owner,
`OwnerSetup.onDeviceOwnerReady()` calls `addPersistentPreferredActivity()` to
silently make it the permanent Home app — no user prompt, and immune to OEM
autostart/battery-kill restrictions since the OS always relaunches the active
Home app on boot. Called from `BootReceiver` (every boot, idempotent) and
`AliveDeviceAdminReceiver.onProfileProvisioningComplete` (right after
enrollment). `OemAutostartHelper` remains as a manual fallback for the
pre-enrollment window and for any device that's never re-provisioned.

**Silent OTA:** New `/api/device/update-check` endpoint (studio) reports the
latest configured release via env vars (no schema change). `UpdateCheckWorker`
polls it every 6h, reuses `AssetDownloader` unmodified to fetch the APK
(range-resume + SHA-256 verify), and installs via `PackageInstaller`. On
Device Owner + API 31+, the install uses `USER_ACTION_NOT_REQUIRED` — fully
silent. On older API levels, Android has no silent-install API even for
device owners, so it falls back to the standard confirm dialog
(`UpdateInstallReceiver` launches it). This is a real platform limitation,
documented plainly rather than glossed over.

**Ops:** `PROVISIONING.md` documents zero-touch QR enrollment (and the Fire TV
ADB fallback) for re-provisioning the existing fleet, since both fixes only
activate once a device is Device Owner.

**Known limitation:** Gradle/Kotlin compilation could not be verified in this
sandbox (no Android SDK, no network access to Google's Maven repo). Manual
review of all changed files found no issues, but a real `assembleDebug` build
should be run before this ships.

---

# Instability Diagnosis + SMIL Nested-Playlist Engine

- [x] 1. Instability audit — static review of the four suspected failure categories → `docs/instability-audit.md`
- [x] 2. Field capture script — `tools/diagnose-device.sh` (logcat + dumpsys, pre-filtered per category)
- [x] 3. Fleet incident telemetry — `Incident` rows (crash stacks, stalls, fallbacks) now upload with every heartbeat, deleted locally after 2xx (no Room version bump — destructive migration would wipe the PoP backlog)
- [x] 4. garlic-player SMIL study (scratchpad clone, AGPL — summary only, clone deleted) → `docs/smil-reference-notes.md`
- [x] 5. `PlanNode` tree parsing in `PlanModels.kt` (`nested` field of /api/device/plan; flat plans unchanged)
- [x] 6. `SmilSequencer` — original depth-first traversal with per-container cursors, keyed by planHash
- [x] 7. `PlaybackEngine.advance()` delegates to the sequencer when a nested tree exists; legacy round-robin otherwise
- [x] 8. JVM unit tests for the sequencer (order, nesting, empty/deep trees, cursor persistence, flatten-equivalence)

## Review

**Diagnosis (Task A).** All four suspected categories already had countermeasures in code
(foreground service + WakeLock, OEM autostart/battery prompts + device-owner HOME claim,
BootReceiver + crash handler, decoder blocklist + stall watchdog) — details with file/line
references in `docs/instability-audit.md`. The actionable gap was evidence: incidents lived
only in the on-device Room table. They now ship with heartbeats and land as
`TelemetryEvent(route='player/incident')` rows, queryable in the admin telemetry viewer, so
the dominant failure category is answerable from fleet data. Logcat cross-referencing waits
on a capture from a real device (`tools/diagnose-device.sh`).

**SMIL engine (Task B).** Backend companion (studio repo, same branch): `PlaylistItem`
gained `childPlaylistId` (XOR with `contentId`, cycle-rejected, depth ≤ 3), and
`/api/device/plan` emits both the flattened `items` (legacy players and the download
manifest — flattening equals SMIL play order) and the `nested` tree. Player side:
`SmilSequencer` walks the tree with live per-playlist cursors (groundwork for future
repeat/shuffle/interleave), keyed by planHash so cursors survive the per-item plan reloads.
No garlic-player code was copied, adapted, or vendored — architecture summary only
(`docs/smil-reference-notes.md`); the scratch clone was deleted after the study.

**Verification note:** unlike the previous session, `dl.google.com` was reachable this
time — Android cmdline-tools + SDK 35 were installed in the session scratchpad, so
`:app:compileDebugKotlin` and `:app:testDebugUnitTest` ran for real (closing that
session's compile-verification gap as well). Studio: `tsc --noEmit` + `next build`;
Prisma migration DDL cross-checked against `prisma migrate diff`.
