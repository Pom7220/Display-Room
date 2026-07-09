# RIS Kiosk — Standby Schedule & Screen Dimming Design

**Date:** 2026-07-09  
**Status:** Approved  

---

## Goal

Save screens and improve app stability on all 12 room tablets during non-office hours, without requiring root, system permissions, or MDM.

---

## Context & Constraints

- **All 12 tablets** are Android (Lenovo-based), all running MEET IN TOUCH alongside our RIS Kiosk app.
- MEET IN TOUCH attempts to power off the device at 20:00 and reboot it at 08:00.
- Some tablets successfully power off/reboot (2–3 tablets). Others stay on all night (majority).
- Our app cannot control OS-level screen power (`PowerManager.goToSleep()` requires system permission).
- Our app cannot trigger a full device reboot (`PowerManager.reboot()` requires system permission).
- Office hours: **Monday–Friday 08:00–20:00**. Weekends are entirely off-hours.
- The existing delay in BootReceiver (waits for MEET IN TOUCH to settle before launching kiosk) must be preserved.

---

## Two Runtime Scenarios

### Scenario A — MEET IN TOUCH fails to power off (always-on tablets)

1. Device stays on all night.
2. **20:30 alarm** fires → launch `StandbyActivity` (screen dims to near-black).
3. **06:00 alarm** fires → app self-restart (clears WebView memory/state).
4. App relaunches → BootReceiver-equivalent time check → 06:00 = off-hours → `StandbyActivity`.
5. **08:00 alarm** fires (Mon–Fri) → launch `KioskWebViewActivity` (full brightness, normal kiosk).

### Scenario B — MEET IN TOUCH successfully powers off + reboots

1. Device powers off before 20:30 → 20:30 alarm never fires.
2. Device reboots at 08:00 → `BootReceiver` fires.
3. BootReceiver checks time: ≥ 08:00 and Mon–Fri → launch `KioskWebViewActivity` (after existing delay).
4. Device reboot itself provides the daily fresh start — 06:00 alarm irrelevant.

### Scenario B edge — device boots before 08:00

1. BootReceiver checks time: before 08:00 → launch `StandbyActivity`.
2. 08:00 alarm fires → switch to `KioskWebViewActivity`.

All three scenarios converge on the same correct state with no special detection logic.

---

## Architecture

### New: `StandbyActivity.java`

**Purpose:** Displays a near-black screen during off-hours. Continues sending heartbeat with `status=sleep`.

**Behaviour:**
- Sets `WindowManager.LayoutParams.screenBrightness = 0.01f` (visually black; avoids system-permission requirement).
- Does NOT set `FLAG_KEEP_SCREEN_ON` — Android system timeout may also eventually power off the backlight.
- Overrides `onTouchEvent()`: touch → restore brightness to 1.0f + show brief "Outside office hours" message → auto-dim back to 0.01f after **30 seconds** of no further touch.
- Blocks back button (kiosk mode).
- Starts a repeating OkHttp POST to `/api/heartbeat` with `status: "sleep"` every 20 minutes (same interval as normal heartbeat). Uses room email and name from `SharedPreferences`.
- On destroy, cancels the heartbeat runnable.

**Layout:** Single `FrameLayout` with black background. Small centred text view (hidden by default, shown on touch): "Outside office hours · Resumes 08:00".

---

### Modified: `BootReceiver.java`

**Additional logic on `BOOT_COMPLETED`:**

```
currentHour = now.hour
currentDay  = now.dayOfWeek   // 1=Mon … 7=Sun

if (Mon–Fri AND currentHour >= 8 AND currentHour < 20):
    wait existing delay
    start KioskWebViewActivity
else:
    start StandbyActivity   // off-hours or weekend
```

Existing delay and room-config intent extras are preserved for the kiosk path.

---

### Modified: `MainActivity.java`

On every launch (after applying rotation and existing setup), register three `AlarmManager` alarms using `setRepeating` with `RTC_WAKEUP`:

| Alarm | Time | Action |
|---|---|---|
| Standby | 20:30 daily | Broadcast → `ScheduleReceiver` → start `StandbyActivity` |
| Wake | 08:00 daily | Broadcast → `ScheduleReceiver` → check Mon–Fri → start `KioskWebViewActivity` or no-op |
| Restart | 06:00 daily | Broadcast → `ScheduleReceiver` → `System.exit(0)` (OS restarts app via BootReceiver) |

Alarms are idempotent — re-registering with the same `PendingIntent` replaces the previous one.

---

### New: `ScheduleReceiver.java`

`BroadcastReceiver` that handles the three alarm intents:

- **`ACTION_STANDBY`** → start `StandbyActivity`.
- **`ACTION_WAKE`** → check day-of-week; if Mon–Fri start `KioskWebViewActivity`; if Sat/Sun do nothing (stay in standby).
- **`ACTION_RESTART`** → call `System.exit(0)`. Android restarts the process; `BootReceiver` fires and checks time.

---

### Modified: `AndroidManifest.xml`

- Register `StandbyActivity` with `android:screenOrientation="portrait"` and `Theme.Holo.NoActionBar.Fullscreen`.
- Register `ScheduleReceiver` with `android:exported="false"`.
- Add `android.permission.SCHEDULE_EXACT_ALARM` if targeting API 31+ (current target is API 30 — not needed).

---

### Modified: Cloudflare Worker (`cloudflare-worker.js`)

No change to heartbeat storage — the Worker already stores the raw `status` string and the KV TTL is 1 hour. A `status=sleep` heartbeat every 20 min keeps the record alive and fresh.

Only the **dashboard display logic** needs updating (see below).

---

### Modified: `dashboard.html`

Add a third visual state for `status === "sleep"`:

| `isOnline` | `status` | Dot | Label |
|---|---|---|---|
| true | `ok` / `needs_tap` | 🟢 green | Active |
| true | `sleep` | 🌙 grey | Standby |
| false | any | 🔴 red | Offline |

Logic: if `lastSeenMinutes < 30` AND `status === "sleep"` → show Standby (not Active). If `lastSeenMinutes >= 30` → Offline regardless of stored status.

---

## Heartbeat During Standby

`StandbyActivity` sends heartbeat via OkHttp (Java), not JavaScript, because the WebView is not running during standby.

Payload mirrors the existing JS heartbeat shape:

```json
{
  "room": "rislatte@central.co.th",
  "roomname": "Latte",
  "status": "sleep",
  "launchMode": "standby",
  "version": "5.x",
  "uptime": 0
}
```

---

## Touch-to-Wake UX

- **Touch during standby** → brightness 1.0 immediately → show "Outside office hours · Resumes 08:00" for 30 seconds → auto-dim to 0.01.
- **08:00 alarm or BootReceiver** → `KioskWebViewActivity` replaces `StandbyActivity` (no touch required).
- No PIN or manual action needed to resume at office hours start.

---

## What This Does NOT Do

- Does not power off the screen at OS level (requires system permission).
- Does not reboot the device (requires system permission). The 06:00 restart is an app-process restart only.
- Does not interfere with MEET IN TOUCH — if MEET IN TOUCH powers off the device, our alarms simply never fire; BootReceiver handles the next boot.

---

## Files Changed

| File | Change |
|---|---|
| `StandbyActivity.java` | New |
| `ScheduleReceiver.java` | New |
| `BootReceiver.java` | Add time/day check on boot |
| `MainActivity.java` | Register 3 AlarmManager alarms |
| `AndroidManifest.xml` | Register new activity + receiver |
| `dashboard.html` | Add sleep/standby visual state |

`cloudflare-worker.js` and `index.html` require no changes.
