# Device Owner Provisioning (Zero-Touch QR)

Required once per device to unlock the three reliability fixes that need
Device Owner status: silent HOME claim (boots straight into the player,
immune to OEM autostart/battery-kill restrictions), silent OTA installs
(no confirm dialog, API 31+), and always-on Tailscale, which is the only
way a screen comes back onto the tailnet by itself after a power cut —
Android will not start a VPN at boot otherwise, and these panels ship with
no VPN settings screen to turn it on by hand.

This is a **factory-reset operation** — existing fleet devices must go
through this once. New devices should be enrolled at first setup, before
any other app/account is added.

## 0. Before you reset a screen that is already in the field

A reset changes the player's `ANDROID_ID`, because that value is scoped per
app signing key and per device. The player claims on `ANDROID_ID`
(`PairingActivity`), so the TV comes back as a **new `Device` row**, and
everything the old row had learned is left behind on it:

- **`renditionTier` resets to the `HEVC` default.** This is the one that
  bites. A panel that had been stepped down to `H264_BASELINE` after real
  decode failures is immediately offered HEVC again, fails, and shows
  nothing — a working screen goes dark on enrolment. Record the tier first
  and put it back on the new row. (2026-10-31, AH Store: reinstalling cost
  the screen its `H264_BASELINE` and it stopped playing video entirely.)
- **Pairing, and the old row itself.** The old row survives, still linked to
  the store, and goes stale — it will raise offline alerts for a screen that
  no longer exists. Retire it once the new row is paired and playing.

So for each screen, before the reset: note `renditionTier`, `storeId` and
the device name; after it: re-pair, restore the tier, confirm playback is
advancing (`playbackAliveAt`, not just `lastSeen`), then retire the old row.

## 1. Generate the provisioning QR code

Device Owner via QR requires the APK to be reachable as a plain HTTPS
download with a known SHA-256 checksum of the **signed APK as downloaded**
(not the SHA-256 of the keystore cert — see step 2).

**The download host must not sit behind a bot challenge.** Android's
provisioning downloader is not a browser: it cannot solve a CAPTCHA or a
Cloudflare interstitial, and a challenged host fails the enrolment *after*
the device has been wiped, which is the worst moment to find out.
`media.wearealive.in` is challenged site-wide today (`403`,
`cf-mitigated: challenge`) and must not be used until a bypass rule exists
for the release path. Check any candidate host with a plain, non-browser
client before trusting it to a factory-reset TV:

```bash
# Expect 200/206 and a body starting with the ZIP magic "PK\x03\x04".
curl -sIL -A 'Dalvik/2.1.0 (Linux; U; Android 11)' -o /dev/null \
  -w '%{http_code}\n' "$APK_URL"
```

Both the URL and the checksum are **per build** — the checksum changes with
every release, so regenerate the QR whenever the APK changes:

```bash
# SHA-256 of the APK file itself, base64url-encoded (no padding) — this is the
# checksum format QR provisioning expects, NOT a hex digest.
openssl dgst -binary -sha256 alive-player-release.apk | openssl base64 | tr '+/' '-_' | tr -d '='
```

QR payload (JSON):

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "in.wearealive.player/com.alive.player.admin.AliveDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
    "https://github.com/vanillasomethin/ALIVE-Player/releases/download/sideload-latest/alive-player-<versionCode>.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
    "<base64url SHA-256 of THAT build's APK, from the command above>",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
  "android.app.extra.PROVISIONING_LOCALE": "en_IN",
  "android.app.extra.PROVISIONING_WIFI_SSID": "<store wifi SSID, optional>",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "<store wifi password, optional>"
}
```

Render this JSON as a QR code (any QR generator — e.g. `qrencode -o setup.png "$(cat payload.json)"`).

## 2. Enroll the device

1. Factory reset the device (Settings → reset, or wipe via recovery on Fire TV).
2. On the Android TV/Fire TV "Welcome" setup screen, navigate to the network
   step and connect to Wi-Fi (or skip if pre-filled via the QR payload above).
3. On most Android TV setup flows: tap the screen **6 times** in the same spot
   on the welcome screen to enter QR provisioning mode (same gesture as phone
   zero-touch enrollment). On Fire TV this may instead require `adb shell`
   `dpm set-device-owner in.wearealive.player/com.alive.player.admin.AliveDeviceAdminReceiver`
   with the APK pre-installed via `adb install` — Fire TV doesn't expose the
   standard QR provisioning UI.
4. Scan the QR code (camera-based setup) or, for Fire TV / no-camera devices,
   run the ADB command directly:
   ```bash
   adb install alive-player-release.apk
   adb shell dpm set-device-owner in.wearealive.player/com.alive.player.admin.AliveDeviceAdminReceiver
   ```
   This only succeeds on a device with no accounts/apps configured yet
   (same factory-reset requirement).
5. Player launches automatically post-provisioning and silently claims HOME
   (`OwnerSetup.onDeviceOwnerReady`, triggered by `PROFILE_PROVISIONING_COMPLETE`).

## 3. Verify enrollment

```bash
adb shell dpm list-owners
# Expect: Device Owner: ComponentInfo{in.wearealive.player/com.alive.player.admin.AliveDeviceAdminReceiver}
```

In-app: Settings → Diagnostics overlay (5-tap) should show "Device Owner: yes".

## Devices that can't be re-provisioned

Some already-deployed devices may be in active use and unreachable for a
factory reset in the short term. They keep working exactly as before —
HOME claim and silent OTA simply stay unavailable on those units (LAUNCHER
icon entry + manual sideload), with `OemAutostartHelper` as a manual fallback
during any future re-setup. Prioritize re-provisioning during routine
maintenance visits rather than as an emergency rollout.
