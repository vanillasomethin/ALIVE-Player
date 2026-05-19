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
