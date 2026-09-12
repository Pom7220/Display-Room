# Watchdog Alarm Self-Heal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 30-minute AlarmManager watchdog that relaunches KioskWebViewActivity if it is not in the foreground during active hours, enabling autonomous mid-day recovery from a dead or crashed APK process.

**Architecture:** A new `ACTION_WATCHDOG` BroadcastReceiver handler is added to `ScheduleReceiver`. It fires every 30 minutes, checks if KioskWebViewActivity is the top activity (Android 4.4 only — API guard for Latte), and relaunches it if not. The alarm re-registers itself on every fire and is initially registered alongside existing alarms in `BootReceiver` and `ScheduleReceiver.schedule()`.

**Tech Stack:** Android Java, AlarmManager, ActivityManager, OkHttp (existing), Conscrypt (existing)

## Global Constraints

- Target Android: 4.4.2 (API 19) for 5 LG tablets; Latte is Android 10 (API 29) — watchdog check skips on API 21+ (LOLLIPOP) because `getRunningTasks` is restricted; skip is safe and silent
- All code must be ES5-equivalent Java — no lambdas, no streams; use anonymous Runnable classes
- APK version: bump from 5.88 → 5.89 in `boot-launcher/app/build.gradle` (`versionCode` and `versionName`)
- PendingIntent request code for watchdog: **5** (1=STANDBY, 2=WAKE, 3=RESTART, 4=HEALTH_CHECK, 10/11=TEST — do not reuse any of these)
- No AndroidManifest change needed — use `setClass(context, ScheduleReceiver.class)` like `scheduleHealthCheck()` does
- Active hours: BKK 07:30–20:30 (timeBKK >= 730 AND timeBKK < 2030)
- `logAlarmEventSync` is blocking HTTP — always call from a background Thread, never from the main thread
- Worker URL: `https://ris-display.ris-display.workers.dev`
- No new dependencies — reuse existing OkHttp and Conscrypt

---

### Task 1: Add ACTION_WATCHDOG to ScheduleReceiver and register on boot

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java`
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java`

**Interfaces:**
- Produces: `ScheduleReceiver.scheduleWatchdog(Context)` — static, called by BootReceiver and ScheduleReceiver.schedule()
- Produces: `ACTION_WATCHDOG` constant — string `"th.co.central.ris.bootlauncher.ACTION_WATCHDOG"`

- [ ] **Step 1: Add the ACTION_WATCHDOG constant to ScheduleReceiver**

In `ScheduleReceiver.java`, add after line 22 (after `ACTION_TEST_WAKE`):

```java
public static final String ACTION_WATCHDOG = "th.co.central.ris.bootlauncher.ACTION_WATCHDOG";
```

The constants block now reads:
```java
public static final String ACTION_STANDBY      = "th.co.central.ris.bootlauncher.ACTION_STANDBY";
public static final String ACTION_WAKE         = "th.co.central.ris.bootlauncher.ACTION_WAKE";
public static final String ACTION_RESTART      = "th.co.central.ris.bootlauncher.ACTION_RESTART";
public static final String ACTION_HEALTH_CHECK = "th.co.central.ris.bootlauncher.ACTION_HEALTH_CHECK";
public static final String ACTION_TEST_SLEEP   = "th.co.central.ris.bootlauncher.ACTION_TEST_SLEEP";
public static final String ACTION_TEST_WAKE    = "th.co.central.ris.bootlauncher.ACTION_TEST_WAKE";
public static final String ACTION_WATCHDOG     = "th.co.central.ris.bootlauncher.ACTION_WATCHDOG";
```

- [ ] **Step 2: Add the ACTION_WATCHDOG handler in onReceive()**

In `ScheduleReceiver.java`, add after the `ACTION_TEST_WAKE` block (after line 83, before the closing brace of `onReceive`):

```java
} else if (ACTION_WATCHDOG.equals(action)) {
    checkAndHeal(context);
    scheduleWatchdog(context);
}
```

- [ ] **Step 3: Add the checkAndHeal() method**

Add this private static method to `ScheduleReceiver.java` after the `isWeekend()` method (after line 124):

```java
private static void checkAndHeal(final Context context) {
    // getRunningTasks(1) returns all apps' tasks on API 19 (Android 4.4).
    // On API 21+, it only returns our own package's tasks — useless for
    // detecting whether Chrome has taken the foreground. Skip on Latte.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) return;

    java.util.Calendar bkk = java.util.Calendar.getInstance(
        java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    int timeBKK = bkk.get(java.util.Calendar.HOUR_OF_DAY) * 100
                + bkk.get(java.util.Calendar.MINUTE);
    if (timeBKK < 730 || timeBKK >= 2030) return; // standby hours — nothing to heal

    android.app.ActivityManager am =
        (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    if (am == null) return;
    java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
    if (tasks == null || tasks.isEmpty()) return;

    String top = tasks.get(0).topActivity.getPackageName();
    if (!context.getPackageName().equals(top)) {
        // logAlarmEventSync is blocking HTTP — must run off the main thread.
        new Thread(new Runnable() {
            @Override public void run() {
                logAlarmEventSync(context, "watchdog_relaunch");
                BootReceiver.launchWebView(context);
            }
        }).start();
    }
}
```

- [ ] **Step 4: Add the scheduleWatchdog() method**

Add this static method to `ScheduleReceiver.java` after `scheduleHealthCheck()` (after line 112):

```java
static void scheduleWatchdog(Context context) {
    android.app.AlarmManager am = (android.app.AlarmManager)
        context.getSystemService(android.content.Context.ALARM_SERVICE);
    if (am == null) return;
    int flags = android.os.Build.VERSION.SDK_INT >= 23
        ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
    android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(context, 5,
        new android.content.Intent(ACTION_WATCHDOG).setClass(context, ScheduleReceiver.class),
        flags);
    long triggerAt = System.currentTimeMillis() + 30 * 60 * 1000L;
    if (android.os.Build.VERSION.SDK_INT >= 23) {
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
    } else {
        am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }
}
```

- [ ] **Step 5: Register watchdog in ScheduleReceiver.schedule()**

In `ScheduleReceiver.java`, update the `schedule()` method (lines 90-95) to call `scheduleWatchdog`:

```java
static void schedule(Context context) {
    setExactAlarm(context, ACTION_STANDBY, 1, 20, 30);
    setExactAlarm(context, ACTION_WAKE,    2,  7, 30);
    setExactAlarm(context, ACTION_RESTART, 3,  6,  0);
    scheduleHealthCheck(context);
    scheduleWatchdog(context);
}
```

- [ ] **Step 6: Verify BootReceiver.java needs no changes**

`BootReceiver.onReceive()` already calls `ScheduleReceiver.schedule(context)` on BOOT_COMPLETED (line 50). After Step 5, `schedule()` calls `scheduleWatchdog()` — so the watchdog is automatically registered on every boot. No change to `BootReceiver.java` is needed.

- [ ] **Step 7: Verify imports are present in ScheduleReceiver.java**

The new methods use `android.app.ActivityManager` and `android.os.Build` — both are already used by `scheduleHealthCheck()` and `setExactAlarm()` via fully-qualified names. No new imports needed.

- [ ] **Step 8: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java
git commit -m "feat(apk): v5.89 — ACTION_WATCHDOG alarm every 30min relaunches kiosk if not foreground

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2: Bump APK version and build

**Files:**
- Modify: `boot-launcher/app/build.gradle`

**Interfaces:**
- Consumes: Task 1 (ScheduleReceiver changes must be committed first)
- Produces: `ris-boot-launcher.apk` at version 5.89 in repo root, triggerable via GitHub Actions

- [ ] **Step 1: Bump versionCode and versionName in build.gradle**

In `boot-launcher/app/build.gradle`, find the `defaultConfig` block and update:

```groovy
versionCode 589
versionName "5.89"
```

(Previous values were `versionCode 588`, `versionName "5.88"`)

- [ ] **Step 2: Commit the version bump**

```bash
git add boot-launcher/app/build.gradle
git commit -m "chore: bump APK version to 5.89 for watchdog alarm

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

- [ ] **Step 3: Push to trigger GitHub Actions build**

```bash
git push origin main
```

Expected: GitHub Actions workflow starts, builds APK, commits `ris-boot-launcher.apk` to repo root within ~3 minutes.

- [ ] **Step 4: Verify build succeeded**

Check GitHub Actions at: `https://github.com/pom7220/Display-Room/actions`

Expected: latest workflow run shows green ✅. If red ❌, check build log for compile errors.

- [ ] **Step 5: Verify APK version in repo**

```bash
git pull
cat apk-version.json
```

Expected output contains `"versionCode": 589` and `"versionName": "5.89"`.

---

### Task 3: Deploy to active tablets and verify watchdog fires correctly

**Files:** None — ADB + Worker diagnostics only

**Interfaces:**
- Consumes: Task 2 (APK 5.89 must be in repo before OTA)

- [ ] **Step 1: Trigger OTA update on all 6 active tablets via health agent**

Run the health agent manually or wait for the next scheduled run. Alternatively, trigger OTA directly for each tablet:

```bash
curl -s -X POST -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  -H "Content-Type: application/json" \
  -d '{"room":"risaffogato@central.co.th","command":"perform_update","sentBy":"manual"}' \
  https://ris-display.ris-display.workers.dev/api/command

curl -s -X POST -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  -H "Content-Type: application/json" \
  -d '{"room":"risdecaffeinato@central.co.th","command":"perform_update","sentBy":"manual"}' \
  https://ris-display.ris-display.workers.dev/api/command

curl -s -X POST -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  -H "Content-Type: application/json" \
  -d '{"room":"rislatte@central.co.th","command":"perform_update","sentBy":"manual"}' \
  https://ris-display.ris-display.workers.dev/api/command

curl -s -X POST -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  -H "Content-Type: application/json" \
  -d '{"room":"rismacchiato@central.co.th","command":"perform_update","sentBy":"manual"}' \
  https://ris-display.ris-display.workers.dev/api/command

curl -s -X POST -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  -H "Content-Type: application/json" \
  -d '{"room":"rismocha@central.co.th","command":"perform_update","sentBy":"manual"}' \
  https://ris-display.ris-display.workers.dev/api/command

curl -s -X POST -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  -H "Content-Type: application/json" \
  -d '{"room":"risviennese@central.co.th","command":"perform_update","sentBy":"manual"}' \
  https://ris-display.ris-display.workers.dev/api/command
```

- [ ] **Step 2: Confirm ota_install events for all 6 tablets**

Wait 10 minutes, then fetch diagnostics:

```bash
curl -s -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  https://ris-display.ris-display.workers.dev/api/diagnostics
```

Expected: each room's `alarmLog` contains an `ota_install` entry with `apkVersion: "5.89"`. If any tablet is missing after 15 min, re-send `perform_update` for that room.

- [ ] **Step 3: Confirm watchdog_relaunch does NOT appear on healthy tablets**

After 1 hour of normal operation, check alarmLog entries:

```bash
curl -s -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
  https://ris-display.ris-display.workers.dev/api/diagnostics
```

Expected: NO `watchdog_relaunch` events. If they appear on every tablet every 30 min, the `getRunningTasks` check is not returning the correct package — investigate ADB logcat on one tablet.

- [ ] **Step 4: Verify watchdog alarm is registered via ADB (one tablet)**

Connect ADB to any active tablet (e.g. Affogato at 10.0.54.111):

```bash
C:\TEMP\platform-tools\adb.exe connect 10.0.54.111:5555
C:\TEMP\platform-tools\adb.exe -s 10.0.54.111:5555 shell dumpsys alarm | findstr WATCHDOG
```

Expected output contains a line with `ACTION_WATCHDOG` showing a trigger ~30 min in the future:
```
th.co.central.ris.bootlauncher.ACTION_WATCHDOG
```

If not present, the alarm was not registered — check that `schedule()` was called after OTA install (RestartReceiver calls `ScheduleReceiver.schedule(context)` — verify this in the existing RestartReceiver code).

- [ ] **Step 5: Update knowledge base**

In `.claude/agents/knowledge-base.md`, add to Confirmed Patterns:

```
### [2026-09-12] ACTION_WATCHDOG self-heal alarm (APK ≥ 5.89)
- status: confirmed
- confirmedOn: 2026-09-12
- evidence: AlarmManager fires every 30 min independently of APK process state. On Android 4.4 (API 19), checks if KioskWebViewActivity is top activity. If not (and BKK time 07:30–20:30), logs watchdog_relaunch and relaunches. Skips on API 21+ (Latte) — getRunningTasks restricted to own package.
- agent action: If watchdog_relaunch appears in alarmLog, classify as RECOVERED. No fix action needed — watchdog already resolved it. Report: "Self-recovered via watchdog at HH:MM BKK."
```

- [ ] **Step 6: Commit knowledge base update**

```bash
git add .claude/agents/knowledge-base.md
git commit -m "docs(knowledge-base): add watchdog_relaunch confirmed pattern — APK 5.89

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```
