# Standby Schedule & Screen Dimming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dim the screen to near-black at 20:30 (Mon–Sun) and restore the kiosk display at 08:00 (Mon–Fri), handling both always-on tablets and tablets that MEET IN TOUCH reboots nightly.

**Architecture:** A new `StandbyActivity` shows a black screen and sends Java-side heartbeats with `status=sleep`. A new `ScheduleReceiver` handles three daily `AlarmManager` alarms (standby at 20:30, wake at 08:00, WebView refresh at 06:00). `BootReceiver` gains a time-of-day check so it launches the correct activity immediately on boot. The dashboard gains a third visual state (🌙 Standby) for rooms reporting `status=sleep`.

**Tech Stack:** Java (Android API 19+), `AlarmManager.RTC_WAKEUP`, OkHttp 3.12.x (already in deps), `WindowManager.LayoutParams.screenBrightness`, `Handler` for touch-to-dim timer.

## Global Constraints

- `minSdkVersion 19` (Android 4.4) — no lambda, no API 21+ without guards
- OkHttp already on classpath as `com.squareup.okhttp3:okhttp:3.12.13` — do not add a new dep
- Conscrypt already installed as a static singleton in `UpdateChecker` — do not install it again
- `PREFS_NAME = "ris_kiosk_prefs"` — all SharedPreferences use this name
- Package: `th.co.central.ris.bootlauncher`
- Heartbeat URL: `https://ris-display.ris-display.workers.dev/api/heartbeat`
- No Kotlin, no data-binding, no ViewBinding — plain Java + programmatic views
- Commit after every task

---

## File Map

| File | Change |
|---|---|
| `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/StandbyActivity.java` | **Create** |
| `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java` | **Create** |
| `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java` | **Modify** — add time/day check |
| `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/MainActivity.java` | **Modify** — register 3 alarms |
| `boot-launcher/app/src/main/AndroidManifest.xml` | **Modify** — register new activity + receiver |
| `dashboard.html` | **Modify** — add sleep visual state |

---

### Task 1: StandbyActivity

**Files:**
- Create: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/StandbyActivity.java`

**Interfaces:**
- Produces: `StandbyActivity` — started via `Intent(context, StandbyActivity.class)` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`

- [ ] **Step 1: Create StandbyActivity.java**

```java
package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class StandbyActivity extends Activity {

    private static final String PREFS_NAME      = "ris_kiosk_prefs";
    private static final String HEARTBEAT_URL   = "https://ris-display.ris-display.workers.dev/api/heartbeat";
    private static final long   HB_INTERVAL_MS  = 20 * 60 * 1000L; // 20 minutes
    private static final long   DIM_DELAY_MS    = 30 * 1000L;       // 30 seconds after touch

    private static final OkHttpClient HB_CLIENT = new OkHttpClient();

    private Handler  handler;
    private TextView hintText;
    private boolean  isDimmed = true;

    private final Runnable dimRunnable = new Runnable() {
        @Override public void run() { dim(); }
    };

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override public void run() {
            sendHeartbeat();
            handler.postDelayed(this, HB_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyRotation();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // No FLAG_KEEP_SCREEN_ON — let Android system timeout also apply

        handler = new Handler();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        hintText = new TextView(this);
        hintText.setText("Outside office hours\nResumes 08:00 weekdays");
        hintText.setTextColor(Color.parseColor("#3a4d6b"));
        hintText.setTextSize(14);
        hintText.setGravity(Gravity.CENTER);
        hintText.setVisibility(View.GONE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        root.addView(hintText, lp);
        setContentView(root);

        dim();

        // Initial heartbeat after 30s, then every 20 min
        handler.postDelayed(heartbeatRunnable, 30_000L);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (isDimmed) {
                brighten();
            } else {
                handler.removeCallbacks(dimRunnable);
                handler.postDelayed(dimRunnable, DIM_DELAY_MS);
            }
        }
        return true;
    }

    private void dim() {
        isDimmed = true;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        hintText.setVisibility(View.GONE);
    }

    private void brighten() {
        isDimmed = false;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        hintText.setVisibility(View.VISIBLE);
        handler.removeCallbacks(dimRunnable);
        handler.postDelayed(dimRunnable, DIM_DELAY_MS);
    }

    private void applyRotation() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean rotated = p.getBoolean("screen_rotated", false);
        setRequestedOrientation(rotated
            ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void sendHeartbeat() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String room     = p.getString("room_email", "");
        final String roomname = p.getString("room_name",  "");
        if (room.isEmpty()) return;

        String version = "unknown";
        try {
            version = getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        final String ver = version;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("room",       room);
                    body.put("roomname",   roomname);
                    body.put("status",     "sleep");
                    body.put("launchMode", "standby");
                    body.put("version",    ver);
                    body.put("uptime",     0);

                    RequestBody rb = RequestBody.create(
                        MediaType.parse("application/json"),
                        body.toString());
                    Request req = new Request.Builder()
                        .url(HEARTBEAT_URL)
                        .post(rb)
                        .build();
                    HB_CLIENT.newCall(req).execute().close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    @Override
    public void onBackPressed() { /* block back in kiosk mode */ }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(dimRunnable);
        handler.removeCallbacks(heartbeatRunnable);
        super.onDestroy();
    }
}
```

- [ ] **Step 2: Manual test — verify it compiles**

```
cd boot-launcher
./gradlew assembleRelease
```
Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 3: Commit**

```
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/StandbyActivity.java
git commit -m "feat: add StandbyActivity — black screen + sleep heartbeat"
```

---

### Task 2: ScheduleReceiver

**Files:**
- Create: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java`

**Interfaces:**
- Consumes: `StandbyActivity` (Task 1), `KioskWebViewActivity` (existing), `BootReceiver.launchWebView()` (existing)
- Produces: `ScheduleReceiver` — receives three intent actions:
  - `th.co.central.ris.bootlauncher.ACTION_STANDBY`
  - `th.co.central.ris.bootlauncher.ACTION_WAKE`
  - `th.co.central.ris.bootlauncher.ACTION_RESTART`

- [ ] **Step 1: Create ScheduleReceiver.java**

```java
package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Calendar;

public class ScheduleReceiver extends BroadcastReceiver {

    static final String ACTION_STANDBY = "th.co.central.ris.bootlauncher.ACTION_STANDBY";
    static final String ACTION_WAKE    = "th.co.central.ris.bootlauncher.ACTION_WAKE";
    static final String ACTION_RESTART = "th.co.central.ris.bootlauncher.ACTION_RESTART";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (ACTION_STANDBY.equals(action)) {
            launchStandby(context);

        } else if (ACTION_WAKE.equals(action)) {
            int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            boolean isWeekend = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
            if (!isWeekend) {
                BootReceiver.launchWebView(context);
            }
            // Weekend: stay in standby, do nothing

        } else if (ACTION_RESTART.equals(action)) {
            // Switch to StandbyActivity to destroy the WebView and free memory.
            // The 08:00 ACTION_WAKE alarm will restore KioskWebViewActivity.
            launchStandby(context);
        }
    }

    static void launchStandby(Context context) {
        try {
            Intent i = new Intent(context, StandbyActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: Verify compile**

```
./gradlew assembleRelease
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java
git commit -m "feat: add ScheduleReceiver — handles standby/wake/restart alarms"
```

---

### Task 3: BootReceiver — time-of-day check on boot

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java`

**Interfaces:**
- Consumes: `ScheduleReceiver.launchStandby()` (Task 2)
- Produces: `BootReceiver.launchWebView(Context)` — unchanged, still public static

- [ ] **Step 1: Replace BootReceiver.java**

The 90-second delay is preserved for the kiosk path (MEET IN TOUCH needs time to settle). The standby path has no delay — showing a black screen immediately on boot is fine.

```java
package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;

public class BootReceiver extends BroadcastReceiver {

    private static final long BOOT_DELAY_MS = 90000; // 90s — let Android + MEET IN TOUCH settle

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;

        try {
            Intent svc = new Intent(context, ForegroundWatchService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {}

        // Check current time to decide which activity to launch
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int day  = now.get(Calendar.DAY_OF_WEEK);
        boolean isWeekend    = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
        boolean isOfficeHours = !isWeekend && hour >= 8 && hour < 20;

        if (isOfficeHours) {
            // Delay so MEET IN TOUCH can open first, then our WebView takes over
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(BOOT_DELAY_MS); } catch (InterruptedException e) {}
                    launchWebView(context);
                }
            }).start();
        } else {
            // Off-hours or weekend — go straight to standby (no delay needed)
            ScheduleReceiver.launchStandby(context);
        }
    }

    static void launchWebView(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: Verify compile**

```
./gradlew assembleRelease
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java
git commit -m "feat: BootReceiver checks time on boot — standby if off-hours"
```

---

### Task 4: MainActivity — register 3 AlarmManager alarms

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/MainActivity.java`

**Interfaces:**
- Consumes: `ScheduleReceiver.ACTION_STANDBY`, `ACTION_WAKE`, `ACTION_RESTART` (Task 2)

- [ ] **Step 1: Add imports to MainActivity.java**

Add these imports after the existing import block (around line 20):

```java
import android.app.AlarmManager;
import android.app.PendingIntent;
import java.util.Calendar;
```

- [ ] **Step 2: Add scheduleAlarms() method to MainActivity**

Add this method before the closing `}` of the class (before the final `}`):

```java
private void scheduleAlarms() {
    AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
    if (am == null) return;

    int flags = android.os.Build.VERSION.SDK_INT >= 23
        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        : PendingIntent.FLAG_UPDATE_CURRENT;

    // --- 20:30 daily → standby ---
    PendingIntent piStandby = PendingIntent.getBroadcast(this, 1,
        new Intent(ScheduleReceiver.ACTION_STANDBY)
            .setClass(this, ScheduleReceiver.class), flags);
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
        nextOccurrence(20, 30), AlarmManager.INTERVAL_DAY, piStandby);

    // --- 08:00 daily → wake (receiver checks weekday) ---
    PendingIntent piWake = PendingIntent.getBroadcast(this, 2,
        new Intent(ScheduleReceiver.ACTION_WAKE)
            .setClass(this, ScheduleReceiver.class), flags);
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
        nextOccurrence(8, 0), AlarmManager.INTERVAL_DAY, piWake);

    // --- 06:00 daily → WebView refresh (destroy + re-standby; 08:00 wakes properly) ---
    PendingIntent piRestart = PendingIntent.getBroadcast(this, 3,
        new Intent(ScheduleReceiver.ACTION_RESTART)
            .setClass(this, ScheduleReceiver.class), flags);
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
        nextOccurrence(6, 0), AlarmManager.INTERVAL_DAY, piRestart);
}

private static long nextOccurrence(int hour, int minute) {
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, hour);
    cal.set(Calendar.MINUTE, minute);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
        cal.add(Calendar.DAY_OF_YEAR, 1);
    }
    return cal.getTimeInMillis();
}
```

- [ ] **Step 3: Call scheduleAlarms() from onCreate()**

In `onCreate()`, add the call just before `UpdateChecker.check(this)` (around line 217):

```java
    scheduleAlarms();

    // Check for APK update in background
    UpdateChecker.check(this);
```

- [ ] **Step 4: Verify compile**

```
./gradlew assembleRelease
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/MainActivity.java
git commit -m "feat: MainActivity registers daily standby/wake/restart alarms"
```

---

### Task 5: AndroidManifest — register new components

**Files:**
- Modify: `boot-launcher/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add StandbyActivity declaration**

Inside `<application>`, after the existing `KioskWebViewActivity` `<activity>` block, add:

```xml
        <activity
            android:name=".StandbyActivity"
            android:label="RIS Standby"
            android:screenOrientation="portrait"
            android:theme="@android:style/Theme.Holo.NoActionBar.Fullscreen"
            android:exported="false" />
```

- [ ] **Step 2: Add ScheduleReceiver declaration**

Inside `<application>`, after the `StandbyActivity` block, add:

```xml
        <receiver
            android:name=".ScheduleReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="th.co.central.ris.bootlauncher.ACTION_STANDBY" />
                <action android:name="th.co.central.ris.bootlauncher.ACTION_WAKE" />
                <action android:name="th.co.central.ris.bootlauncher.ACTION_RESTART" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 3: Verify compile and check manifest**

```
./gradlew assembleRelease
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Bump version**

In `boot-launcher/app/build.gradle`, update:
```
versionCode 55
versionName "5.24"
```

In `apk-version.json`, update:
```json
{
  "versionCode": 55,
  "versionName": "5.24",
  "apkUrl": "https://ris-display.ris-display.workers.dev/api/apk"
}
```

- [ ] **Step 5: Commit**

```
git add boot-launcher/app/src/main/AndroidManifest.xml
git add boot-launcher/app/build.gradle
git add apk-version.json
git commit -m "feat: register StandbyActivity + ScheduleReceiver in manifest (v5.24)"
```

---

### Task 6: Dashboard — add sleep/standby visual state

**Files:**
- Modify: `dashboard.html`

**Context:** The admin dashboard's `renderAdminRooms()` function (around line 1053) currently shows two states: green (online) and red (offline). The Cloudflare Worker already stores whatever `status` string is sent — a room in standby will have `isOnline: true` (heartbeat < 30 min) and `status: "sleep"`.

- [ ] **Step 1: Update dotColor and statusText logic in renderAdminRooms()**

Find this block (around line 1068–1074):

```javascript
    const isOnline = r && r.isOnline;
    const needsAuth = false; \ removed: proxy auth handles this
    const needsTap = r && isOnline && r.launchMode === 'browser';
    const dotColor = !r ? '#555' : (isOnline ? (needsAuth ? '#ff9500' : '#00d68f') : '#ff3333');
    const statusText = !r ? 'No heartbeat' :
      (isOnline ? (r.status === 'needs_tap' ? '👆 Waiting for tap' :
        'Online — ' + r.status) : 'Offline (' + r.lastSeenMinutes + 'm ago)');
```

Replace with:

```javascript
    const isOnline = r && r.isOnline;
    const isSleep  = isOnline && r.status === 'sleep';
    const needsAuth = false; // removed: proxy auth handles this
    const needsTap = r && isOnline && !isSleep && r.launchMode === 'browser';
    const dotColor = !r ? '#555' :
      (!isOnline ? '#ff3333' :
       isSleep   ? '#4a5568' :
       '#00d68f');
    const statusText = !r ? 'No heartbeat' :
      (!isOnline  ? 'Offline (' + r.lastSeenMinutes + 'm ago)' :
       isSleep    ? '🌙 Standby' :
       r.status === 'needs_tap' ? '👆 Waiting for tap' :
       'Online — ' + r.status);
```

- [ ] **Step 2: Update renderAdminStats() to count sleep separately**

Find this block (around line 1040–1044):

```javascript
function renderAdminStats(rooms, incidents) {
  const online = rooms.filter(r => r.isOnline).length;
  const offline = rooms.length > 0 ? rooms.filter(r => !r.isOnline).length : 0;
  document.getElementById('adm-online').textContent = online;
  document.getElementById('adm-offline').textContent = offline;
```

Replace with:

```javascript
function renderAdminStats(rooms, incidents) {
  const sleeping = rooms.filter(r => r.isOnline && r.status === 'sleep').length;
  const online  = rooms.filter(r => r.isOnline && r.status !== 'sleep').length;
  const offline = rooms.filter(r => !r.isOnline).length;
  document.getElementById('adm-online').textContent  = online + (sleeping ? ' (+' + sleeping + '🌙)' : '');
  document.getElementById('adm-offline').textContent = offline;
```

- [ ] **Step 3: Verify dashboard loads without JS errors**

Open `dashboard.html` in a browser (or use `edge://inspect` if testing on device). Check the browser console — no errors expected.

- [ ] **Step 4: Commit**

```
git add dashboard.html
git commit -m "feat: dashboard shows 🌙 Standby state for sleep heartbeats"
```

---

## Testing Sequence (Manual — ADB)

After all tasks are merged and APK v5.24 installed on a test tablet:

**Test 1 — Standby alarm fires:**
```
# Trigger standby immediately via broadcast
adb -s <IP>:5555 shell am broadcast -a th.co.central.ris.bootlauncher.ACTION_STANDBY \
  -n th.co.central.ris.bootlauncher/.ScheduleReceiver
```
Expected: screen goes black within 2 seconds.

**Test 2 — Touch to brighten:**
Tap the screen.
Expected: screen brightens, "Outside office hours" text appears. Dims again after 30 seconds.

**Test 3 — Wake alarm fires (weekday):**
```
adb -s <IP>:5555 shell am broadcast -a th.co.central.ris.bootlauncher.ACTION_WAKE \
  -n th.co.central.ris.bootlauncher/.ScheduleReceiver
```
Expected: KioskWebViewActivity launches, kiosk display appears.

**Test 4 — Wake alarm on weekend (no-op):**
Change device date to Saturday, then run Test 3 broadcast.
Expected: nothing happens — device stays in standby.

**Test 5 — Restart alarm refreshes WebView:**
```
adb -s <IP>:5555 shell am broadcast -a th.co.central.ris.bootlauncher.ACTION_RESTART \
  -n th.co.central.ris.bootlauncher/.ScheduleReceiver
```
Expected: StandbyActivity launches (WebView destroyed). Then run Test 3 — kiosk should reload fresh.

**Test 6 — Boot off-hours:**
```
adb -s <IP>:5555 shell reboot
```
Wait 2 minutes (boot + 30s settle). Expected: StandbyActivity shows (black screen) if current time is outside Mon–Fri 08:00–20:00.

**Test 7 — Sleep heartbeat in dashboard:**
After Test 1, wait 30 seconds, then open admin dashboard.
Expected: that room shows grey dot + "🌙 Standby" instead of red "Offline".

**Test 8 — Heartbeat keeps room alive:**
After 25 minutes in standby mode, check dashboard.
Expected: room still shows 🌙 Standby (not Offline), confirming heartbeat fired.

---

## Deployment

After local testing passes all 8 tests:

```
git push origin main
```

GitHub Actions builds the APK. Tablets auto-update on next relaunch (via existing `UpdateChecker`). No manual sideload needed — v5.24 will distribute automatically.
