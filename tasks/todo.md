# OTA update prompt-loop fix (2026-08-10)

## Bug
`UpdateCheckWorker` (6h periodic) downloads a newer APK and commits a PackageInstaller
session unconditionally. On non-device-owner boxes (most of the sideloaded fleet) the
commit ends in `STATUS_PENDING_USER_ACTION` and `UpdateInstallReceiver` launches the
system install-confirm dialog over kiosk playback with `FLAG_ACTIVITY_NEW_TASK`.
Nobody at the TV confirms → version stays old → every 6h cycle re-prompts, stealing
focus from playback each time. And there is no `MY_PACKAGE_REPLACED` receiver, so when
an install *is* confirmed, the app dies and never comes back → black screen, no ads.

## Design
Never commit an install session that cannot complete:
- **Silent-capable** (device owner on S+, or self-installer-of-record on S+ with
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`) → commit immediately, install applies itself.
- **Needs user action** → do NOT commit from the worker. Just download + record
  "update ready" state. The Settings screen (operator present, reached via MENU key)
  shows an "Install update" button that performs the commit; the confirm dialog then
  appears in front of a human, never over playback.
- `UpdateGate.userActionAllowed` is true only while SettingsActivity is resumed —
  `UpdateInstallReceiver` uses it as belt-and-braces: a PENDING that leaks through
  while playback owns the screen is swallowed and recorded, not launched.
- New `PackageReplacedReceiver` (`MY_PACKAGE_REPLACED`) relaunches playback +
  reschedules workers after any successful update, so the screen comes back by itself.
- After the first operator-confirmed self-update the player becomes its own
  installer-of-record, so on Android 12+ every later update is fully silent.

## Todo
- [x] Read update flow, manifest, prefs, settings UI, boot receiver
- [ ] DevicePrefs: update-ready state (versionCode/name/apkPath + needs-user-action flag)
- [ ] UpdateGate (settings-visible flag)
- [ ] UpdateInstaller: shared session commit, silent-eligibility, stale-session abandon, old-APK prune
- [ ] UpdateCheckWorker: dedupe via state; commit only when completable
- [ ] UpdateInstallReceiver: gate dialog launch; record state; clear on success
- [ ] PackageReplacedReceiver + manifest entry; UPDATE_PACKAGES_WITHOUT_USER_ACTION permission
- [ ] UpdateScheduler.checkNow() one-shot
- [ ] SettingsActivity: toggle UpdateGate; SettingsFragment/layout: update row + install button
- [x] Clean build (JDK 17, CI parity)
- [x] Adversarial multi-lens review of the diff
- [ ] Re-build + re-verify after applying review fixes

## Review
First implementation passed a clean build; the 4-lens adversarial review (19 agents:
state-machine / Android-platform / kiosk-regression / requirements, each finding
independently refutation-tested) confirmed 12 findings (9 distinct). All fixed:
1. (major ×2 lenses) Own abandonSession() fires STATUS_FAILURE_ABORTED into our
   receiver, misread as operator-cancel → poisons needs-user-action on silent devices.
   Fixed: session-id recorded before commit; receiver ignores other sessions' statuses.
2. (critical) Silent install on non-owner installer-of-record boxes (API 29+) killed
   the app with no legal way to relaunch → dead screen. Fixed: auto-commit now also
   requires canRelaunchUiAfterInstall (owner / pre-Q / overlay grant); everyone else
   goes through the operator path.
3. (critical) On device-owner boxes both the OS HOME-resume and PackageReplacedReceiver
   relaunched → stacked PlaybackActivities/black screen. Fixed: BootReceiver-style
   current-HOME guard.
4. (minor ×2) checkNow one-shot could run concurrently with the periodic worker →
   same .part staging file corrupted. Fixed: worker-wide Mutex + KEEP policy.
5. (minor) commit() unserialized across 3 call paths; double-press abandoned the
   session behind a visible dialog. Fixed: @Synchronized + 10-min in-flight guard +
   button disabled until rebind.
6. (minor) Unvalidated (captive-portal) network consumed runs as success → Settings
   install silently no-oped forever. Fixed: Result.retry().
7. (minor) Settings could install a server-withdrawn build from stale state. Fixed:
   Install button always revalidates via checkNow (cached APK reused when current).
8. (minor) Deleted APKs left assetDao rows → phantom bytes shrank the 2GB LRU media
   budget. Fixed: prune/clear paths also delete rows.
9. (minor, declined) Live progress/status observer on the Settings update row —
   deliberate skip: observeForever on deprecated platform Fragment is fragilty for
   polish; onResume rebind covers state refresh. Revisit if operators report confusion.

---

# Stacked-PlaybackActivity poller outage fix (2026-08-14)

## Bug
Reproduced live on the Foxsky/KTC HiSilicon TV: three PlaybackActivity instances
stacked in one task (no launchMode; every launcher/monkey relaunch adds one).
Each instance keeps running downloadPollRunnable while backgrounded (callbacks only
removed in onDestroy). The poller kicked `engine.startLoop()` whenever ITS OWN
waitingOverlay was VISIBLE — but onWaiting/onPlaying are single vars on the shared
service engine, so a buried instance's overlay stays frozen VISIBLE and it kicks
startLoop() every 5s forever. startLoop() reset currentItemIndex=0 → screen replays
the first 5s of item 0 indefinitely (ExoPlayer Init/Release every 5.000s, no errors).

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

---

## Todo
- [x] Manifest: `android:launchMode="singleTask"` on PlaybackActivity (+ onNewIntent
      re-asserts kiosk guards on instance reuse)
- [x] PlaybackActivity: pollers start in onStart / removed in onStop (not onDestroy),
      so a STOPPED instance can never poll
- [x] PlaybackActivity: download poller kicks on `engine.isWaitingForContent`
      (shared engine truth), never the activity's own overlay view
- [x] PlaybackEngine: new `isWaitingForContent` state (set on waiting/no-content paths,
      cleared on render/stop)
- [x] PlaybackEngine: startLoop() no longer resets currentItemIndex — resumes from the
      current index (modulo list size), so a legitimate kick doesn't snap to item 0
- [x] Clean build (generic + kodak flavors)
- [x] Device test on the HiSilicon bench panel (192.168.15.156, APK 999320-debug):
      3 consecutive relaunches all delivered to the SAME ActivityRecord (4c0ad9f) —
      no stacking; 90s logcat cadence cycles 5.4s→11.3s→5.8s→5.5s repeatedly (all 4
      playlist items, incl. the 11s one) — no 5.000s item-0 reset signature. One
      transient "Video error 2001: Source error" recovered as designed (evict+advance).
      Mid-test the app vanished: logcat shows two external force-stops (someone using
      the TV's selenview settings at the bench) — not a crash; relaunched, cadence
      healthy again. NOT committed — tree also holds unrelated update-gate WIP.
