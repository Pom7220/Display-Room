#!/usr/bin/env bash
#
# Disable MEET IN TOUCH (me.exzy.meetingroom) across the fleet — runbook section 9.
#
# WHY THIS MATTERS
# me.exzy.meetingroom registers Device Admin with the force-lock policy. It wakes on a
# ~25-minute timer, fails to launch its own MainActivity, times out, then calls lockNow()
# and puts the screen to sleep. The kiosk watchdog wakes the screen and relaunches the
# WebView, which the web app reports as a kiosk_relaunch incident. Confirmed from logcat
# on Viennese 2026-09-23: 11 "Going to sleep due to device administration policy" events
# in one morning. Doppio, which has the package but no Device Admin, produced zero —
# so it is the admin privilege that does the damage, not the app's presence.
#
# Safe to run any time. Idempotent: already-disabled and not-installed tablets are
# skipped, and only this one package is touched. Verifies by re-reading package state
# rather than trusting the command's exit code.
#
# USAGE — Git Bash, NOT PowerShell (the for-loop syntax fails silently in PowerShell):
#   bash scripts/disable-meetintouch.sh --dry-run     # show what would change
#   bash scripts/disable-meetintouch.sh               # whole fleet
#   bash scripts/disable-meetintouch.sh 10.0.54.107   # one or more specific IPs
#
# No reboot is issued. pm disable force-stops the package immediately, so the lockNow()
# calls cease at once; the scheduled 06:00 cold reboot clears any residual state.

set -u
ADB="${ADB:-/c/TEMP/platform-tools/adb.exe}"
PKG="me.exzy.meetingroom"

# IP:Name:Platform — platform lg = Android 4.4 (needs su), a10 = Android 10 (no su).
FLEET="
10.0.54.101:Doppio:lg
10.0.54.102:Cappuccino:lg
10.0.54.103:Americano:lg
10.0.54.104:Lungo:lg
10.0.54.105:Ristretto:lg
10.0.54.106:Macchiato:lg
10.0.54.107:Viennese:lg
10.0.54.108:Decaffinato:lg
10.0.54.109:Latte:a10
10.0.54.110:Mocha:lg
10.0.54.111:Affogato:lg
10.0.54.112:Espresso:lg
"

DRY=0
ARGS=()
for a in "$@"; do
  if [ "$a" = "--dry-run" ]; then DRY=1; else ARGS+=("$a"); fi
done

TARGETS=""
if [ ${#ARGS[@]} -gt 0 ]; then
  for want in "${ARGS[@]}"; do
    row=$(echo "$FLEET" | grep "^$want:")
    if [ -z "$row" ]; then echo "Unknown IP: $want" >&2; exit 2; fi
    TARGETS="$TARGETS $row"
  done
else
  TARGETS=$(echo "$FLEET" | grep -v '^$')
fi

pkgstate() {  # $1=ip -> enabled | disabled | absent
  local ip="$1"
  if MSYS_NO_PATHCONV=1 "$ADB" -s "$ip:5555" shell "pm list packages -d $PKG" 2>/dev/null | grep -q "$PKG"; then
    echo disabled; return
  fi
  if MSYS_NO_PATHCONV=1 "$ADB" -s "$ip:5555" shell "pm list packages -e $PKG" 2>/dev/null | grep -q "$PKG"; then
    echo enabled; return
  fi
  echo absent
}

echo "MEET IN TOUCH ($PKG) — runbook section 9"
[ $DRY -eq 1 ] && echo "*** DRY RUN — nothing will be changed ***"
echo
printf "%-13s %-13s %-9s %-9s %s\n" ROOM IP BEFORE AFTER RESULT
printf -- "------------------------------------------------------------------\n"

CHANGED=0; SKIPPED=0; FAILED=""
for ENTRY in $TARGETS; do
  IP=$(echo "$ENTRY" | cut -d: -f1)
  NAME=$(echo "$ENTRY" | cut -d: -f2)
  PLAT=$(echo "$ENTRY" | cut -d: -f3)

  MSYS_NO_PATHCONV=1 "$ADB" connect "$IP:5555" >/dev/null 2>&1
  STATE=$(MSYS_NO_PATHCONV=1 "$ADB" devices 2>/dev/null | grep "^$IP:5555" | awk '{print $2}' | tr -d '\r')
  if [ "$STATE" != "device" ]; then
    # A stale entry reports offline; disconnect and retry once before giving up.
    MSYS_NO_PATHCONV=1 "$ADB" disconnect "$IP:5555" >/dev/null 2>&1; sleep 1
    MSYS_NO_PATHCONV=1 "$ADB" connect "$IP:5555" >/dev/null 2>&1
    STATE=$(MSYS_NO_PATHCONV=1 "$ADB" devices 2>/dev/null | grep "^$IP:5555" | awk '{print $2}' | tr -d '\r')
  fi
  if [ "$STATE" != "device" ]; then
    printf "%-13s %-13s %-9s %-9s %s\n" "$NAME" "$IP" "-" "-" "UNREACHABLE (${STATE:-no route})"
    FAILED="$FAILED $NAME"
    continue
  fi

  BEFORE=$(pkgstate "$IP")
  if [ "$BEFORE" != "enabled" ]; then
    printf "%-13s %-13s %-9s %-9s %s\n" "$NAME" "$IP" "$BEFORE" "$BEFORE" "skipped"
    SKIPPED=$((SKIPPED+1)); continue
  fi
  if [ $DRY -eq 1 ]; then
    printf "%-13s %-13s %-9s %-9s %s\n" "$NAME" "$IP" "$BEFORE" "disabled" "would change"
    CHANGED=$((CHANGED+1)); continue
  fi

  if [ "$PLAT" = "a10" ]; then
    MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell "pm disable-user --user 0 $PKG" >/dev/null 2>&1
  else
    MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell "su -c 'pm disable $PKG'" >/dev/null 2>&1
  fi
  sleep 2

  AFTER=$(pkgstate "$IP")
  if [ "$AFTER" = "disabled" ]; then
    printf "%-13s %-13s %-9s %-9s %s\n" "$NAME" "$IP" "$BEFORE" "$AFTER" "OK"
    CHANGED=$((CHANGED+1))
  else
    printf "%-13s %-13s %-9s %-9s %s\n" "$NAME" "$IP" "$BEFORE" "$AFTER" "FAILED"
    FAILED="$FAILED $NAME"
  fi
done

echo
if [ $DRY -eq 1 ]; then
  echo "Dry run complete — nothing changed. $CHANGED would be disabled, $SKIPPED already correct."
  echo "Re-run without --dry-run to apply."
  exit 0
fi
echo "Disabled: $CHANGED   Already correct: $SKIPPED"
if [ -n "$FAILED" ]; then
  echo "NEEDS ATTENTION:$FAILED"
  echo "A tablet reporting offline while its display works is usually a wedged adbd:"
  echo "on the tablet, Settings > Developer Options, toggle USB debugging off then on."
  exit 1
fi
echo "Fleet matches runbook section 9."
