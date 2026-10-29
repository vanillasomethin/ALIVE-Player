# Debloat an ALIVE screen

Turns off a TV maker's preloaded apps, ads and telemetry so the panel only runs the
ALIVE player. Based on the disable step of [cleanyourtv](https://github.com/Gathin23/cleanyourtv),
WITHOUT its launcher step: the ALIVE player is the home screen and kiosk, and a second
launcher would fight it.

- Disables with `pm disable-user --user 0`. Nothing is uninstalled; every run writes an undo file.
- `protected.txt` is never touched, whatever a profile says: the player, Play services (remote
  commands arrive over FCM), WebView, the package installer (OTA), Tailscale, text-to-speech
  (spoken offers), a fallback launcher, core Android and the SoC vendor's HDMI/picture services.
- After every run it checks the player is still the home screen and running, and that those
  services are enabled.

## Run it

From a laptop with `adb` (Android platform-tools). `<tv>` is the Tailscale address from
Admin → Screens → Remote (`100.x.y.z:port`), a LAN `ip:port`, or a USB serial.

```bash
tools/debloat/debloat.sh discover <tv> kodak          # what is on this TV; changes nothing
tools/debloat/debloat.sh apply    <tv> kodak          # dry run: what would be disabled
tools/debloat/debloat.sh apply    <tv> kodak --yes    # do it, writes backups/<tv>-<time>/undo.txt
tools/debloat/debloat.sh restore  <tv> tools/debloat/backups/<tv>-<time>/undo.txt
```

## Profiles: one per TV model

`profiles/common.txt` applies to every TV (streaming apps, Google TV recommendations,
screensavers, Chromecast). On top of it, `profiles/<model>.txt` — `kodak`, `hisilicon`,
`generic` — holds that model's own brand bloat. The model files start empty on purpose:
OEM package names differ per firmware, so they are filled from a real TV, not guessed.

1. On ONE bench TV of the model, run `discover`. Lines marked `*` look like ads, telemetry,
   app stores or push services.
2. Add the ones you are sure of to `profiles/<model>.txt`, `apply --yes` on the bench TV, and
   let it run a full day (playback, OTA check, screenshot from the console).
3. Then apply the same profile to the rest of that model, and commit the profile.

The `apply` also makes the ALIVE player the TV's home screen (`cmd package set-home-activity`), so the
TV boots straight into ALIVE instead of the maker's launcher; `restore` sets the old one back.

Re-run `apply` after a TV's firmware update: firmware updates can switch disabled apps back on.
