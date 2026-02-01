# Device Install Guide

This guide describes how to install the Android TV player on a device and complete the pairing flow.

## Installation

1. Build the debug APK (or use the release APK provided by CI).
   ```bash
   ./gradlew clean assembleDebug
   ```
2. Install the APK on the device.
   ```bash
   ./gradlew installDebug
   ```
3. Launch the app from the Android TV home screen.

## Pairing flow

On first launch the player shows:

- Claim code (6–8 alphanumeric characters).
- Device model, Android version, app version, locale/timezone.

### Claim exchange

1. The device requests a claim code from the backend (`POST /device/claim` or `POST /pair/claim`).
2. The UI shows the claim code so an operator can associate the device.
3. The device polls or uses a “Continue” action to exchange the code:
   `POST /device/claim/exchange` → returns `device_token` and `device_id`.

### Storage

Store pairing data in `EncryptedSharedPreferences`:

- `device_token`
- `device_id`
- `paired_at`

## Reset device

From Settings → Reset device:

- Clear encrypted preferences.
- Clear Room tables (plan cache, downloads, assets, events).
- Delete cached assets folder.
- Restart the app to return to the pairing screen.
