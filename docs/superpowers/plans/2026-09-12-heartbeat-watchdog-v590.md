# Heartbeat Watchdog Implementation Plan (v5.90)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a native heartbeat watchdog that restarts KioskWebViewActivity if no successful JS heartbeat has been received within `heartbeatInterval + 15 min` during active hours — enabling autonomous recovery from a dead JS loop without physical intervention or a reload command.

**Architecture:** JS calls `window.Android.recordHeartbeatSuccess(intervalMins)` on every successful XHR heartbeat. ForegroundWatchService checks every 5 min; if `now - lastHeartbeatSuccessMs > heartbeatIntervalMs + 15 min` during active hours (07:30–20:30 BKK), it logs `heartbeat_watchdog_restart`, relaunches KioskWebViewActivity, and kills the current process so the new process starts clean.

**Tech Stack:** Android Java, ForegroundWatchService (existing), JavascriptInterface, OkHttp (existing)

## Global Constraints

- Target Android: 4.4.2 (API 19) for 5 LG tablets; Latte is Android 10 (API 29) — watchdog must work on BOTH
- All code must be ES5-equivalent Java — no lambdas, no streams; use anonymous Runnable/Handler classes
- APK version: bump from 5.89 → 5.90 in `boot-launcher/app/build.gradle`
- Active hours for watchdog: BKK 07:30–20:30 (timeBKK >= 730 AND timeBKK < 2030)
- Threshold: `heartbeatIntervalMs + 15 * 60 * 1000L` (dynamic, from last JS call)
- Default heartbeatIntervalMs: `30 * 60 * 1000L` (30 min) — used until first JS call arrives
- `logAlarmEventSync` is blocking HTTP — always call from a background Thread
- Worker URL: `https://ris-display.ris-display.workers.dev`
- No new dependencies — reuse existing OkHttp and Conscrypt
- JS change: add ONE line to `index.html` in the `sendHeartbeat()` success callback only

---

### Task 1: Add JS bridge in KioskWebViewActivity + static state in ScheduleReceiver

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java`
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java`

**Interfaces:**
- Produces: `ScheduleReceiver.lastHeartbeatSuccessMs` — `public static volatile long`
- Produces: `ScheduleReceiver.heartbeatIntervalMs` — `public static volatile long`
- Produces: `KioskWebViewActivity.HeartbeatBridge.recordHeartbeatSuccess(int)` — `@JavascriptInterface`

- [ ] **Step 1: Add two static volatile fields to ScheduleReceiver**

In `ScheduleReceiver.java`, add after the existing constant declarations (after `ACTION_WATCHDOG`):

```java
// Written by JS bridge; read by ForegroundWatchService heartbeat watchdog.
public static volatile long lastHeartbeatSuccessMs = 0L;
public static volatile long heartbeatIntervalMs    = 30 * 60 * 1000L; // default 30 min
```

- [ ] **Step 2: Add the HeartbeatBridge inner class to KioskWebViewActivity**

In `KioskWebViewActivity.java`, add a new static inner class. Find the existing inner class or end of class body and add:

```java
private static class HeartbeatBridge {
    @android.webkit.JavascriptInterface
    public void recordHeartbeatSuccess(int intervalMins) {
        ScheduleReceiver.lastHeartbeatSuccessMs = System.currentTimeMillis();
        if (intervalMins > 0) {
            ScheduleReceiver.heartbeatIntervalMs = intervalMins * 60 * 1000L;
        }
    }
}
```

- [ ] **Step 3: Register the JS bridge when the WebView is set up**

In `KioskWebViewActivity.java`, find where `addJavascriptInterface` is called (search for `addJavascriptInterface`). Add the heartbeat bridge alongside the existing `Android` interface:

```java
mWebView.addJavascriptInterface(new HeartbeatBridge(), "AndroidHB");
```

Note: We use `AndroidHB` to avoid collision with the existing `Android` interface object.

- [ ] **Step 4: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java
git commit -m "feat(apk): v5.90 — add HeartbeatBridge JS interface + static heartbeat state

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2: Add heartbeat watchdog check in ForegroundWatchService

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java`

**Interfaces:**
- Consumes: `ScheduleReceiver.lastHeartbeatSuccessMs`, `ScheduleReceiver.heartbeatIntervalMs` (from Task 1)
- Produces: `heartbeat_watchdog_restart` alarm log event
- Produces: restart via `getLaunchIntentForPackage` + `Process.killProcess`

- [ ] **Step 1: Read ForegroundWatchService to understand its loop structure**

```bash
cat "boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java"
```

Find the 5-minute loop (Handler/Runnable that calls itself with `postDelayed`). Identify where the loop body is — this is where we add the heartbeat check.

- [ ] **Step 2: Add checkHeartbeat() method**

Add this private method to `ForegroundWatchService`:

```java
private void checkHeartbeat() {
    long lastSuccess = ScheduleReceiver.lastHeartbeatSuccessMs;
    // No heartbeat recorded yet in this process — don't fire on cold start.
    if (lastSuccess == 0L) return;

    java.util.Calendar bkk = java.util.Calendar.getInstance(
        java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    int timeBKK = bkk.get(java.util.Calendar.HOUR_OF_DAY) * 100
                + bkk.get(java.util.Calendar.MINUTE);
    if (timeBKK < 730 || timeBKK >= 2030) return; // standby hours

    long threshold = ScheduleReceiver.heartbeatIntervalMs + 15 * 60 * 1000L;
    if (System.currentTimeMillis() - lastSuccess <= threshold) return;

    // Heartbeat overdue — log and restart.
    final android.content.Context ctx = getApplicationContext();
    new Thread(new Runnable() {
        @Override public void run() {
            logAlarmEventSync(ctx, "heartbeat_watchdog_restart");
            android.content.Intent launch =
                ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (launch != null) {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            }
            // Kill this process so the new launch starts clean.
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }).start();
}
```

Note: `logAlarmEventSync` must already exist in ForegroundWatchService or be accessible — check Step 1 output. If not present, call `ScheduleReceiver.logAlarmEventSync(ctx, "heartbeat_watchdog_restart")` using the existing static method.

- [ ] **Step 3: Call checkHeartbeat() from the service loop**

Inside the existing 5-minute loop body (the Runnable that runs repeatedly), add ONE line at the start:

```java
checkHeartbeat();
```

- [ ] **Step 4: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java
git commit -m "feat(apk): v5.90 — heartbeat watchdog in ForegroundWatchService, restarts on stale JS heartbeat

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 3: Update index.html to call JS bridge on heartbeat success

**Files:**
- Modify: `index.html`

**Interfaces:**
- Consumes: `window.AndroidHB.recordHeartbeatSuccess(intervalMins)` (from Task 1)
- Modifies: `sendHeartbeat()` success callback (around line 1787)

- [ ] **Step 1: Locate the sendHeartbeat XHR success callback**

```bash
grep -n "sendHeartbeat\|onload\|recordHeartbeat\|_hbMins" index.html | head -30
```

Find the line inside `sendHeartbeat()` where `xhr.onload` or success is handled.

- [ ] **Step 2: Add the bridge call on success**

Inside `sendHeartbeat()`, in the XHR success branch (where the heartbeat POST returned 2xx), add:

```javascript
if (window.AndroidHB && window.AndroidHB.recordHeartbeatSuccess) {
    window.AndroidHB.recordHeartbeatSuccess(_hbMins);
}
```

Add it immediately after any existing success logging, before the closing brace of the success handler. The `if` guard ensures it silently no-ops on browsers or older APKs without the bridge.

- [ ] **Step 3: Commit**

```bash
git add index.html
git commit -m "feat(web): v5.90 — call AndroidHB.recordHeartbeatSuccess on heartbeat XHR success

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 4: Bump APK version and build

**Files:**
- Modify: `boot-launcher/app/build.gradle`

**Interfaces:**
- Consumes: Tasks 1–3 committed

- [ ] **Step 1: Bump versionCode and versionName**

In `boot-launcher/app/build.gradle`, update `defaultConfig`:

```groovy
versionCode 590
versionName "5.90"
```

- [ ] **Step 2: Commit and push**

```bash
git add boot-launcher/app/build.gradle
git commit -m "chore: bump APK version to 5.90 for heartbeat watchdog

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
git push origin main
```

Expected: GitHub Actions builds APK, commits `ris-boot-launcher.apk` to repo within ~3 min.

- [ ] **Step 3: Verify build succeeded**

Check GitHub Actions at `https://github.com/pom7220/Display-Room/actions`. Latest run must be green ✅.

- [ ] **Step 4: Verify APK version**

```bash
git pull
cat apk-version.json
```

Expected: `"versionCode": 590, "versionName": "5.90"`

---

### Task 5: Deploy and verify

**Files:** None — ADB + Worker diagnostics only

- [ ] **Step 1: OTA all 6 tablets**

Health agent will send `perform_update` at next run, or trigger manually:

```bash
for room in risaffogato@central.co.th risdecaffeinato@central.co.th rislatte@central.co.th rismacchiato@central.co.th rismocha@central.co.th risviennese@central.co.th; do
  curl -s -X POST -H "X-Admin-Key: RIS-ROOM-ADMIN2026" \
    -H "Content-Type: application/json" \
    -d "{\"room\":\"$room\",\"command\":\"perform_update\",\"sentBy\":\"manual\"}" \
    https://ris-display.ris-display.workers.dev/api/command
done
```

- [ ] **Step 2: Confirm ota_install events**

Wait 10 min, then check diagnostics. Each room's `alarmLog` must contain `ota_install` with `apkVersion: "5.90"`.

- [ ] **Step 3: Confirm no false-positive restarts**

After 1 hour of normal operation, check alarmLog for `heartbeat_watchdog_restart`. Expected: none during normal operation (JS heartbeat firing every 20–30 min is well within threshold).

- [ ] **Step 4: Update knowledge base**

In `.claude/agents/knowledge-base.md`, add to Confirmed Patterns:

```
### [2026-09-12] Heartbeat watchdog restart (APK ≥ 5.90)
- status: confirmed
- confirmedOn: 2026-09-12 (deployed)
- evidence: ForegroundWatchService 5-min loop. If JS heartbeat not received within heartbeatInterval + 15 min during 07:30-20:30 BKK, logs heartbeat_watchdog_restart and relaunches via getLaunchIntentForPackage + Process.killProcess. lastHeartbeatSuccessMs = 0 on cold start — watchdog skips until first JS heartbeat arrives.
- agent action: If heartbeat_watchdog_restart appears in alarmLog, classify as RECOVERED. No fix action needed. Report: "Self-recovered via heartbeat watchdog at HH:MM BKK — JS loop had been silent since [last heartbeat ts]."
```

- [ ] **Step 5: Commit knowledge base**

```bash
git add .claude/agents/knowledge-base.md
git commit -m "docs(knowledge-base): add heartbeat_watchdog_restart confirmed pattern — APK 5.90

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
git push origin main
```
