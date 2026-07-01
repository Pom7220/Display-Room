# Native WebView APK Kiosk Shell — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch the kiosk container from Chrome to `KioskWebViewActivity` so Android-level fullscreen (`FLAG_FULLSCREEN`) controls the display — making `location.reload()` after auto-updates fully tap-free.

**Architecture:** `BootReceiver` launches `KioskWebViewActivity` (explicit Intent) instead of Chrome. The activity sets `FLAG_FULLSCREEN` on the Android window in `onCreate()` — this flag never drops during WebView page reloads. `ForegroundWatchService` already accepts the APK's own package as a valid foreground app; its recovery action is updated to restart `KioskWebViewActivity` instead of Chrome. A new `POST /api/token` route in the Cloudflare Worker serves ROPC tokens to `TokenFetcher` on every boot.

**Tech Stack:** Android 4.4 (API 19), Java, Cloudflare Workers (JavaScript)

## Global Constraints

- `minSdkVersion 19` — no API above 19 in new code unless already used in existing files
- ES5 only in Cloudflare Worker (already the pattern — `var`, not `const`/`let`)
- APK package: `th.co.central.ris.bootlauncher`
- All existing Cloudflare Worker routes unchanged — new route is purely additive
- Do not remove `launchChrome()` or `bringChromeToFront()` from `BootReceiver` — they may be needed as fallbacks

---

## File Map

| File | Change |
|---|---|
| `boot-launcher/app/src/main/AndroidManifest.xml` | Add `KioskWebViewActivity` declaration |
| `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java` | Add `launchKioskActivity()`, replace boot thread calls |
| `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java` | Update recovery call + javadoc |
| `cloudflare-worker.js` | Add `POST /api/token` route + `handleTokenFetch()` |

---

## Task 1 — Register `KioskWebViewActivity` in Manifest

**Files:**
- Modify: `boot-launcher/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `KioskWebViewActivity` is a declared, launchable activity — required before Task 2's explicit Intent will resolve

- [ ] **Step 1: Add activity declaration**

Open `boot-launcher/app/src/main/AndroidManifest.xml`. After the closing `</activity>` tag of `MainActivity` (line 36), insert:

```xml
        <activity
            android:name=".KioskWebViewActivity"
            android:label="RIS Kiosk Display"
            android:screenOrientation="portrait"
            android:theme="@android:style/Theme.Holo.NoActionBar.Fullscreen"
            android:exported="false" />
```

The full `<application>` block should now read:

```xml
    <application
        android:allowBackup="false"
        android:label="RIS Kiosk"
        android:icon="@drawable/ic_launcher">

        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter android:priority="999">
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </receiver>

        <activity
            android:name=".MainActivity"
            android:label="RIS Kiosk"
            android:screenOrientation="portrait"
            android:theme="@android:style/Theme.Holo.NoActionBar.Fullscreen"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".KioskWebViewActivity"
            android:label="RIS Kiosk Display"
            android:screenOrientation="portrait"
            android:theme="@android:style/Theme.Holo.NoActionBar.Fullscreen"
            android:exported="false" />

        <service
            android:name=".ForegroundWatchService"
            android:exported="false" />

        <!-- Accessibility service for auto-tap fullscreen -->
        <service
            android:name=".KioskAccessibilityService"
            android:label="RIS Kiosk Auto-tap"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
```

- [ ] **Step 2: Verify it compiles**

```
cd boot-launcher
./gradlew assembleDebug 2>&1 | tail -5
```

Expected output ends with: `BUILD SUCCESSFUL`

If it fails with "unresolved class KioskWebViewActivity" — the Java file exists at `app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java`. Confirm the file is present before proceeding.

- [ ] **Step 3: Commit**

```
git add boot-launcher/app/src/main/AndroidManifest.xml
git commit -m "feat: register KioskWebViewActivity in manifest"
```

---

## Task 2 — Update `BootReceiver` to launch `KioskWebViewActivity`

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java`

**Interfaces:**
- Consumes: `KioskWebViewActivity` declared in manifest (Task 1)
- Produces: `BootReceiver.launchKioskActivity(Context)` — static method used by Task 3

- [ ] **Step 1: Replace the boot thread and add `launchKioskActivity()`**

Replace the entire content of `BootReceiver.java` with:

```java
package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Boot launcher — starts KioskWebViewActivity after device boot.
 * KioskWebViewActivity provides Android-level fullscreen (FLAG_FULLSCREEN)
 * that is reload-proof — page reloads for auto-updates never exit fullscreen.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String PREFS_NAME = "ris_kiosk_prefs";
    private static final long FIRST_LAUNCH_MS = 90000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            try {
                context.startService(new Intent(context, ForegroundWatchService.class));
            } catch (Exception e) {}

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    launchKioskActivity(context);
                }
            }).start();
        }
    }

    static void launchKioskActivity(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback to Chrome if WebView activity fails to start
            launchChrome(context);
        }
    }

    static void launchChrome(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName = prefs.getString("room_name", "");

        StringBuilder url = new StringBuilder("https://ris-display.ris-display.workers.dev/");
        url.append("?nocache=").append(System.currentTimeMillis());
        if (roomEmail.length() > 0) url.append("&room=").append(Uri.encode(roomEmail));
        if (roomName.length() > 0) url.append("&roomname=").append(Uri.encode(roomName));

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url.toString()));
            intent.setPackage(CHROME_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(fallback);
            } catch (Exception e2) {}
        }
    }

    static void bringChromeToFront(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(CHROME_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {}
    }
}
```

Key changes from original:
- Boot thread now calls `launchKioskActivity()` instead of `launchChrome()`
- Removed the second 180s `bringChromeToFront()` call
- `launchKioskActivity()` falls back to `launchChrome()` if the activity fails to start
- `launchChrome()` and `bringChromeToFront()` kept as fallbacks

- [ ] **Step 2: Verify it compiles**

```
cd boot-launcher
./gradlew assembleDebug 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java
git commit -m "feat: boot via KioskWebViewActivity instead of Chrome"
```

---

## Task 3 — Update `ForegroundWatchService` recovery action

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java`

**Interfaces:**
- Consumes: `BootReceiver.launchKioskActivity(Context)` (Task 2)

- [ ] **Step 1: Update recovery call and javadoc**

Replace the entire content of `ForegroundWatchService.java` with:

```java
package th.co.central.ris.bootlauncher;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

import java.util.List;

/**
 * Background watchdog — keeps KioskWebViewActivity in the foreground.
 *
 * Checks every 5 minutes if the kiosk app is the top app. If something else
 * (e.g., home screen, another app) is on top, it relaunches KioskWebViewActivity.
 *
 * Runs as an Android Service — works even when the WebView is suspended.
 */
public class ForegroundWatchService extends Service {

    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final long CHECK_INTERVAL_MS = 300000; // 5 minutes
    private static final long INITIAL_DELAY_MS = 240000;  // 4 minutes (let boot sequence finish)

    private Handler handler;
    private boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            handler.postDelayed(checkRunnable, INITIAL_DELAY_MS);
        }
        return START_STICKY;
    }

    private Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndRestore();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private void checkAndRestore() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;

            // getRunningTasks works on API 19 (Android 4.4)
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;

            String topPackage = tasks.get(0).topActivity.getPackageName();
            String ownPackage = getPackageName();

            if (!ownPackage.equals(topPackage)) {
                // Kiosk activity is not on top — relaunch it
                BootReceiver.launchKioskActivity(getApplicationContext());
            }
        } catch (Exception e) {
            // Non-critical — will retry on next interval
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) {
            handler.removeCallbacks(checkRunnable);
        }
        super.onDestroy();
    }
}
```

Key changes from original:
- Recovery calls `BootReceiver.launchKioskActivity()` instead of `BootReceiver.bringChromeToFront()`
- Watchdog condition simplified: only `ownPackage` must be foreground (Chrome no longer accepted as valid foreground)
- `CHROME_PACKAGE` kept declared (compiler will warn if unused — safe to keep for future fallback reference)

- [ ] **Step 2: Verify it compiles**

```
cd boot-launcher
./gradlew assembleDebug 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ForegroundWatchService.java
git commit -m "feat: watchdog relaunches KioskWebViewActivity on recovery"
```

---

## Task 4 — Add `POST /api/token` to Cloudflare Worker

**Files:**
- Modify: `cloudflare-worker.js`

**Interfaces:**
- Produces: `POST /api/token` — called by `TokenFetcher.java` on boot with `X-Admin-Key` header. Returns `{"ok":true,"access_token":"...","refresh_token":"...","id_token":"...","client_id":"...","expires_in":3600}` on success, or `{"ok":false,"error":"..."}` on failure.

- [ ] **Step 1: Add the route in the fetch handler**

In `cloudflare-worker.js`, find the block that starts with `// ── API ROUTES ──` (around line 42). After the existing `if (path === '/api/test-reauth' ...)` block and before the final `return jsonResponse({ error: 'Not found' }, 404);`, add:

```javascript
      // POST /api/token — APK fetches ROPC tokens on boot (TokenFetcher.java)
      if (path === '/api/token' && method === 'POST') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        return handleTokenFetch(env);
      }
```

- [ ] **Step 2: Add `handleTokenFetch()` function**

At the bottom of `cloudflare-worker.js`, before the `// HELPERS` section, add:

```javascript
// ═══════════════════════════════════════
// TOKEN FETCH — ROPC tokens for APK boot
// ═══════════════════════════════════════
// Called by TokenFetcher.java on every boot.
// Returns tokens directly (not queued as a command).

async function handleTokenFetch(env) {
  var svcUser = env.RIS_SVC_USER || '';
  var svcPass = env.RIS_SVC_PASSWORD || '';
  var tenantId = env.RIS_TENANT_ID || '';
  var clientId = env.RIS_CLIENT_ID || '';

  if (!svcUser || !svcPass || !tenantId || !clientId) {
    return jsonResponse({
      ok: false,
      error: 'Token fetch not configured. Set RIS_SVC_USER, RIS_SVC_PASSWORD, RIS_TENANT_ID, RIS_CLIENT_ID in Worker secrets.'
    }, 400);
  }

  try {
    var tokenUrl = 'https://login.microsoftonline.com/' + tenantId + '/oauth2/v2.0/token';
    var body = 'client_id=' + encodeURIComponent(clientId)
      + '&scope=' + encodeURIComponent('Calendars.ReadWrite Calendars.ReadWrite.Shared Mail.Send User.Read openid profile offline_access')
      + '&username=' + encodeURIComponent(svcUser)
      + '&password=' + encodeURIComponent(svcPass)
      + '&grant_type=password';

    var resp = await fetch(tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    });

    var data = await resp.json();

    if (data.error) {
      return jsonResponse({ ok: false, error: data.error_description || data.error }, 401);
    }

    return jsonResponse({
      ok: true,
      access_token: data.access_token || '',
      refresh_token: data.refresh_token || '',
      id_token: data.id_token || '',
      client_id: clientId,
      expires_in: data.expires_in || 3600
    });
  } catch (e) {
    return jsonResponse({ ok: false, error: e.message }, 500);
  }
}
```

- [ ] **Step 3: Smoke-test the new route locally with curl**

Deploy to Cloudflare (or use `wrangler dev` locally), then:

```bash
curl -s -X POST https://ris-display.ris-display.workers.dev/api/token \
  -H "X-Admin-Key: YOUR_ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d "{}" | jq .
```

Expected success response:
```json
{
  "ok": true,
  "access_token": "eyJ...",
  "refresh_token": "0.A...",
  "id_token": "eyJ...",
  "client_id": "80648895-4acf-4ac5-b4a3-c5bf6bc98983",
  "expires_in": 3600
}
```

If `ok: false` with "Token fetch not configured" — the Cloudflare secrets (`RIS_SVC_USER`, `RIS_SVC_PASSWORD`, `RIS_TENANT_ID`, `RIS_CLIENT_ID`) are not set. Set them via the Cloudflare dashboard → Worker → Settings → Variables and Secrets.

If `ok: false` with an Azure AD error — check that the service account `rismeetingroomsystem@central.co.th` has no MFA and that ROPC is enabled for the app registration.

- [ ] **Step 4: Verify no existing routes broken**

```bash
# Proxy still works
curl -I https://ris-display.ris-display.workers.dev/ | head -5
# Expected: HTTP/2 200

# Heartbeat still works
curl -s -X POST https://ris-display.ris-display.workers.dev/api/heartbeat \
  -H "Content-Type: application/json" \
  -d '{"room":"test@central.co.th","roomname":"Test","status":"avail","version":"test"}' | jq .ok
# Expected: true
```

- [ ] **Step 5: Commit**

```
git add cloudflare-worker.js
git commit -m "feat: add POST /api/token for APK boot token fetch"
```

---

## Task 5 — Build APK and Deploy to Tablets

**Files:**
- No source changes — this task builds and deploys the APK from Tasks 1–3

- [ ] **Step 1: Trigger APK build**

Push the branch (or merge to main) to trigger GitHub Actions:

```
git push origin main
```

Wait for the `build-apk.yml` workflow to complete. Download the signed APK from the workflow artifacts.

- [ ] **Step 2: Install on one tablet first (smoke test)**

Connect the first tablet via USB. Enable ADB if not already enabled (Settings → Developer Options → USB Debugging).

```bash
adb devices
# Expected: one device listed as "device" (not "unauthorized")

adb install -r path/to/ris-kiosk.apk
# Expected: Success
```

- [ ] **Step 3: Reboot the tablet and verify**

```bash
adb reboot
```

After reboot (~2 minutes):
- ✅ `KioskWebViewActivity` opens automatically (loading screen "RIS Starting room display...")
- ✅ Tokens fetched — room display loads without login screen
- ✅ Display is fullscreen — no URL bar, no status bar
- ✅ Room name and calendar data shown

If the login screen appears (MSAL redirect): token injection may be failing — check logcat:
```bash
adb logcat | grep -i "RIS\|kiosk\|token"
```

- [ ] **Step 4: Verify fullscreen survives a simulated update reload**

While the display is running, trigger a reload via the admin dashboard command (`reload`), or via ADB:

```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED
```

Or simulate a version-check reload by temporarily changing `APP_VERSION` in a test build. Confirm:
- ✅ Display reloads
- ✅ Fullscreen does NOT drop
- ✅ No tap required

- [ ] **Step 5: Deploy to remaining 11 tablets**

For each remaining tablet:
```bash
adb install -r path/to/ris-kiosk.apk
adb reboot
```

Verify each comes up correctly before moving to the next.

- [ ] **Step 6: Final commit (tag release)**

```
git tag v-apk-webview-kiosk-1.0
git push origin --tags
```
