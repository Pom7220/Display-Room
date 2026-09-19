# Escalated Auto Cold Reboot Design

**Goal:** Add a self-healing Level 2 escalation to the APK watchdog — when Level 1 process restarts fail to recover a tablet, the device autonomously performs a hard reboot, with a daily cap to prevent runaway loops.

**Architecture:** All logic lives in `ForegroundWatchService.java`. No Worker changes, no cloud dependency, no laptop required. Uses the proven reboot command already in `ACTION_RESTART`. A new `escalated_reboot` alarm event makes the action visible on the dashboard.

**Tech Stack:** Android Java (API 19+), existing `/api/alarm` endpoint, dashboard.html icon map.

---

## Escalation Ladder

| Level | Trigger | Action | Existing? |
|-------|---------|--------|-----------|
| 1 | No JS ping for 10 min | Process restart (`ping_timeout`) | ✓ v5.90 |
| 2 | `restartCount ≥ 3` within 120 min, OR no ping for 120 min after Level 1 | Hard reboot (`escalated_reboot`) | **New** |
| 3 | Daily 06:00 ACTION_RESTART | Hard reboot regardless | ✓ |

**Daily cap:** `dailyRebootCount` max 3. When reached, Level 2 is suppressed — tablet stays down until 06:00 ACTION_RESTART clears it naturally via reboot.

**Standby guard:** Level 2 does not fire during standby hours (20:30–07:30 BKK) or on weekends — same time gate as `checkAndHeal()`.

---

## State Fields (ForegroundWatchService)

```java
private static volatile long lastRestartAttemptMs = 0L;  // time of first Level 1 in current window
private static volatile int  restartCount         = 0;   // Level 1 fires since last recovery
private static volatile int  dailyRebootCount     = 0;   // Level 2 fires today
```

All three are reset on `cold_boot` (BootReceiver fires on every APK start after any reboot).

---

## Logic

### On ping_timeout (Level 1 fires)
```
restartCount++
if lastRestartAttemptMs == 0: lastRestartAttemptMs = now
scheduleProcessRestart("ping_timeout")   // existing
if restartCount >= 3: fireLevel2()       // new: restart loop detected
```

### On watchdog tick (every 5 min)
```
if lastRestartAttemptMs > 0
    AND now - lastRestartAttemptMs > 120 min
    AND timeBKK >= 730 AND timeBKK < 2030 AND !isWeekend():
        fireLevel2()
```

### fireLevel2()
```
if dailyRebootCount >= 3: return  // cap reached, suppress
dailyRebootCount++
restartCount = 0
lastRestartAttemptMs = 0
logAlarmEvent(context, "escalated_reboot")   // posts to /api/alarm
if SDK >= 21: exec("reboot")
else:         exec("su", "-c", "reboot")      // proven on Android 4.4
```

### On successful Android.ping()
```
restartCount = 0
lastRestartAttemptMs = 0
// dailyRebootCount intentionally NOT reset — persists until cold_boot
```

### On cold_boot (BootReceiver)
```
restartCount = 0
lastRestartAttemptMs = 0
dailyRebootCount = 0
```

---

## Dashboard

In `dashboard.html` alarm chip icon map, add:
```javascript
escalated_reboot: '⚡❄️'
```

Label shown: **"Auto Cold Reboot"**

Renders as a chip on the room card alongside other alarm events (cold_boot ❄️, restart 🔄, wake ☀️ etc.).

---

## APK Version

Bump: `5.108 → 5.109` (`versionCode 608 → 609`)

---

## What This Does NOT Cover

- Tablets where `ForegroundWatchService` itself is dead — Level 2 cannot fire; 06:00 ACTION_RESTART remains the backstop
- Cloudflare cron autonomous agent — deferred to a future spec
- Notifications — silent by design; visible on dashboard via `⚡❄️` chip
