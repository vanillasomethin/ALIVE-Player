# Device Owner Provisioning (Zero-Touch QR)

Required once per device to unlock the two reliability fixes that need
Device Owner status: silent HOME claim (boots straight into the player,
immune to OEM autostart/battery-kill restrictions) and silent OTA installs
(no confirm dialog, API 31+).

This is a **factory-reset operation** — existing fleet devices must go
through this once. New devices should be enrolled at first setup, before
any other app/account is added.

## 1. Generate the provisioning QR code

Device Owner via QR requires the APK to be reachable as a plain HTTPS
download with a known SHA-256 checksum of the **signed APK as downloaded**
(not the SHA-256 of the keystore cert — see step 2).

```bash
# SHA-256 of the APK file itself, base64url-encoded (no padding) — this is the
# checksum format QR provisioning expects, NOT a hex digest.
openssl dgst -binary -sha256 alive-player-release.apk | openssl base64 | tr '+/' '-_' | tr -d '='
```

QR payload (JSON):

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "com.alive.player/com.alive.player.admin.AliveDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
    "https://media.wearealive.in/releases/alive-player-release.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
    "<base64url SHA-256 of the APK, from the command above>",
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
   `dpm set-device-owner com.alive.player/.admin.AliveDeviceAdminReceiver`
   with the APK pre-installed via `adb install` — Fire TV doesn't expose the
   standard QR provisioning UI.
4. Scan the QR code (camera-based setup) or, for Fire TV / no-camera devices,
   run the ADB command directly:
   ```bash
   adb install alive-player-release.apk
   adb shell dpm set-device-owner com.alive.player/.admin.AliveDeviceAdminReceiver
   ```
   This only succeeds on a device with no accounts/apps configured yet
   (same factory-reset requirement).
5. Player launches automatically post-provisioning and silently claims HOME
   (`OwnerSetup.onDeviceOwnerReady`, triggered by `PROFILE_PROVISIONING_COMPLETE`).

## 3. Verify enrollment

```bash
adb shell dpm list-owners
# Expect: Device Owner: ComponentInfo{com.alive.player/com.alive.player.admin.AliveDeviceAdminReceiver}
```

In-app: Settings → Diagnostics overlay (5-tap) should show "Device Owner: yes".

## Devices that can't be re-provisioned

Some already-deployed devices may be in active use and unreachable for a
factory reset in the short term. They keep working exactly as before —
HOME claim and silent OTA simply stay unavailable on those units (LAUNCHER
icon entry + manual sideload), with `OemAutostartHelper` as a manual fallback
during any future re-setup. Prioritize re-provisioning during routine
maintenance visits rather than as an emergency rollout.
