# Dashboard Incident Report Update for v5.100

**Date:** 2026-09-16  
**Status:** Implemented — deployed in v5.100 commit e88ffe7  
**Related spec:** [2026-09-15-unified-daily-reboot-design.md](2026-09-15-unified-daily-reboot-design.md)

---

## Problem

With v5.100 introducing a daily cold reboot at 06:00 BKK, the existing dashboard incident logic needed two updates:

1. The 06:00 cold reboot is **expected and scheduled** — it must not appear as an `unexpected_reboot` incident.
2. The alarm chain chips on each room card showed `🔄 restart` at 06:00 — this must change to `❄️ cold_boot` since the action is now a cold reboot, not a soft process restart.
3. The old 07:30 BKK exception window (for MEET IN TOUCH reboots via LGKioskMode) is now redundant — eliminated by v5.100's `pm clear` in BootReceiver.

---

## Design

### New alarm event: `cold_boot`

**BootReceiver.java** posts `cold_boot` to `POST /api/alarm` on every `BOOT_COMPLETED`, immediately after the `pm disable` + `pm clear` block. This replaces the `restart` event previously posted by ScheduleReceiver's `ACTION_RESTART` handler.

- The `restart` / `restart_weekend` posts are removed from `ACTION_RESTART` — the cold reboot kills the process before a post could reliably complete anyway.
- `cold_boot` is posted by BootReceiver which runs on every boot, making it the authoritative signal.
- Net KV write count unchanged: `cold_boot` replaces `restart` (still 3 writes/tablet/day).

### Dashboard chip: ❄️

**dashboard.html** `_alarmIcons` map gains `cold_boot: '❄️'`.

Daily alarm chain on room cards:

| Old (v5.99) | New (v5.100) |
|-------------|--------------|
| `🔄 06:00 → ☀️ 07:00 → 🌙 20:30` | `❄️ 06:01 → ☀️ 07:00 → 🌙 20:30` |

Weekends unchanged: no `cold_boot` (ScheduleReceiver skips ACTION_RESTART on weekends).

### Incident suppression window

**index.html** `reportIncident('unexpected_reboot', ...)` — time-window check:

- **Keep**: 06:00 BKK window → 23:00 UTC ± 15 min (UTC min 1365–1395 = 05:45–06:15 BKK). Reboots in this window are logged as expected, no incident created.
- **Remove**: 07:30 BKK window (UTC min 30 ± 15/20). Was for LGKioskMode-driven reboots from MEET IN TOUCH — eliminated by `pm clear` in BootReceiver.

Any cold reboot outside 05:45–06:15 BKK on a weekday → `unexpected_reboot` incident, stays OPEN for admin review.

### Device Admin receiver

**BootLauncherDeviceAdminReceiver.java** (new) — empty `DeviceAdminReceiver` subclass declared in `AndroidManifest.xml`. Enables `DevicePolicyManager.reboot()` on Latte (Android 10, API 29) once Device Admin is activated.

**One-time activation per tablet (Latte only):**  
Settings → Security → Device Administrators → Boot Launcher → Activate

LG tablets (Android 4.4) fall back to `su -c reboot` — no Device Admin activation needed.

---

## Build Process

No Java / Android Studio on local machine. All APK builds run via CI (GitHub Actions):

1. Push code changes to `main`
2. CI workflow builds `assembleRelease`, signs the APK, commits `ris-boot-launcher.apk` back to the repo
3. Pull the updated commit before next push to avoid non-fast-forward rejection (`git pull --rebase`)

---

## Files Changed

| File | Change |
|------|--------|
| `BootReceiver.java` | Add `ScheduleReceiver.logAlarmEvent(context, "cold_boot")` after pm clear block |
| `ScheduleReceiver.java` | ACTION_RESTART: remove `logAlarmEventSync("restart")`, add DPM/su cold reboot |
| `BootLauncherDeviceAdminReceiver.java` | New — empty DeviceAdminReceiver subclass |
| `AndroidManifest.xml` | Add BootLauncherDeviceAdminReceiver with BIND_DEVICE_ADMIN + device_admin meta-data |
| `res/xml/device_admin.xml` | New — empty device-admin policy declaration |
| `build.gradle` | versionCode 600, versionName "5.100" |
| `dashboard.html` | Add `cold_boot: '❄️'` to `_alarmIcons` map |
| `index.html` | Remove 07:30 BKK window; keep 06:00 BKK window; update comments |

---

## Monitoring (first 06:00 cycle, 2026-09-17)

| Time (BKK) | Expected dashboard signal |
|------------|--------------------------|
| 06:00–06:05 | All tablets go offline briefly (cold reboot) |
| 06:01–06:05 | `cold_boot` KV write — ❄️ chip appears on room cards |
| 07:00 | `wake` — ☀️ chip, WebView launches |
| 08:00 | No reboot (MEET IN TOUCH neutralised by pm clear) |
| No `unexpected_reboot` incidents | Expected — 06:00 window suppresses them |
