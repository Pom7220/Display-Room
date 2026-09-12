# Watchdog Alarm Self-Heal — Design Spec

**Date:** 2026-09-12
**Author:** VoRutchapon
**Status:** Approved for implementation

---

## Goal

Enable tablets to self-recover from a dead or crashed APK process remotely, without requiring ADB or physical intervention, by adding a frequent AlarmManager-based watchdog that relaunches KioskWebViewActivity if it is no longer in the foreground during active hours.

---

## Background

`ForegroundWatchService` currently checks the foreground activity every 5 minutes but runs as a Service — it dies when the APK process dies. AlarmManager alarms (06:00 restart, 07:30 wake) fire independently of process state but only twice a day, leaving up to a 16-hour recovery gap for mid-day crashes.

The watchdog alarm fires every 30 minutes via AlarmManager. Android starts the BroadcastReceiver even when the APK process is completely dead, enabling autonomous recovery within 30 minutes of any mid-day failure.

---

## Architecture

```
AlarmManager (every 30 min, RTC_WAKEUP)
    → ACTION_WATCHDOG → ScheduleReceiver.onReceive()
        → checkAndHeal()
            → BKK time in active hours (07:30–20:30)?
                NO  → no action (standby hours)
                YES → KioskWebViewActivity top activity?
                        YES → no action (healthy)
                        NO  → logAlarmEventSync("watchdog_relaunch")
                              → BootReceiver.launchWebView()
        → scheduleWatchdog()  ← re-register next 30 min trigger
```

---

## What This Covers

| Failure | Covered? |
|---|---|
| APK process killed by OS mid-day | ✅ Recovers within 30 min |
| KioskWebViewActivity crashed | ✅ Recovers within 30 min |
| Chrome took foreground (like Viennese incident Sep 9) | ✅ Recovers within 30 min |
| Tablet fully powered off (PoE cut, AlarmManager lost) | ❌ PoE cycle still needed |
| Lobby VLAN MDCA network block (Doppio etc.) | ❌ Network issue, not process issue |

---

## APK Changes

**Version bump:** 5.88 → 5.89

### `ScheduleReceiver.java`

Add constant:
```java
public static final String ACTION_WATCHDOG = "th.co.central.ris.bootlauncher.ACTION_WATCHDOG";
```

Add handler in `onReceive()`:
```java
} else if (ACTION_WATCHDOG.equals(action)) {
    checkAndHeal(context);
    scheduleWatchdog(context);
}
```

Add `checkAndHeal()`:
```java
private void checkAndHeal(Context context) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Bangkok"));
    int timeBKK = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE);
    if (timeBKK < 730 || timeBKK >= 2030) return;

    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    if (am == null) return;
    List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
    if (tasks == null || tasks.isEmpty()) return;
    String top = tasks.get(0).topActivity.getPackageName();
    if (!context.getPackageName().equals(top)) {
        logAlarmEventSync(context, "watchdog_relaunch");
        BootReceiver.launchWebView(context);
    }
}
```

Add `scheduleWatchdog()`:
```java
static void scheduleWatchdog(Context context) {
    long triggerAt = System.currentTimeMillis() + 30 * 60 * 1000L;
    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    Intent i = new Intent(context, ScheduleReceiver.class);
    i.setAction(ACTION_WATCHDOG);
    PendingIntent pi = PendingIntent.getBroadcast(context, 10, i, 0);
    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
}
```

### `BootReceiver.java`

Call `ScheduleReceiver.scheduleWatchdog(context)` alongside existing alarm registrations in the boot thread and in `scheduleAll()` so the watchdog starts on every boot and OTA restart.

### `AndroidManifest.xml`

Add `ACTION_WATCHDOG` to `ScheduleReceiver`'s `<intent-filter>`:
```xml
<action android:name="th.co.central.ris.bootlauncher.ACTION_WATCHDOG" />
```

---

## KV Impact

- **Normal (healthy) operation:** 0 additional KV writes. `checkAndHeal()` runs entirely on-device via `ActivityManager.getRunningTasks()`. No network call made when KioskWebViewActivity is in foreground.
- **Incident (relaunch fires):** 1 KV write per `watchdog_relaunch` event via existing `logAlarmEventSync` → POST `/api/heartbeat`. Negligible.
- KV only writes on state change — identical heartbeats are skipped by the Worker. No limit concern.

---

## Health Agent Integration

`watchdog_relaunch` appears in the tablet's `alarmLog` via the existing heartbeat event mechanism. The health agent should:
- Treat `watchdog_relaunch` as a **RECOVERED** signal (same as `webview_process_restart`)
- Report it as: "Self-recovered via watchdog at HH:MM BKK — KioskWebViewActivity was not in foreground."
- No fix action needed — watchdog already resolved it.

---

## Success Criteria

- After APK process death during active hours (07:30–20:30): KioskWebViewActivity relaunches within 30 minutes without any manual intervention
- `watchdog_relaunch` event visible in Worker diagnostics alarmLog after recovery
- No watchdog events on healthy days (zero false positives)
- Existing alarms (restart, wake, standby) unaffected

---

## Deployment

1. Build APK v5.89 via GitHub Actions
2. OTA push to all 6 active Office tablets via health agent `perform_update`
3. Verify `watchdog_relaunch` does NOT appear after normal operation (confirms no false triggers)
4. Monitor for 2 days — confirm all tablets healthy
