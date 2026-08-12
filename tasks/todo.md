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
