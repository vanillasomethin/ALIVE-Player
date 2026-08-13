#!/usr/bin/env bash
# ALIVE Player field diagnosis capture.
# Run on a laptop with adb, connected (USB or `adb connect <ip>`) to a misbehaving box.
# Produces a timestamped folder of raw + pre-filtered evidence to attach to a debugging
# session. Read docs/instability-audit.md for how each section maps to a failure category.
set -uo pipefail

PKG="com.alive.player"
OUT="alive-diagnosis-$(date +%Y%m%d-%H%M%S)"

if ! command -v adb >/dev/null; then
  echo "adb not found — install Android platform-tools first." >&2
  exit 1
fi
if ! adb get-state >/dev/null 2>&1; then
  echo "No device connected. Use USB or: adb connect <box-ip>:5555" >&2
  exit 1
fi

mkdir -p "$OUT"
echo "Capturing to $OUT/ ..."

# ── Device identity + uptime (Category 2/3: OEM model, reboot detection) ──────
{
  echo "== identity =="
  adb shell getprop ro.product.manufacturer
  adb shell getprop ro.product.model
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.display.id
  echo "== uptime (low value near incident time = reboot happened) =="
  adb shell uptime
  echo "== date on device vs local =="
  adb shell date; date -u
} > "$OUT/device-info.txt" 2>&1

# ── App install state (Category 2: owner? exempt? version?) ───────────────────
{
  echo "== version =="
  adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"
  echo "== device owner (strongest OEM-kill protection when set) =="
  adb shell dumpsys device_policy | grep -A3 -i "device owner"
  echo "== battery-optimization whitelist =="
  adb shell dumpsys deviceidle whitelist | grep -i alive
  echo "== standby bucket (10=active best, 40+=restricted bad) =="
  adb shell am get-standby-bucket "$PKG" 2>/dev/null
  echo "== foreground services currently running =="
  adb shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground|startForegroundCount" | head -20
} > "$OUT/app-state.txt" 2>&1

# ── Full logcat dump (raw evidence) ───────────────────────────────────────────
adb logcat -d -v threadtime > "$OUT/logcat-full.txt" 2>&1

# ── Pre-filtered views (one file per failure category) ────────────────────────
grep -E "AndroidRuntime|FATAL EXCEPTION"            "$OUT/logcat-full.txt" > "$OUT/filter-1-crashes.txt"
grep -E "ActivityManager.*(Killing|died|ANR |Force stopping|Process $PKG)" \
                                                    "$OUT/logcat-full.txt" > "$OUT/filter-2-kills.txt"
grep -iE "lowmemorykiller|lmkd|onTrimMemory|OutOfMemory|oom" \
                                                    "$OUT/logcat-full.txt" > "$OUT/filter-3-memory.txt"
grep -E "MediaCodec|OMX|ExoPlayer|PlaybackEngine|AudioTrack|SurfaceFlinger.*alive" \
                                                    "$OUT/logcat-full.txt" > "$OUT/filter-4-media.txt"
grep -E "$PKG|alive\.player|AlivePlayer|PlanFetch|Heartbeat|Watchdog|BootReceiver" \
                                                    "$OUT/logcat-full.txt" > "$OUT/filter-5-app.txt"

# ── Storage (full cache volume silently truncates downloads) ──────────────────
adb shell df -h > "$OUT/storage.txt" 2>&1

# ── Recent ANR/tombstone traces if world-readable (varies by OEM) ─────────────
adb shell "ls -lt /data/anr 2>/dev/null | head -5" > "$OUT/anr-list.txt" 2>&1

echo
echo "Done. Quick triage:"
for f in filter-1-crashes filter-2-kills filter-3-memory filter-4-media; do
  n=$(wc -l < "$OUT/$f.txt")
  echo "  $f: $n lines"
done
echo
echo "Attach the whole '$OUT' folder to the debugging session."
