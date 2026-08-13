# ALIVE Player — Instability Audit (static code review)

Reported symptoms: crashes, freezes, black screen after reboot, content not updating, media
glitches. This audit maps each suspected failure category to what the codebase already does,
what evidence exists, and what can still produce the symptoms. **No logcat was available at
audit time** — capture one from an affected box with `tools/diagnose-device.sh` and attach it
to complete the categorization with real evidence.

## Verdict summary

The four commonly-suspected categories are each already substantially countered in code. The
dominant remaining risks are (a) **non-device-owner installs on aggressive-OEM boxes**, and
(b) **vendor decoder incompatibilities not yet in the blocklist**. The biggest diagnostic gap
was that on-device incident records never reached the server — fixed alongside this audit
(see "Fleet incident telemetry" below).

## Category 1 — Background service killed (Android 12+ / not-foreground)

**Status: already handled — not the cause on enrolled devices.**

- `PlaybackForegroundService` is a true foreground service: `startForeground(1, notification)`
  in `onCreate` (`service/PlaybackForegroundService.kt:40`), manifest type `mediaPlayback`
  (`AndroidManifest.xml:77`), `START_STICKY`, and a held `PARTIAL_WAKE_LOCK` (`:42`).
- A second foreground service (`WatchdogService`, `specialUse` type) runs in its own OS
  process (`:watchdog`) and kill-restarts the main process if its heartbeat file goes stale
  for 90 s (`service/WatchdogService.kt:56-69`) — this covers full main-thread ANR, which an
  in-process watchdog cannot.

**Residual risk:** none specific to this category. If logcat shows
`ActivityManager: Killing ... fg service` anyway, look at Category 2 (OEM) instead.

## Category 2 — OEM battery managers (MIUI-style autostart kills)

**Status: handled for device-owner installs; partially handled otherwise.**

- Device-owner installs (zero-touch QR, `PROVISIONING.md`) silently claim persistent HOME
  (`admin/OwnerSetup.kt`), and the OS always restarts the active HOME app — this bypasses OEM
  autostart lists entirely. This is the strongest protection available and is why enrollment
  matters.
- Non-owner installs get: a battery-optimization exemption prompt
  (`ui/PairingActivity.kt:252-259`, skipped once `isIgnoringBatteryOptimizations` is true) and
  a one-time OEM autostart settings prompt (`admin/OemAutostartHelper.kt` — MIUI et al.).

**Residual risk (plausible cause):** a box installed *without* device-owner enrollment where
staff dismissed the OEM autostart prompt. The app then survives only until the OEM task
killer runs. **Check per affected device:** `tools/diagnose-device.sh` records
manufacturer/model and whether the app is device owner + battery-exempt.

## Category 3 — Boot / lifecycle (black screen after reboot)

**Status: handled; failure window exists only on unpaired or non-owner boxes.**

- `BootReceiver` (BOOT_COMPLETED) re-asserts the HOME claim, reschedules all workers, and
  starts the playback + watchdog services (`receiver/BootReceiver.kt`).
- Crashes are caught by a default uncaught-exception handler that records the stack trace to
  the local `Incident` table and forces a fast process exit so the HOME relaunch is immediate
  instead of waiting on the OEM crash dialog (`AliveApplication.kt:20-38`).
- "Black screen after reboot" on a healthy install is more likely Category 4 in disguise:
  `PlaybackEngine` deliberately never hides the video surface because some vendor TextureView
  builds never render into a non-VISIBLE view (`playback/PlaybackEngine.kt:300-307`) — a
  regression here or an un-blocklisted decoder shows as black screen at the first video.

## Category 4 — Media / codec (glitches, freezes, black video)

**Status: substantial hardening exists; this is the category where new device models keep
adding cases.**

- Exact-name blocklist of confirmed-broken vendor hardware decoders (Hisilicon AVC that never
  drains output buffers; Realtek AVC that fails init) forces the next codec candidate
  (`playback/PlaybackEngine.kt:71-94`, `DecoderCapabilities`).
- Fresh ExoPlayer instance per video item — a poisoned codec pipeline can't leak into
  subsequent items (`:243-251`).
- Decoder stall watchdog: position not advancing for 10 s → evict cached file as corrupt,
  record stall reason (surfaced in heartbeat telemetry), advance (`:369-434`).
- Decode/source errors evict the cached copy and advance after a 2 s debounce (`:356-366`).
- HEVC rendition preferred where the device's HEVC decoder is healthy (`DecoderCapabilities.preferHevc`).

**Residual risk (plausible cause):** decoder models not yet blocklisted, or systemic cache
corruption (full storage truncating downloads — `freeStorageMb` is reported each heartbeat
precisely for this; check the Screens tab for low values).

## Content not updating

Not one of the four categories but reported — covered by: 15-min plan poll + FCM
`plan_updated` push + admin force-sync + `forceSyncAt` in `planHash`. If a device shows stale
content with a fresh `lastSeen`, suspect FCM token loss (check `fcmToken` null in DB) or the
plan hash short-circuit; capture output includes the app-side last fetch status.

## Fleet incident telemetry (the evidence fix shipped with this audit)

On-device `Incident` rows (`UNCAUGHT_EXCEPTION` with stack trace, `STUCK_PLAYBACK`,
`FALLBACK_TRIGGERED`) previously never left the device. They are now uploaded with every
heartbeat and stored server-side as `TelemetryEvent` rows (`route='player/incident'`), so
"which category dominates" is answerable from the fleet after a few days of data:

- crash-loop devices → high `UNCAUGHT_EXCEPTION` counts (stack traces attached)
- freeze-prone devices → `STUCK_PLAYBACK` / stall reasons
- OEM-kill victims → *no* incidents but repeated `OFFLINE_TRANSITION` + reboots (uptime resets
  in capture script output)

## Next step (requires a human with the device)

Run `tools/diagnose-device.sh` against 1-2 misbehaving boxes, attach the output folder.
The cross-referencing pass (crash timestamps vs uptime resets vs media items vs fetch times)
happens on that data; fixes are chosen only after the dominant category is confirmed.
