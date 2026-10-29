#!/usr/bin/env bash
# Debloat an ALIVE screen: disable the TV maker's preloaded apps, ads and telemetry,
# and never touch what the player needs (tools/debloat/protected.txt).
#
#   debloat.sh discover <tv> <model>          list what is left to look at (changes nothing)
#   debloat.sh apply    <tv> <model> [--yes]  disable common.txt + <model>.txt (dry run without --yes)
#   debloat.sh restore  <tv> <undo-file>      re-enable everything one apply run disabled
#
# <tv> is an adb target: the Tailscale address shown in Admin → Screens → Remote
# (100.x.y.z:port), a LAN ip:port, or a USB serial. <model> names profiles/<model>.txt.
#
# Disabling is `pm disable-user --user 0`: nothing is uninstalled, a firmware reset or
# `restore` brings everything back. cleanyourtv's launcher step is deliberately absent:
# the ALIVE player IS the home screen, and a second launcher would fight its kiosk.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLAYER=in.wearealive.player
PLAYER_HOME=$PLAYER/com.alive.player.ui.PairingActivity   # the activity with the HOME filter
SUSPECT='(^|[._])(ads?|adservice|advert|analytics|telemetry|tracking|tracker|stats|report|recommend|promo|store|market|appstore|feedback|survey|push|ota|smartcast|cloud)([._]|$)'

die() { echo "error: $*" >&2; exit 1; }

# Strip comments and blanks: one package name (or protect pattern) per line.
read_list() { [ -f "$1" ] && sed -e 's/#.*//' -e 's/[[:space:]]//g' "$1" | grep -v '^$' || true; }

is_protected() {
  local pkg=$1 pat
  while IFS= read -r pat; do
    case "$pat" in
      *\*) [[ $pkg == "${pat%\*}"* ]] && return 0 ;;
      *)   [[ $pkg == "$pat" ]] && return 0 ;;
    esac
  done < <(read_list "$HERE/protected.txt")
  return 1
}

connect() {
  TV=$1
  if [[ $TV == *:* ]]; then adb connect "$TV" >/dev/null 2>&1 || true; fi
  local state
  state=$(adb -s "$TV" get-state 2>/dev/null || true)
  [ "$state" = device ] || die "cannot reach $TV over adb (state: ${state:-none}). Is ADB on and this computer authorised / paired?"
  MODEL_INFO="$(adb -s "$TV" shell getprop ro.product.manufacturer | tr -d '\r') $(adb -s "$TV" shell getprop ro.product.model | tr -d '\r') / $(adb -s "$TV" shell getprop ro.hardware | tr -d '\r')"
  echo "Connected: $TV  ($MODEL_INFO)"
}

# An empty list (nothing disabled yet) is a normal answer, never a reason to stop.
pkgs() { { adb -s "$TV" shell pm list packages "$@" || true; } | tr -d '\r' | sed 's/^package://' | sort; }

profile_for() {
  local model=$1
  [ -f "$HERE/profiles/$model.txt" ] || die "no profile tools/debloat/profiles/$model.txt (have: $(ls "$HERE/profiles" | sed 's/\.txt$//' | tr '\n' ' '))"
  { read_list "$HERE/profiles/common.txt"; read_list "$HERE/profiles/$model.txt"; } | sort -u
}

backup() {
  BACKUP_DIR="$HERE/backups/$(echo "$TV" | tr ':/' '__')-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$BACKUP_DIR"
  pkgs        > "$BACKUP_DIR/all.txt"
  pkgs -d     > "$BACKUP_DIR/disabled-before.txt"
  echo "$MODEL_INFO" > "$BACKUP_DIR/tv.txt"
}

home_now() {
  adb -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | tail -1
}

# Make the player the TV's home screen, so it boots straight into ALIVE instead of the
# maker's launcher (and its logo) first. The stock launcher stays installed as the
# fallback; the previous home is kept beside the undo file so restore can put it back.
make_player_home() {
  local before; before=$(home_now)
  if [[ $before == $PLAYER/* ]]; then echo "  home screen is already the ALIVE player"; return; fi
  echo "${before:-}" > "$BACKUP_DIR/home-before.txt"
  local out; out=$(adb -s "$TV" shell cmd package set-home-activity "$PLAYER_HOME" 2>&1 || true)
  if [[ $(home_now) == $PLAYER/* ]]; then echo "  home screen set to the ALIVE player (was ${before:-none})"
  else echo "  FAILED to set the home screen (${out//$'\r'/}); this firmware may not allow it without device owner"; fi
}

# Lets the console's "Turn ADB on" switch Wireless debugging back on after a reboot
# (RemoteAccess.enableAdb). Only grantable over ADB; the app can never ask for it.
grant_adb_switch() {
  if adb -s "$TV" shell pm grant "$PLAYER" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; then
    echo "  granted the player permission to switch ADB back on after a reboot"
  else
    echo "  FAILED to grant WRITE_SECURE_SETTINGS (player older than the remote-access build?)"
  fi
}

# The checks that matter after any change: the player is home, alive, and the services
# its remote features and OTA ride on are still enabled.
health() {
  local ok=1 home p disabled
  home=$(home_now)
  if [[ $home == $PLAYER/* ]]; then echo "  ok   home screen is the ALIVE player"
  else echo "  WARN home screen resolves to '${home:-nothing}', not the ALIVE player"; ok=0; fi
  if adb -s "$TV" shell pidof "$PLAYER" >/dev/null 2>&1; then echo "  ok   player is running"
  else echo "  WARN player is not running"; ok=0; fi
  disabled=$(pkgs -d)
  for p in com.google.android.gms com.google.android.webview com.android.packageinstaller com.google.android.packageinstaller com.tailscale.ipn; do
    if grep -qx "$p" <<<"$disabled"; then echo "  WARN $p is DISABLED"; ok=0; fi
  done
  [ $ok = 1 ] && echo "  ok   Play services, WebView, package installer and Tailscale are enabled"
  return 0
}

cmd_discover() {
  local model=$1 enabled disabled profile pkg n=0
  enabled=$(pkgs -e); disabled=$(pkgs -d)
  profile=$(profile_for "$model")
  echo
  echo "Enabled packages not protected and not yet in common.txt or $model.txt:"
  echo "(* = name looks like ads, telemetry, an app store or push. Check what each is"
  echo "      before adding it to profiles/$model.txt.)"
  while IFS= read -r pkg; do
    is_protected "$pkg" && continue
    grep -qx "$pkg" <<<"$profile" && continue
    if [[ $pkg =~ $SUSPECT ]]; then echo "  * $pkg"; else echo "    $pkg"; fi
    n=$((n + 1))
  done <<<"$enabled"
  echo "$n package(s). Already handled by the profile:"
  while IFS= read -r pkg; do
    [ -n "$pkg" ] || continue
    if grep -qx "$pkg" <<<"$disabled"; then echo "    $pkg (already disabled)"
    elif grep -qx "$pkg" <<<"$enabled"; then echo "    $pkg (enabled, would be disabled)"; fi
  done <<<"$profile"
  echo
  echo "Health:"; health
}

cmd_apply() {
  local model=$1 yes=${2:-} enabled profile pkg todo=() undo out
  enabled=$(pkgs -e)
  profile=$(profile_for "$model")
  while IFS= read -r pkg; do
    [ -n "$pkg" ] || continue
    if is_protected "$pkg"; then echo "  skip $pkg (protected, ignoring the profile line)"; continue; fi
    grep -qx "$pkg" <<<"$enabled" && todo+=("$pkg")
  done <<<"$profile"

  if [ ${#todo[@]} -eq 0 ]; then echo "Nothing to disable: every profile package is absent or already disabled."
  else echo "Will disable ${#todo[@]} package(s):"; printf '  %s\n' "${todo[@]}"; fi
  [[ $(home_now) == $PLAYER/* ]] || echo "Will make the ALIVE player the home screen (now: $(home_now))."
  if [ "$yes" != --yes ]; then echo; echo "Dry run. Re-run with --yes to apply."; return; fi

  backup
  make_player_home
  grant_adb_switch
  undo="$BACKUP_DIR/undo.txt"
  : > "$undo"
  for pkg in "${todo[@]}"; do
    out=$(adb -s "$TV" shell pm disable-user --user 0 "$pkg" 2>&1 || true)
    if [[ $out == *"new state: disabled"* ]]; then
      echo "$pkg" >> "$undo"; echo "  disabled $pkg"
    else
      echo "  FAILED   $pkg (left as it was)"
    fi
  done
  echo
  echo "Undo file: $undo"
  echo "  restore with: $0 restore $TV $undo"
  echo
  echo "Health:"; health
}

cmd_restore() {
  local undo=$1 pkg
  [ -f "$undo" ] || die "no such undo file: $undo"
  while IFS= read -r pkg; do
    [ -n "$pkg" ] || continue
    adb -s "$TV" shell pm enable --user 0 "$pkg" >/dev/null 2>&1 && echo "  enabled $pkg" || echo "  FAILED  $pkg"
  done < "$undo"
  local prev; prev=$(cat "$(dirname "$undo")/home-before.txt" 2>/dev/null || true)
  if [[ -n $prev && $prev == */* ]]; then
    adb -s "$TV" shell cmd package set-home-activity "$prev" >/dev/null 2>&1 && echo "  home screen set back to $prev" || echo "  FAILED to set home back to $prev"
  fi
  echo; echo "Health:"; health
}

[ $# -ge 3 ] || { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
command -v adb >/dev/null || die "adb not found (install Android platform-tools)"
action=$1; target=$2; arg=$3; shift 3
case "$action" in
  discover) connect "$target"; cmd_discover "$arg" ;;
  apply)    connect "$target"; cmd_apply "$arg" "${1:-}" ;;
  restore)  connect "$target"; cmd_restore "$arg" ;;
  *) die "unknown action '$action' (discover | apply | restore)" ;;
esac
