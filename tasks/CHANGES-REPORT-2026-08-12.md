# ALIVE Signage — Changes Report (Deepak)

_Generated 2026-08-12. Covers repository structure + all uncommitted working-tree changes and recent commits across the workspace._

## 1. Repository structure

`~/alive-signage-player/` is a multi-repo workspace: **three independent git repos** plus a submodule.

| Path | Repo / role | Remote | Branch @ HEAD |
|---|---|---|---|
| `studio/` | Next.js CMS/backend, deployed to wearealive.in (Vercel) | vanillasomethin/studio | `main` @ `e294957` |
| `ALIVE-Player/` | Android TV signage player (Kotlin, Media3, WorkManager, Room) | vanillasomethin/ALIVE-Player | `feature/kiosk-exit-hatch` @ `d60dd9f` |
| `ALIVE-Player/studio/` | studio vendored as a submodule (reference pin) | vanillasomethin/studio | pinned @ `541a78f` |
| `apk-releases/` | Built-APK distribution, **local-only (no remote)** | — | `main` @ `bbb900c` |
| `apk-releases/by-tv/` | Per-panel APKs | — | `alive-player-hisilicon.apk`, `alive-player-kodak.apk` |

Notes:
- **studio exists twice** — the reference submodule pin (`541a78f`) and a separate top-level checkout (`e294957`, 6 commits ahead). Live backend work happens in the top-level checkout, not the pin.
- ALIVE-Player `feature/kiosk-exit-hatch` is **6 commits ahead of main** (5×BACK exit hatch, boot hardening, adversarial-review crash fixes, image-slot playback fixes) and carries the large uncommitted WIP below.
- Author split (last 20 ALIVE-Player commits): **15 Deepak**, 3 org account, 2 Claude. studio recent work almost entirely Deepak.
- **Nothing from the field session is committed or pushed.**

## 2. Uncommitted changes in ALIVE-Player

Branch `feature/kiosk-exit-hatch` (HEAD `d60dd9f`): **19 modified + 6 new files, ~1058 insertions / 305 deletions**, spanning three distinct efforts.

### Effort A — OTA update: stop the install-prompt loop over playback

The old flow re-launched the system install dialog on top of the ad loop on every 6-hour check on non-owner devices, forever, and left a dead screen after a successful update. Reworked so a confirm dialog can never appear over unattended playback.

| File | Δ | What |
|---|---|---|
| `worker/UpdateGate.kt` | **new** | `@Volatile userActionAllowed`, true only while SettingsActivity is resumed. The single guard for "may the confirm dialog appear". |
| `worker/UpdateInstaller.kt` | **new** | Single owner of PackageInstaller. `canInstallSilently()` (owner or installer-of-record, API31+), `canRelaunchUiAfterInstall()`, `@Synchronized commit()` (abandons stale sessions, 10-min in-flight guard, persists session-id before commit), `pruneOldUpdates()`/`clearAllUpdates()`. |
| `receiver/PackageReplacedReceiver.kt` | **new** | `MY_PACKAGE_REPLACED` → relaunch playback + reschedule workers after a self-update (skips relaunch when it's already HOME, to avoid stacking). Fixes the dead-screen-after-update. |
| `worker/UpdateCheckWorker.kt` | +63/−49 | Never commits an install unless it can complete (silent+relaunchable) or an operator is in Settings. Process-wide mutex, ready-state recording, captive-portal retry, 410→decommission. |
| `worker/UpdateInstallReceiver.kt` | +50/−11 | Launches the confirm dialog only if the gate is open; else records "needs user action". Session-id filter rejects noise from abandoned sessions. |
| `worker/UpdateScheduler.kt` | +21 | `checkNow()` one-shot backing the Settings button; kiosk-exit cancels it too. |
| `settings/SettingsActivity.kt` | +15 | Opens/closes UpdateGate on resume/pause. |
| `settings/SettingsFragment.kt` | +54/−16 | "App update" row + Install button (runs the one-shot so the server is re-validated first). Also refactors reset → `DeviceDecommissioner.wipe`. |
| `settings/DevicePrefs.kt` | +66 (OTA subset) | Update-ready state, pending-session-id, needs-user-action flags. |
| `res/layout/activity_settings.xml` | +52 | The operator-facing update row (hidden until an update is ready). |
| `AndroidManifest.xml` | +15 | `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission + the new receiver. |
| `res/xml/device_admin.xml` | +3/−2 | Comment-only. |

Reviewed by a 19-agent adversarial pass (found + fixed 9 defects). **Status: code-complete, not yet exercised end-to-end** — the test APK's versionCode (9001) sits above the server build, so the OTA path never triggers on the bench device.

### Effort B — Time-sync / network / device-lifecycle hardening (prior field session)

Three reliability fixes for real field failures.

| File | Δ | What |
|---|---|---|
| `settings/NtpSyncManager.kt` | +204/−20 | Sync **before** the first server call (not after); multi-host NTP (google/cloudflare/pool), IPv4-first, garbage-timestamp rejection; **HTTP-Date fallback** when UDP/123 is blocked; on device-owner hardware **writes the corrected time into the system clock** via `setTime()` so TLS self-heals; `isClockBadlyWrong()` for operator diagnostics. |
| `network/NetworkProbe.kt` | **new** | Raw-socket HTTP/1.1 `HEAD` to port 80 (bypasses cleartext policy) to distinguish the two failures that both throw "Trust anchor not found": an intercepting proxy (Via header / bare 403) vs. a wrong clock (reads the Date header). |
| `data/DeviceDecommissioner.kt` | **new** | Full identity/content wipe → return to pairing when a screen is deleted in admin. Cancels workers + stops service first, clears all Room tables + caches + prefs. Also backs the Settings "Reset Device" button. |
| `worker/PlanFetchWorker.kt` | +63/−5 | NTP-sync-first; 410→decommission; `diagnose()` turns a raw TLS error into an actionable operator message (proxy vs. clock). |
| `network/AliveMessagingService.kt` | +11/−3 | FCM `decommission` push → wipe (fast path for reachable screens). |
| `worker/HeartbeatWorker.kt` | +7 | 410→decommission, ordered **before** the 401/403 re-claim so a deleted device can't resurrect itself. |
| `worker/PopUploadWorker.kt` | +8 | Same 410→decommission guard. |

**Root cause captured** in `tasks/field-findings-2026-08-11.md` (new, +288): Bug A (OTA loop), Bug B (clock drift — **verified fixed on-device**), Bug C (network proxy/UDP-block — no code fix, needs a router whitelist; NetworkProbe now reports it clearly).

### Effort C — Video playback double-buffer + per-model build flavors (this session)

Aims to remove the ~0.5–1s boundary freeze between clips. **The double-buffer path is EXPERIMENTAL and currently INERT** (`DOUBLE_BUFFER_VIDEO=false` for both flavors).

| File | Δ | What |
|---|---|---|
| `playback/PlaybackEngine.kt` | +316/−83 (stat: 399) | Two-surface engine: preload the next clip on a covered second `PlayerView`, z-order swap at the boundary. Gated behind `BuildConfig.DOUBLE_BUFFER_VIDEO`; falls back to the original single-surface path when off. |
| `res/layout/activity_playback.xml` | +16 | Second `player_view_b` surface (never GONE; hidden by z-order only). |
| `ui/PlaybackActivity.kt` | +3/−1 | Wires `player_view_b` into the new `attachViews` signature. |
| `build.gradle.kts` | +27 | Product flavors on a `device` dimension: `generic` (default) + `kodak`; each emits `DEVICE_PROFILE` + `DOUBLE_BUFFER_VIDEO` and a per-model APK. Same applicationId/signing/server across all. |

**What actually ships today** (flag-independent, so worth the most review): the z-order `transitionViews` rework, arming the slot timer from `onRenderedFirstFrame` (+`VIDEO_END_GRACE_MS`, fixes clips being cut ~1s short), and the merged image/web full-teardown. The gapless swap path is unverified because the flag is off — and double-buffer was **measured to worsen** stalls on the single-decoder Kodak/Realtek panel, so it must be validated per-panel before any flavor enables it.

## 3. Committed work (context)

- **studio** (`main` @ `e294957`, Deepak): Proof-of-Play reporting (By Screen/Ad/Groups + date range, `ea36ec5`), real campaign-card analytics (`541a78f`), partner-onboarding stage fixes, payout/rate-limiter hardening. 5 uncommitted device-API/FCM WIP files in the working tree.
- **ALIVE-Player** `feature/kiosk-exit-hatch` (6 commits over main): 5×BACK kiosk exit hatch, boot auto-start hardening, exit-crash fixes, image-slot playback fixes.
- **apk-releases**: 3 commits of built APKs (Realtek exclusion, HEVC preference, blank-screen fix) + the `by-tv/` per-panel set.

## 4. Overall status & risks

- **Nothing from the field/session work is pushed.** All Effort A/B/C changes are uncommitted on `feature/kiosk-exit-hatch`.
- **Verified:** clock-drift fix (Bug B) on-device.
- **Not verified end-to-end:** OTA install flow (Bug A) — version numbering prevents triggering it on the bench; the double-buffer path (inert by flag).
- **No code fix possible for Bug C** (network interception) — needs a router-side whitelist of wearealive.in + UDP/123.
- Diff-only reading for Efforts A/C detail; not a full build/run audit of every path.
