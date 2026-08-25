# Field debugging session — 2026-08-11

Device under test: **Kodak / SPPL_2K_RT41** (Realtek panel), Android 11 (API 30),
`192.168.15.154`, non-device-owner sideload. Symptom reported by operator: repeated
"updates are available, do you wish to install" prompts interrupting ad playback, and
the app exiting/not playing.

Three separate problems were found. Bug A explains the prompts. Bug B (clock drift that
a screen cannot recover from) is a genuine fleet-wide defect. Bug C — a network policy
transparently proxying and blocking this TV — turned out to be what was actually keeping
*this* screen dark.

> **Correction.** Bug B was first written up here as the root cause of the dark screen.
> That was wrong, and the evidence that corrected it is recorded under Bug C. The clock
> defect is real and worth fixing, but it is not why this TV stopped playing.

---

## Bug A — OTA install prompt re-appeared over playback forever

**Found:** by code inspection while reproducing the operator's report.

`UpdateCheckWorker` downloaded a newer APK and committed a `PackageInstaller` session
unconditionally, every 6 hours. On a non-device-owner device (most of the sideloaded
fleet) the commit lands in `STATUS_PENDING_USER_ACTION`, and `UpdateInstallReceiver`
launched the system confirm dialog with `FLAG_ACTIVITY_NEW_TASK` — i.e. over whatever
was playing. Nobody is standing at an unattended screen to answer it, so the version
never changed and the next 6-hour tick prompted again, forever.

Compounding it: there was no `MY_PACKAGE_REPLACED` receiver, so on the occasions an
install *was* confirmed, the install killed the app and nothing brought it back —
a dead screen until the next reboot. That is the "app exits and stops playing ads"
half of the report.

**Fix:** never commit an install that cannot complete.
- Silent-capable device (device owner on 12+, or self-installer-of-record on 12+ with
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`) **and** able to relaunch itself afterwards →
  commit immediately; the update applies with no UI at all.
- Otherwise → download and record "update ready" only. `SettingsActivity` holds
  `UpdateGate` open while an operator is on screen, and the Settings "Install update"
  button is the one place the confirm dialog may appear.
- `UpdateInstallReceiver` re-checks the gate, so a `PENDING` that arrives during
  playback is swallowed and recorded rather than shown.
- New `PackageReplacedReceiver` relaunches playback + reschedules workers after any
  successful update.

Files: `worker/UpdateCheckWorker.kt`, `worker/UpdateInstaller.kt` (new),
`worker/UpdateGate.kt` (new), `worker/UpdateInstallReceiver.kt`,
`worker/UpdateScheduler.kt`, `receiver/PackageReplacedReceiver.kt` (new),
`settings/DevicePrefs.kt`, `settings/SettingsActivity.kt`, `settings/SettingsFragment.kt`,
`res/layout/activity_settings.xml`, `AndroidManifest.xml`.

### Defects found in the first cut of that fix (adversarial review, 19 agents)

Reviewed before shipping; 9 distinct defects confirmed and fixed:

| # | Severity | Defect | Fix |
|---|---|---|---|
| 1 | critical | Silent install on a non-owner installer-of-record box (API 29+) killed the app with no legal way to relaunch → dead screen | auto-commit also requires `canRelaunchUiAfterInstall()` (owner / pre-Q / overlay grant) |
| 2 | critical | On device-owner boxes the OS HOME-resume *and* the new receiver both relaunched → stacked activities / black screen | skip the relaunch when the app is already current HOME (same guard `BootReceiver` uses) |
| 3 | major | Our own `abandonSession()` fires `STATUS_FAILURE_ABORTED` into our own receiver, misread as an operator pressing Cancel → wedged auto-install | persist the committed session id; ignore statuses from any other session |
| 4 | minor | `checkNow` one-shot could run concurrently with the periodic worker → both wrote the same `.part` staging file | process-wide `Mutex` in the worker + `ExistingWorkPolicy.KEEP` |
| 5 | minor | `commit()` unserialized across 3 call paths; a double-press abandoned the session behind a visible dialog | `@Synchronized` + 10-minute in-flight guard + button disabled until rebind |
| 6 | minor | Captive-portal network consumed the run as `success` → Settings install silently no-oped forever | return `Result.retry()` on unvalidated network |
| 7 | minor | Settings could install a server-withdrawn build from stale local state | Install button always revalidates via `checkNow` (cached APK reused when still current) |
| 8 | minor | Deleted update APKs left `assetDao` rows → phantom bytes counted against the 2 GB LRU budget, evicting real media early | prune/clear paths delete rows alongside files |
| 9 | minor (declined) | No live progress text on the Settings update row | deliberate skip — `observeForever` on the deprecated platform `Fragment` is fragility for polish; `onResume` rebind covers refresh |

---

## Bug B — a drifted system clock bricks a screen permanently (more serious)

**Found:** on the live Kodak TV. `PlanFetchWorker` was looping `Result.RETRY` every 30 s
and no ads were playing. The recorded reason in `alive_player_status.xml` was:

```
fetch_message = java.security.cert.CertPathValidatorException:
                Trust anchor for certification path not found.
```

The TV's clock read **Thu Jan 2 2025**; real date was **Aug 11 2026** — ~19 months in
the past. TLS certificates are only valid inside a date window, so every https call
failed validation. Not app-specific: the TV's own Google `cast_shell` was also failing
handshakes (`net_error -201`, ERR_CERT_AUTHORITY_INVALID) continuously.

Device state confirming the cause:
- `plan_updated_ms` = 1786092896182 → Aug 6 2026 (last genuinely successful fetch)
- `fetch_time`      = 1735785389938 → Jan 2 2025 ("now" per the TV)
- `auto_time` = 1 but **`ntp_server` = null** — this OEM firmware ships automatic time
  with no NTP server configured, so auto-time silently never sets the clock. Budget
  panels have no RTC battery, so any power cut drops them to the firmware build date
  and they stay there.

**Why the app could never recover on its own — two compounding defects:**

1. `NtpSyncManager.sync()` was only called *after a successful plan fetch*
   (`PlanFetchWorker`, old line 103). The fetch requires TLS, TLS requires a correct
   clock — so the one mechanism that could detect the drift was unreachable on exactly
   the devices that needed it. Chicken-and-egg deadlock.
2. Even had it run, it only stored an **app-level offset** used for proof-of-play
   timestamps. TLS validates against the **system** clock, which the offset does not
   touch — so the sync would not have fixed the outage either.

**Fix:**
- `NtpSyncManager.syncIfNeeded()` now runs **before** the first server call in
  `PlanFetchWorker`, forced when the previous attempt errored. NTP is plain UDP with no
  certificates, so it works on a clock-broken device — this is the channel that breaks
  the deadlock. Throttled to 6 h on healthy devices (pool.ntp.org etiquette), and never
  skipped when the clock has jumped backwards.
- On the **Device-Owner** fleet, `correctSystemClock()` now pushes the true time into
  the **system** clock (`setAutoTimeEnabled(false)` + `DevicePolicyManager.setTime()`,
  API 28+), so HTTPS recovers with no site visit. The stored offset resets to 0 on
  success so timestamps are not double-corrected.
- On non-owner devices the clock cannot be set by an app, so instead of the opaque
  trust-anchor exception the status now reads **"Device date/time is wrong — correct it
  in TV settings to restore playback"**, which is actionable by whoever is on site.

Files: `settings/NtpSyncManager.kt`, `settings/DevicePrefs.kt`,
`worker/PlanFetchWorker.kt`, `res/xml/device_admin.xml` (comment).

**Immediate remedy applied to the affected TV** (non-owner, so not self-healing):
```
adb -s <tv>:5555 shell settings put global ntp_server time.google.com
adb -s <tv>:5555 shell settings put global auto_time 0
adb -s <tv>:5555 shell settings put global auto_time 1
```

---

## Bug C — network transparently proxies and blocks this TV (actual cause of the outage)

**Found:** while testing whether an HTTP `Date` header could substitute for the blocked
NTP. A plain port-80 request from the TV came back:

```
HTTP/1.1 403 Forbidden
Date: Tue, 11 Aug 2026 06:35:53 GMT
Via: HTTP/1.1 forward.http.proxy:3128
```

The same request from the laptop, on the same subnet, moments later:

```
HTTP/1.0 308 Permanent Redirect
server: Vercel                       (no Via header — straight to origin)
```

So the network forces **this device** through a filtering proxy that returns 403 for
`wearealive.in`, while other clients reach the origin directly.

Ruled out as explanations:
- **Proxy configured on the device** — `http_proxy`, `global_http_proxy_host/port` and
  `global_proxy_pac_url` are all `null`.
- **DNS hijack** — TV and laptop both resolve `wearealive.in` to `216.198.79.1` (Vercel).

That leaves transparent, per-client interception at the router/AP — MAC- or
device-class-based filtering. It also explains the TLS symptom directly: an intercepting
proxy presents a certificate signed by a CA the TV does not trust, which surfaces as
exactly `CertPathValidatorException: Trust anchor for certification path not found`. A
merely-wrong clock more usually reports "certificate not yet valid"/"expired", so the
error string had been pointing at the proxy all along.

The same policy blocks **UDP 123**, which is why the clock drifted in the first place and
why the Bug B fix could not sync here. One network policy produced both symptoms.

**Confirmed by experiment.** The proxy and the drifted clock were genuinely
indistinguishable from the error text alone — Android's path building discards trust
anchors that are not valid at the device's believed time, so a badly wrong clock can
report "Trust anchor not found" too. To separate them the clock was corrected (see
below) and the fetch re-run:

```
TV clock      Tue Aug 11 12:42:38 IST 2026   (correct, verified against the laptop)
fetch_status  ERROR
fetch_message "This network is blocking the screen (proxy: HTTP/1.1 forward.http.proxy:3128)"
```

TLS still fails with a correct clock, so the proxy is intercepting **443**, not merely
filtering port 80. The network is the cause; the clock was a real but separate defect.

**Fixing the clock without physical access.** Internet UDP/123 is blocked but *LAN* UDP
is open (verified by a UDP round-trip between TV and laptop). So a minimal NTP server run
on a laptop on the same subnet, with the TV pointed at it, fixes the clock remotely:

```
sudo python3 ntp_server.py                      # laptop, serves 0.0.0.0:123
adb -s <tv>:5555 shell settings put global ntp_server 192.168.15.212
adb -s <tv>:5555 shell settings put global auto_time 0
adb -s <tv>:5555 shell settings put global auto_time 1
```

The panel corrected itself within ~25 s. Useful for any screen whose clock has reset on a
network that blocks NTP egress, and it does not require device-owner.

**Action:** network-side, not code. Whitelist the screen (or move it off the filtered
device group) on the router. Confirm by tethering the TV to a phone hotspot — if the plan
fetch succeeds there, the panel and the app are fine.

**Implication for the fleet:** store and venue networks routinely do this. A screen that
cannot reach the origin should say so plainly rather than surfacing a TLS exception, and
`Via:`/`403` in a plain-HTTP probe is a cheap, reliable way to detect and report
"this network is blocking us" as distinct from "the server is down".

---

## Operational notes from this session

- **Fleet audit worth doing:** any screen reporting a TLS/trust error is almost
  certainly clock drift, not a server problem. `ntp_server = null` is likely common
  across this OEM's panels, so healthy-looking devices may be one power cut away.
- **Testing OTA needs a LOW versionCode.** The test APK was built with
  `BUILD_NUMBER=9001`, which is *higher* than the server's CI build (~40), so
  `update.versionCode <= BuildConfig.VERSION_CODE` short-circuits and no update is ever
  offered — the prompt path cannot be exercised at all. Build with a versionCode below
  the server's release to test the update flow.
- **Two TVs on the bench.** `192.168.15.156` (Hisilicon Hi3751V350) belongs to a
  separate session; every command here was scoped with `-s 192.168.15.154:5555`, and no
  global adb command (`kill-server`, bare `disconnect`) was used.
- **`pool.ntp.org` is not a dependable single source.** Measured from this network, the
  pool hostname timed out while an individual address it had just resolved answered
  immediately — round-robin hands out dead volunteer servers. `NtpSyncManager` now tries
  `time.google.com` and `time.cloudflare.com` (stable anycast) before the pool, and walks
  every resolved address rather than only the first.
- **Never toggle wifi over adb-over-wifi.** `svc wifi disable` killed the debugging link
  mid-command (`error: closed`). Use `adb reboot` or the on-screen settings instead.
- **This TV cannot set its own clock.** `adb root` is refused ("cannot run as root in
  production builds") and `adb shell date` gives "Operation not permitted" — the shell
  user has no `SET_TIME`. On a non-device-owner panel the clock can only be fixed by a
  human in Settings, which is precisely the argument for device-owner provisioning.
- **Still to do: HTTP `Date` fallback.** UDP 123 is blocked on this network, so the Bug B
  fix cannot sync here — but the proxy's own 403 carried an accurate `Date` header. A
  plain-HTTP `Date` probe rides the TCP path that already works and would have recovered
  the clock even here. Worth adding; note it is unauthenticated, so it should only be
  trusted to get inside the certificate validity window, after which TLS/NTP can refine.

## On-device verification (Kodak SPPL_2K_RT41, versionCode 2)

Built with JDK 17 (CI parity), installed over adb, app restarted, prefs read back:

```
last_ntp_sync_ms = 1735790474906     time recovered despite UDP/123 being blocked
clock_offset_ms  = 50641130118       586 days — matches Jan 2 2025 -> Aug 11 2026 exactly
fetch_message    = "This network is blocking the screen
                    (proxy: HTTP/1.1 forward.http.proxy:3128)
                    — allow wearealive.in on the router"
```

Confirms end-to-end: the HTTP `Date` fallback recovers the true time where NTP cannot,
the drift is measured correctly, and the diagnostic names the interception rather than
the clock — the actionable one of the two, and the opposite of what the raw TLS
exception implied. The system clock itself stays wrong because this panel is not
device-owner, so `setTime()` is unavailable — intended behaviour, and the strongest
argument yet for device-owner provisioning.

Correcting the clock then exposed two more defects in the new code, both fixed and
re-verified (`clock_offset_ms` now settles at `0` on a healthy clock):

- **Stale offset after an external correction.** `syncIfNeeded` throttled to 6 h on the
  last sync time alone. When Android's own time service fixed the panel's clock, the
  stored 586-day offset stayed in effect for the rest of the window — every timestamp
  would have been 19 months in the future. A large stored offset is now never throttled,
  because it means either the clock is still wrong or our offset has gone stale, and both
  want re-checking.
- **Over-trusting the HTTP Date header.** With the clock correct, the proxy's `Date`
  still put the offset 391 s out — the proxy's own clock is ~6.5 min adrift. Believing an
  unauthenticated, second-granular header about a small difference makes a healthy clock
  worse. The HTTP source is now only believed when it says the clock is wrong by over an
  hour (rescue), and below that the offset is stored as 0 so the system clock wins.

Two further implementation bugs were found *by this test* and fixed:
- `NetworkProbe` first used `HttpURLConnection`. targetSdk 28+ denies cleartext, so the
  probe threw before sending a packet and silently returned null — on exactly the device
  it existed to diagnose. Rewritten over a raw socket, which keeps the app's TLS policy
  locked down (the alternative, a cleartext exemption in the network security config,
  would relax it for every code path to read one header).
- `NtpSyncManager` queried only `pool.ntp.org` by hostname; see the operational note.

## Status

- Bug A (OTA prompt loop): code complete, compiles clean, installed on the test panel.
  **Not exercised end-to-end** — the test APK's versionCode must be below the server's
  release for the update path to run at all (see operational notes).
- Bug B (clock self-heal) + HTTP `Date` fallback + interception diagnostic: complete,
  compiles clean, **verified on-device** (see above).
- Bug C (network): no code fix — router/network policy. Confirm with a phone hotspot.
- Nothing pushed to GitHub. Working tree is on `feature/kiosk-exit-hatch`, which also
  carries 2 unpushed commits from another session.
