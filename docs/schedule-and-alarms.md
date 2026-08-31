# RIS Kiosk — Schedule, Alarms & Health Check Reference

**Last updated:** 2026-08-31  
**Applies to:** APK v5.75+

---

## Overview

Each LG Android tablet runs three scheduled alarms plus a continuous health check watchdog. Together they manage the daily on/off cycle and self-recovery from crashes.

---

## The Three Alarms

### 1. 06:00 — ACTION_RESTART (Daily Maintenance)

**Purpose:** Forces a clean app restart each morning before business hours. Clears accumulated WebView memory and state from the previous day. Also catches tablets that may have crashed overnight by returning them to a known-good standby state. On weekdays, `UpdateChecker.silentInstall()` runs first (see [OTA Silent Update (06:00)](#ota-silent-update-0600) below).

| Day | Behaviour |
|-----|-----------|
| Monday–Friday | Runs `UpdateChecker.silentInstall()` → if update installs, app process is killed; `BootReceiver` fires → `StandbyActivity`. If no update, proceeds directly to `StandbyActivity` → tablet dims to near-black. After a successful install, `MY_PACKAGE_REPLACED` fires `RestartReceiver` which relaunches the kiosk. |
| Saturday–Sunday | Skips (logs `restart_weekend`) — tablet stays in standby from 20:30 Friday |

**Reschedules itself** for 06:00 the following day.

---

### 2. 07:30 — ACTION_WAKE (Business Hours Start)

**Purpose:** Launches the kiosk WebView to start the working day.

| Day | Behaviour |
|-----|-----------|
| Monday–Friday | Launches `KioskWebViewActivity` — tablet goes live |
| Saturday–Sunday | Skips (logs `wake_weekend`) — tablet stays in standby |

**Reschedules itself** for 07:30 the following day.

---

### 3. 20:30 — ACTION_STANDBY (End of Business Day)

**Purpose:** Puts the tablet to sleep at the end of the working day.

| Day | Behaviour |
|-----|-----------|
| All days | Launches `StandbyActivity` — tablet dims to near-black |

**Reschedules itself** for 20:30 the following day.

---

## Health Check Watchdog (Every 10 Minutes)

**Purpose:** Self-recovery safety net. If a tablet crashes mid-day, the health check detects it and relaunches the WebView within 10 minutes — without waiting for the next 07:30 alarm.

| Condition | Behaviour |
|-----------|-----------|
| Business hours (08:00–20:00) **AND** weekday **AND** WebView not running | Relaunches `KioskWebViewActivity` |
| Business hours **AND** weekday **AND** WebView already running (`sIsVisible = true`) | Skips — does nothing |
| Weekend (any hour) | Skips — does nothing (v5.57+) |
| Outside business hours (before 08:00 or after 20:00) | Skips — does nothing |

**Note:** The health check makes no HTTP calls itself — Conscrypt is not required in the health check handler. It only starts an Activity.

**Always reschedules itself** for 10 minutes later, regardless of whether it acted.

---

## Full Weekly Schedule

### Weekday (Monday–Friday)

```
20:30 (prev day)  ACTION_STANDBY   → StandbyActivity (screen dims)
      overnight   StandbyActivity sends heartbeat every 20 min (status=sleep)
06:00             ACTION_RESTART   → StandbyActivity restarts (fresh app state)
07:30             ACTION_WAKE      → KioskWebViewActivity (tablet goes live)
08:00 onwards     Health check     → fires every 10 min, skips (WebView running)
20:30             ACTION_STANDBY   → StandbyActivity (end of day)
```

### Weekend (Saturday–Sunday) — v5.57+

```
20:30 Friday      ACTION_STANDBY   → StandbyActivity (screen dims)
      overnight   StandbyActivity sends heartbeat every 20 min (status=sleep)
06:00 Sat/Sun     ACTION_RESTART   → skips (logs restart_weekend)
07:30 Sat/Sun     ACTION_WAKE      → skips (logs wake_weekend)
08:00–20:00       Health check     → fires every 10 min, skips (weekend guard)
                  Tablet stays in standby all weekend ✓
```

### Weekend — v5.55 / v5.56 (bug, now fixed)

```
06:00             ACTION_RESTART   → StandbyActivity (no weekend guard)
07:30             ACTION_WAKE      → skips (had guard)
08:03–08:09       Health check     → wakes tablets ❌ (no weekend guard)
                  unexpected_reboot incident logged per tablet
```

---

## Test Sleep (Admin Feature)

Allows the admin to test the full sleep/wake cycle from the dashboard without waiting for the 20:30 alarm.

**How to trigger:** Dashboard admin panel → **🌙 Test Sleep** button → sends `enable_test_sleep` command to all 6 tablets.

**Behaviour:**
- Tablet receives command via next `/api/command` poll (~90 seconds)
- **+2 minutes:** `ACTION_TEST_SLEEP` fires → `StandbyActivity` launches (tablet dims)
- **+10 minutes:** `ACTION_TEST_WAKE` fires → `KioskWebViewActivity` relaunches (tablet goes live)
- `test_sleep_enabled` flag is cleared in SharedPreferences after wake

**Request codes:** `10` = TEST_SLEEP, `11` = TEST_WAKE (separate from regular alarm codes to avoid conflicts).

---

## Conscrypt — TLS 1.2 on Android 4.4

Android 4.4 (LG tablets) ships with old OpenSSL that only supports TLS 1.0/1.1. Cloudflare Workers require TLS 1.2 minimum. Conscrypt replaces the system SSL provider with a modern TLS 1.2/1.3 stack.

| Component | Makes HTTPS calls | Conscrypt needed |
|-----------|------------------|-----------------|
| `KioskWebViewActivity` startup | Yes (heartbeat, calendar, commands) | ✓ Yes |
| `ScheduleReceiver` alarm handlers | Yes (logAlarmEvent, sendSleepHeartbeat) | ✓ Yes |
| `StandbyActivity` heartbeat thread | Yes (POST /api/heartbeat every 20 min) | ✓ Yes (added v5.57) |
| Health check handler | No | Not needed |

Conscrypt must be installed per-process. Each alarm fires in a fresh process and installs its own Conscrypt instance.

**Latte** (Android 10) supports TLS 1.2 natively but Conscrypt installation is harmless.

---

## Alarm Request Codes

| Code | Alarm |
|------|-------|
| 1 | ACTION_STANDBY |
| 2 | ACTION_WAKE |
| 3 | ACTION_RESTART |
| 4 | ACTION_HEALTH_CHECK |
| 10 | ACTION_TEST_SLEEP |
| 11 | ACTION_TEST_WAKE |

---

---

## OTA Silent Update (06:00)

Introduced in v5.72. At 06:00 on weekdays, `ScheduleReceiver` calls `UpdateChecker.silentInstall()` before launching `StandbyActivity`.

**Update flow:**

```
06:00  ACTION_RESTART fires (weekday)
       → UpdateChecker.silentInstall() checks /api/version
       → if remoteCode > localCode:
             download APK → su -c "pm install -r <path>"
             read pm install stdout to confirm success (Android 4.4: exit code alone unreliable)
             if success: app process killed by package manager
                         BootReceiver fires → StandbyActivity
                         MY_PACKAGE_REPLACED broadcast → RestartReceiver fires
                         RestartReceiver issues full-screen notification → KioskWebViewActivity relaunches
       → if no update: proceeds to StandbyActivity normally
07:30  ACTION_WAKE → KioskWebViewActivity (new version running)
```

**Key details:**
- `pm install` stdout is read and checked for "Success" or "Failure [REASON]" — on LG Android 4.4, `su` always exits 0 regardless of the actual pm install result (v5.73)
- `RestartReceiver` uses `setFullScreenIntent()` (full-screen notification) instead of `startActivity()` directly — required on Android 10 (Latte), where background activity launch from a killed-app receiver is blocked; the notification approach also works on Android 4.4 (v5.75)
- Network (FortiGate) may not be available before ~08:00–08:15 BKK — if `/api/version` or APK download fails at 06:00, the install is silently skipped and StandbyActivity proceeds normally. The next scheduled opportunity is the following weekday at 06:00 unless an admin-triggered update is issued from the dashboard.
- **Bootstrap problem:** if `silentInstall` is itself broken in the currently installed APK version (as in v5.68), the tablet cannot self-update; a manual sideload via the room picker or `adb install` is required.

---

## Version History

| Version | Change |
|---------|--------|
| v5.55 | No weekend guard on RESTART or health check |
| v5.56 | Relative test sleep timing (+2 min / +10 min). No weekend behaviour change. |
| v5.57 | ACTION_RESTART weekend guard added. Health check weekend guard added. StandbyActivity Conscrypt fixed. |
| v5.58 | StandbyActivity overnight heartbeat fix — OkHttpClient built after Conscrypt install. |
| v5.72 | `RestartReceiver` added — listens for `MY_PACKAGE_REPLACED` to relaunch kiosk after silent OTA install. `silentInstall()` added to `UpdateChecker` and called at 06:00. |
| v5.73 | `pm install` stdout captured and checked for "Success"/"Failure" on Android 4.4 — `su` exit code alone is not reliable on LG. |
| v5.74 | `postDelayed(300ms)` before re-applying immersive flags in `OnSystemUiVisibilityChangeListener` — fixes nav bar reappearing on Android 4.4 after a system overlay dismisses without restoring window focus. |
| v5.75 | `RestartReceiver` replaced direct `startActivity()` with full-screen notification (`setFullScreenIntent()`) — bypasses Android 10 background activity launch restrictions; also works on Android 4.4. |
