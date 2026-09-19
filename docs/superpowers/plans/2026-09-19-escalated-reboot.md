# Escalated Auto Cold Reboot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Level 2 escalation to the APK watchdog — if Level 1 process restarts fail to recover a tablet (3 restarts in 120 min, or 120 min with no recovery), the device autonomously performs a hard cold reboot, capped at 3 per day.

**Architecture:** All logic lives in `ForegroundWatchService.java` (120-min timer trigger) and `KioskWebViewActivity.java` (restart-count trigger). Escalation state persists to SharedPreferences (`ris_kiosk_prefs`) because `scheduleProcessRestart()` kills the process — in-memory statics are lost on each Level 1. BootReceiver clears per-session escalation state on cold_boot. Dashboard gets a new `⚡❄️` icon for the `escalated_reboot` alarm event.

**Tech Stack:** Android Java (API 19+, compileSdkVersion 30), SharedPreferences, existing `/api/alarm` endpoint, dashboard.html.

## Global Constraints

- `compileSdkVersion 30`, `minSdkVersion 19` — no API 31+ symbols
- All Java must be Java 8 compatible (`compileOptions JavaVersion.VERSION_1_8`)
- SharedPreferences file name: `"ris_kiosk_prefs"` (existing, do not create a new file)
- SharedPreferences keys: `escalation_first_restart_ms` (long), `escalation_restart_count` (int), `escalation_daily_reboot_date` (String "yyyy-MM-dd" BKK), `escalation_daily_reboot_count` (int)
- Level 2 daily cap: 3 reboots per calendar day (BKK timezone). Cap resets by date comparison, NOT on cold_boot — so a Level 2 reboot followed by BootReceiver does not reset the cap
- Level 2 time gate: `timeBKK >= 730 && timeBKK < 2030 && !isWeekend()` — same as `checkAndHeal()`
- Reboot command (proven in `ACTION_RESTART`): API ≥ 21 → `exec("reboot")`, API < 21 → `exec("su", "-c", "reboot")`
- Alarm event name: `"escalated_reboot"` — logged via `ScheduleReceiver.logAlarmEventSync()`
- APK version bump: `versionCode 608 → 609`, `versionName "5.108" → "5.109"`
- index.html patch: `209 → 210`; `index-version.json`: `"v3.10.209" → "v3.10.210"`
- Every git push goes to `main` branch

---

### Task 1: ForegroundWatchService — 120-min escalation timer

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java`

**Context:** `ForegroundWatchService` runs the 5-min `checkRunnable` loop. It currently calls `checkHeartbeat()` and `checkAndRestore()`. We add `checkEscalation()` to the loop. This method reads SharedPreferences to check if 120 min have passed since the first Level 1 restart, applies the time/weekend gate and daily cap, then fires a hard reboot. `fireEscalatedReboot()` does the reboot.

`checkEscalation()` runs even when `lastHeartbeatSuccessMs == 0` (WebView never loaded — that's precisely the scenario we want to catch).

- [ ] **Step 1: Add `checkEscalation()` call to the checkRunnable loop**

In `checkRunnable`, change:
```java
private Runnable checkRunnable = new Runnable() {
    @Override
    public void run() {
        checkHeartbeat();
        checkAndRestore();
        handler.postDelayed(this, CHECK_INTERVAL_MS);
    }
};
```
to:
```java
private Runnable checkRunnable = new Runnable() {
    @Override
    public void run() {
        checkHeartbeat();
        checkAndRestore();
        checkEscalation();
        handler.postDelayed(this, CHECK_INTERVAL_MS);
    }
};
```

- [ ] **Step 2: Add `checkEscalation()` method**

Add this method after `checkAndRestore()` (around line 108):

```java
private void checkEscalation() {
    android.content.SharedPreferences prefs =
        getSharedPreferences("ris_kiosk_prefs", MODE_PRIVATE);
    long firstRestartMs = prefs.getLong("escalation_first_restart_ms", 0L);
    if (firstRestartMs == 0L) return; // no escalation in progress

    // Standby / weekend gate — same bounds as checkAndHeal()
    java.util.Calendar bkk = java.util.Calendar.getInstance(
        java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    int timeBKK = bkk.get(java.util.Calendar.HOUR_OF_DAY) * 100
                + bkk.get(java.util.Calendar.MINUTE);
    if (timeBKK < 730 || timeBKK >= 2030) return;
    int day = bkk.get(java.util.Calendar.DAY_OF_WEEK);
    if (day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY) return;

    // 120-min threshold
    if (System.currentTimeMillis() - firstRestartMs < 120 * 60 * 1000L) return;

    fireEscalatedReboot(prefs);
}
```

- [ ] **Step 3: Add `fireEscalatedReboot()` method**

Add this method after `checkEscalation()`:

```java
private void fireEscalatedReboot(android.content.SharedPreferences prefs) {
    // Daily cap check — resets by BKK calendar date, not on cold_boot
    java.text.SimpleDateFormat sdf =
        new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    String today = sdf.format(new java.util.Date());
    String lastDate = prefs.getString("escalation_daily_reboot_date", "");
    int dailyCount = today.equals(lastDate)
        ? prefs.getInt("escalation_daily_reboot_count", 0) : 0;
    if (dailyCount >= 3) return; // cap reached — wait for manual intervention

    // Commit escalation state update before reboot (sync write is critical here)
    prefs.edit()
        .putString("escalation_daily_reboot_date", today)
        .putInt("escalation_daily_reboot_count", dailyCount + 1)
        .putLong("escalation_first_restart_ms", 0L)
        .putInt("escalation_restart_count", 0)
        .commit(); // commit() not apply() — process may die immediately after

    final android.content.Context ctx = getApplicationContext();
    new Thread(new Runnable() {
        @Override public void run() {
            ScheduleReceiver.logAlarmEventSync(ctx, "escalated_reboot");
            try {
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    Runtime.getRuntime().exec(new String[]{"reboot"});
                } else {
                    Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                }
            } catch (Exception ignored) {}
        }
    }).start();
}
```

- [ ] **Step 4: Verify no import issues**

`SimpleDateFormat`, `Locale`, `Date`, `TimeZone`, `Calendar` are all in `java.util` / `java.text` — already imported transitively or use fully-qualified names (as written above). Confirm the file compiles by checking no red squiggles in IDE, or proceed to build in Task 4.

- [ ] **Step 5: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java
git commit -m "feat: add 120-min escalation timer to ForegroundWatchService

If escalation_first_restart_ms is set and 120 min have elapsed without
recovery, fireEscalatedReboot() logs escalated_reboot and hard-reboots the
device. Daily cap of 3 reboots (BKK date); time-gated to business hours.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2: KioskWebViewActivity + BootReceiver — restart-count trigger and state management

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java`
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java`

**Context:**

`scheduleProcessRestart()` in `KioskWebViewActivity` is where Level 1 fires. We persist escalation state to SharedPreferences here before killing the process. If `restartCount >= 3` within the 120-min window, we escalate to Level 2 immediately instead of doing another process restart.

`HeartbeatBridge.recordHeartbeatSuccess()` is called by JS on every successful heartbeat POST. This is the "recovery confirmed" signal — clear the escalation state here.

`BootReceiver` runs on every cold_boot. Clear `escalation_first_restart_ms` and `escalation_restart_count` (fresh session), but NOT `escalation_daily_reboot_count` (the cap must persist across reboots within the same day).

**Important:** `fireEscalatedReboot()` lives in `ForegroundWatchService`. In the `scheduleProcessRestart()` path (KioskWebViewActivity process), we cannot call it directly. Instead, duplicate the reboot logic inline (same 4 lines) when `restartCount >= 3`. Both code paths use identical reboot commands — this is intentional, not a DRY violation, because the two callers are in different process contexts.

- [ ] **Step 1: Update `scheduleProcessRestart()` to persist escalation state**

Current `scheduleProcessRestart()` starts at line ~168. Replace the entire method with:

```java
/** Schedule a fresh process launch 3 s from now, then kill this process. */
private void scheduleProcessRestart(final String reason) {
    // Persist escalation state before killing — static fields reset on process death.
    final android.content.SharedPreferences prefs =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    long firstRestartMs = prefs.getLong("escalation_first_restart_ms", 0L);
    if (firstRestartMs == 0L) firstRestartMs = System.currentTimeMillis();
    final int restartCount = prefs.getInt("escalation_restart_count", 0) + 1;
    final long windowMs = System.currentTimeMillis() - firstRestartMs;
    prefs.edit()
        .putLong("escalation_first_restart_ms", firstRestartMs)
        .putInt("escalation_restart_count", restartCount)
        .commit(); // commit() — process may die immediately after

    logWebViewRestart(reason);

    // Restart loop detected: 3+ restarts within 120 min → skip another restart, hard reboot.
    if (restartCount >= 3 && windowMs < 120 * 60 * 1000L) {
        // Check daily cap (same logic as ForegroundWatchService.fireEscalatedReboot)
        java.text.SimpleDateFormat sdf =
            new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        final String today = sdf.format(new java.util.Date());
        String lastDate = prefs.getString("escalation_daily_reboot_date", "");
        int dailyCount = today.equals(lastDate)
            ? prefs.getInt("escalation_daily_reboot_count", 0) : 0;
        if (dailyCount < 3) {
            prefs.edit()
                .putString("escalation_daily_reboot_date", today)
                .putInt("escalation_daily_reboot_count", dailyCount + 1)
                .putLong("escalation_first_restart_ms", 0L)
                .putInt("escalation_restart_count", 0)
                .commit();
            final android.content.Context ctx = getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    ScheduleReceiver.logAlarmEventSync(ctx, "escalated_reboot");
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 21) {
                            Runtime.getRuntime().exec(new String[]{"reboot"});
                        } else {
                            Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                        }
                    } catch (Exception ignored) {}
                }
            }).start();
            return; // reboot is coming — do not also kill the process
        }
    }

    // Normal Level 1: schedule relaunch in 3 s then kill this process.
    android.app.AlarmManager am =
        (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
    Intent relaunch = new Intent(this, KioskWebViewActivity.class);
    relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
        this, 9999, relaunch,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT);
    am.set(android.app.AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + 3000L, pi);
    android.os.Process.killProcess(android.os.Process.myPid());
}
```

- [ ] **Step 2: Clear escalation state on successful heartbeat**

In `HeartbeatBridge.recordHeartbeatSuccess()` (around line 418), change:

```java
@JavascriptInterface
public void recordHeartbeatSuccess(int intervalMins) {
    ScheduleReceiver.lastHeartbeatSuccessMs = System.currentTimeMillis();
    if (intervalMins > 0) {
        ScheduleReceiver.heartbeatIntervalMs = intervalMins * 60 * 1000L;
    }
}
```

to:

```java
@JavascriptInterface
public void recordHeartbeatSuccess(int intervalMins) {
    ScheduleReceiver.lastHeartbeatSuccessMs = System.currentTimeMillis();
    if (intervalMins > 0) {
        ScheduleReceiver.heartbeatIntervalMs = intervalMins * 60 * 1000L;
    }
    // Recovery confirmed — clear escalation state (daily cap intentionally kept)
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        .putLong("escalation_first_restart_ms", 0L)
        .putInt("escalation_restart_count", 0)
        .apply();
}
```

- [ ] **Step 3: Clear per-session escalation state on cold_boot in BootReceiver**

In `BootReceiver`, in the background thread (the `new Thread(...)` in `onReceive()`), add this as the first statement inside the thread's `run()` method, before the `fixClockAndLogBoot` / `logAlarmEventSync` calls:

```java
// Clear per-session escalation state — fresh boot resets the restart ladder.
// Note: escalation_daily_reboot_count is intentionally NOT cleared here;
// it resets by calendar date in fireEscalatedReboot() to enforce the daily cap.
context.getSharedPreferences("ris_kiosk_prefs", Context.MODE_PRIVATE).edit()
    .putLong("escalation_first_restart_ms", 0L)
    .putInt("escalation_restart_count", 0)
    .apply();
```

- [ ] **Step 4: Verify required imports in KioskWebViewActivity**

`SimpleDateFormat`, `Locale`, `Date`, `TimeZone` are in `java.text` / `java.util`. Check existing imports at the top of `KioskWebViewActivity.java`. Add any missing:

```java
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
```

- [ ] **Step 5: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java
git commit -m "feat: escalation state tracking in scheduleProcessRestart + BootReceiver reset

- scheduleProcessRestart: persist restart count/timestamp to SharedPreferences
  before process kill; escalate to hard reboot if 3+ restarts within 120 min
- recordHeartbeatSuccess: clear escalation state on recovery
- BootReceiver: clear per-session escalation state on cold_boot

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 3: Dashboard icon + version bumps + CI push

**Files:**
- Modify: `dashboard.html` (alarm chip icon map)
- Modify: `boot-launcher/app/build.gradle` (versionCode/Name)
- Modify: `index.html` (APP_VERSION patch + date)
- Modify: `index-version.json`

**Context:** The `escalated_reboot` alarm event posts to `/api/alarm` and is stored in the room's alarm log. The dashboard renders it as a chip using the icon map. Currently unknown event types render as `•`. Adding `escalated_reboot: '⚡❄️'` makes it immediately recognizable on the dashboard. Version bumps trigger CI to build and deploy APK 5.109.

- [ ] **Step 1: Add escalated_reboot icon to dashboard.html**

Search for the alarm chip icon map in `dashboard.html`. It looks like:
```javascript
{cold_boot:'❄️', restart:'🔄', wake:'☀️', wake_weekend:'⏭️', standby:'🌙'}
```

Add `escalated_reboot:'⚡❄️'` to the map:
```javascript
{cold_boot:'❄️', restart:'🔄', wake:'☀️', wake_weekend:'⏭️', standby:'🌙', escalated_reboot:'⚡❄️'}
```

- [ ] **Step 2: Bump APK versionCode and versionName in build.gradle**

In `boot-launcher/app/build.gradle`, change:
```groovy
versionCode 608
versionName "5.108"
```
to:
```groovy
versionCode 609
versionName "5.109"
```

- [ ] **Step 3: Bump index.html APP_VERSION**

In `index.html`, change:
```javascript
patch: 209,
date: '2026-09-19',
```
to:
```javascript
patch: 210,
date: '2026-09-19',
```

- [ ] **Step 4: Bump index-version.json**

Change:
```json
{"version":"v3.10.209"}
```
to:
```json
{"version":"v3.10.210"}
```

- [ ] **Step 5: Commit dashboard and web files, then commit APK bump separately, then push**

```bash
git add dashboard.html index.html index-version.json
git commit -m "feat: add escalated_reboot ⚡❄️ chip to dashboard alarm icon map

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

```bash
git add boot-launcher/app/build.gradle
git commit -m "chore: bump APK version to 5.109

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

```bash
git push
```

Expected: GitHub Actions CI picks up the push and builds APK 5.109. CI will commit the built APK binary with `[skip ci]` automatically.

- [ ] **Step 6: Verify CI build succeeds**

Watch the GitHub Actions run. Confirm the APK build job completes without errors. The CI commit message will contain `[skip ci]` and reference APK 5.109.
