# OTA Silent APK Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable fully silent over-the-air APK updates on rooted LG Android 4.4 tablets — automatically at 06:00 weekdays, and on-demand from the admin dashboard.

**Architecture:** `UpdateChecker.silentInstall()` downloads the APK and runs `su -c "pm install -r <path>"` via `Runtime.exec()` with a 60-second thread-join timeout. `ScheduleReceiver` calls it at 06:00 before launching standby; `KioskWebViewActivity` calls it when it receives a `perform_update` command via the existing `/api/command` KV poll. The dashboard "Update Now" button POSTs to a new `/api/admin/update` Worker endpoint that writes a single `cmd:perform_update:all` or `cmd:perform_update:ab` KV key which is checked alongside the per-room KV entry on every tablet poll.

**Tech Stack:** Java (API 19 / Android 4.4), OkHttp 3.12.x, Conscrypt 2.5.2, Cloudflare Workers KV, Cloudflare Worker (JS), vanilla JS (ES5) in dashboard.html and index.html.

## Global Constraints

- All Java must compile at `sourceCompatibility JavaVersion.VERSION_1_8` and run on `minSdkVersion 19` (Android 4.4). No lambdas, no streams, no `Process.waitFor(long, TimeUnit)` (API 26+), no `String.isEmpty()` alternatives — use `.length() == 0`.
- No Kotlin, no Rx, no new dependencies.
- All JS in `cloudflare-worker.js`, `dashboard.html`, and `index.html` must be ES5 (no arrow functions, no `const`/`let` in IE11-style contexts, no template literals in cloudflare-worker.js). dashboard.html and index.html already use `var`/`function` — match the style.
- `UpdateChecker.CLIENT` is built in a static block that installs Conscrypt first — do NOT touch it. `silentInstall()` reuses `CLIENT` as-is.
- Silent install uses `su -c "pm install -r <absolutePath>"`. The `-r` flag preserves SharedPreferences. Process timeout is implemented with `Thread.join(60000)` — NOT `Process.waitFor(long, TimeUnit)` (API 26+).
- `getApkFile(context)` already handles Android 10 vs 4.4 path split — reuse it exactly.
- Worker secrets (`RIS_ADMIN_KEY`, `RIS_KV`, etc.) are in Cloudflare dashboard only, never in source code.
- Admin endpoint requires `X-Admin-Key` header (value checked against `env.RIS_ADMIN_KEY`). Same pattern as existing command endpoints.
- Version bump on every commit: increment `versionCode` and `versionName` in `build.gradle`. Current: versionCode 89, versionName "5.58". Next: 90 / "5.59".
- All comments and log strings in English.

---

### Task 1: Add `silentInstall()` to `UpdateChecker.java`

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/UpdateChecker.java`

**Interfaces:**
- Produces: `public static void silentInstall(Context context, Runnable onNoUpdate, Runnable onFailure)` — runs on a new background thread; calls `onNoUpdate` if remote versionCode ≤ local; calls `onFailure` on network error, download error, or non-zero su exit; calls neither callback on success (process is killed by package manager).
- Produces: `private static void runCb(Runnable r)` — null-safe callback runner.

- [ ] **Step 1: Add `silentInstall()` and `runCb()` to `UpdateChecker.java`**

Insert after the closing brace of `triggerInstall()` (line 233), before the final `}` of the class (line 235):

```java
    /**
     * Downloads and silently installs a newer APK via root (su pm install -r).
     * Runs on a background thread. Calls onNoUpdate if already up-to-date,
     * onFailure if root is denied or install fails, neither on success
     * (the package manager kills this process after install).
     * Safe to call from a BroadcastReceiver — does not require Activity context.
     */
    public static void silentInstall(final Context context,
                                     final Runnable onNoUpdate,
                                     final Runnable onFailure) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Request req = new Request.Builder().url(VERSION_URL).build();
                    Response resp = CLIENT.newCall(req).execute();
                    if (!resp.isSuccessful()) { runCb(onFailure); return; }

                    JSONObject json = new JSONObject(resp.body().string());
                    int remoteCode = json.getInt("versionCode");
                    String apkUrl  = json.getString("apkUrl");

                    int localCode = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionCode;
                    if (remoteCode <= localCode) { runCb(onNoUpdate); return; }

                    File apkFile = getApkFile(context);
                    Response dlResp = CLIENT.newCall(
                        new Request.Builder().url(apkUrl).build()).execute();
                    if (!dlResp.isSuccessful()) { runCb(onFailure); return; }

                    InputStream is = dlResp.body().byteStream();
                    FileOutputStream fos = new FileOutputStream(apkFile);
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.close(); is.close();

                    final Process proc = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "pm install -r " + apkFile.getAbsolutePath()
                    });
                    Thread waiter = new Thread(new Runnable() {
                        @Override public void run() {
                            try { proc.waitFor(); } catch (InterruptedException ignored) {}
                        }
                    });
                    waiter.start();
                    waiter.join(60000);
                    if (waiter.isAlive()) { proc.destroy(); runCb(onFailure); return; }
                    if (proc.exitValue() != 0) { runCb(onFailure); return; }
                    // Exit 0: package manager will kill and restart this process.
                } catch (Exception e) { runCb(onFailure); }
            }
        }).start();
    }

    private static void runCb(Runnable r) { if (r != null) r.run(); }
```

- [ ] **Step 2: Verify the file compiles (no new imports needed)**

`InputStream` and `FileOutputStream` are already imported (lines 14-15). `JSONObject`, `File`, `Context`, `Runtime` are all already present. No new imports required.

Check by scanning the import block (lines 1-36) and confirming:
- `java.io.File` ✓ (line 13)
- `java.io.FileOutputStream` ✓ (line 14)
- `java.io.InputStream` ✓ (line 15)
- `android.content.Context` ✓ (line 7)
- `org.json.JSONObject` ✓ (line 18)

- [ ] **Step 3: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/UpdateChecker.java
git commit -m "feat(apk): add silentInstall() to UpdateChecker for root OTA updates"
```

---

### Task 2: Call `silentInstall()` from `ScheduleReceiver.java` at 06:00

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java` (lines 49-52)

**Interfaces:**
- Consumes: `UpdateChecker.silentInstall(Context, Runnable, Runnable)` from Task 1.
- Produces: The weekday ACTION_RESTART branch calls `silentInstall()` instead of `launchStandby()` directly. Both callbacks call `launchStandby(context)` so standby always follows regardless of update outcome.

- [ ] **Step 1: Replace direct `launchStandby()` call in ACTION_RESTART weekday branch**

Current lines 49-52 in `ScheduleReceiver.java`:
```java
            if (!restartWeekend) {
                logAlarmEvent(context, "restart");
                sendSleepHeartbeat(context);
                launchStandby(context);
```

Replace with:
```java
            if (!restartWeekend) {
                logAlarmEvent(context, "restart");
                sendSleepHeartbeat(context);
                final Context ctx = context;
                UpdateChecker.silentInstall(context,
                    new Runnable() { @Override public void run() { launchStandby(ctx); } },
                    new Runnable() { @Override public void run() { launchStandby(ctx); } }
                );
```

The closing `} else {` at line 53 and `setExactAlarm` at line 56 are unchanged.

- [ ] **Step 2: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java
git commit -m "feat(apk): call silentInstall at 06:00 restart before launching standby"
```

---

### Task 3: Handle `perform_update` command in `KioskWebViewActivity.java` + `index.html`

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java` — add `performUpdate()` to `KioskInterface`
- Modify: `index.html` — add `perform_update` branch in `handleRemoteCommand()`

**Interfaces:**
- Consumes: `UpdateChecker.silentInstall(Context, Runnable, Runnable)` from Task 1.
- Produces: JS calls `window.Android.performUpdate()` → Java runs `silentInstall(this, null, null)`.

- [ ] **Step 1: Add `performUpdate()` to `KioskInterface` in `KioskWebViewActivity.java`**

The `KioskInterface` inner class ends with `enableTestSleep()` at line 360-364, then `}` at line 365. Insert after `enableTestSleep()`, before the closing `}` of `KioskInterface`:

```java
        @JavascriptInterface
        public void performUpdate() {
            UpdateChecker.silentInstall(KioskWebViewActivity.this, null, null);
        }
```

Full context for the edit — current lines 359-365:
```java
        @JavascriptInterface
        public void enableTestSleep() {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean("test_sleep_enabled", true).apply();
            ScheduleReceiver.setTestAlarms(KioskWebViewActivity.this);
        }
    }
```

After edit, lines 359-368:
```java
        @JavascriptInterface
        public void enableTestSleep() {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean("test_sleep_enabled", true).apply();
            ScheduleReceiver.setTestAlarms(KioskWebViewActivity.this);
        }

        @JavascriptInterface
        public void performUpdate() {
            UpdateChecker.silentInstall(KioskWebViewActivity.this, null, null);
        }
    }
```

- [ ] **Step 2: Add `perform_update` handler in `index.html`**

Find `handleRemoteCommand` in `index.html` — the `enable_test_sleep` handler is at line 1946:
```javascript
        } else if (cmd === 'enable_test_sleep') {
          if (window.Android && window.Android.enableTestSleep) window.Android.enableTestSleep();
```

Add the `perform_update` branch immediately after:
```javascript
        } else if (cmd === 'perform_update') {
          if (window.Android && window.Android.performUpdate) window.Android.performUpdate();
```

- [ ] **Step 3: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java
git add index.html
git commit -m "feat(apk): handle perform_update command — calls silentInstall from KioskInterface"
```

---

### Task 4: Add `perform_update` to `cloudflare-worker.js`

**Files:**
- Modify: `cloudflare-worker.js`

**Changes needed:**
1. Add `'perform_update'` to `validCommands` in `handleCommandSet()` (line 399).
2. Add a new `POST /api/admin/update` endpoint — writes `cmd:perform_update:all` or `cmd:perform_update:ab` KV key with 5-minute TTL.
3. In `handleCommandGet()`, check the bulk KV keys in addition to per-room keys before returning `{ command: null }`.

**Interfaces:**
- Produces: `POST /api/admin/update` — body `{ "target": "all" | "ab" }`, requires `X-Admin-Key` header. Returns `{ "ok": true, "target": "..." }`.
- Produces: `GET /api/command?room=<email>` now also checks `cmd:perform_update:all` (all rooms) and `cmd:perform_update:ab` (Macchiato + Viennese only) KV keys.

**Macchiato and Viennese room emails** (from `RIS_ROOMS` in dashboard.html):
- Macchiato: `macchiato@central.co.th`
- Viennese: `viennese@central.co.th`

- [ ] **Step 1: Add `'perform_update'` to `validCommands`**

Current line 399:
```javascript
    var validCommands = ['reload', 'clear_tokens', 'clear_config', 'force_fullscreen', 're_auth', 're_auth_remote', 'fetchcal', 'auto_tap', 'set_tablet_key', 'enable_test_sleep'];
```

Replace with:
```javascript
    var validCommands = ['reload', 'clear_tokens', 'clear_config', 'force_fullscreen', 're_auth', 're_auth_remote', 'fetchcal', 'auto_tap', 'set_tablet_key', 'enable_test_sleep', 'perform_update'];
```

- [ ] **Step 2: Find `handleCommandGet()` and extend it to check bulk OTA keys**

Search for `handleCommandGet` in `cloudflare-worker.js`. It reads `cmd:<room>` from KV and returns `{ command }`. Extend it to also check `cmd:perform_update:ab` (for Macchiato/Viennese) and `cmd:perform_update:all` (for all rooms) — checked after the per-room key, before returning null.

Find the section in `handleCommandGet` that returns no command (the null/empty branch). The existing logic looks like:
```javascript
    var stored = await env.RIS_KV.get('cmd:' + room);
    if (!stored) return jsonResponse({ command: null });
```

Replace with:
```javascript
    var stored = await env.RIS_KV.get('cmd:' + room);
    if (!stored) {
      // Check bulk OTA update keys
      var isAb = (room === 'macchiato@central.co.th' || room === 'viennese@central.co.th');
      var otaKey = isAb ? await env.RIS_KV.get('cmd:perform_update:ab') : null;
      if (!otaKey) otaKey = await env.RIS_KV.get('cmd:perform_update:all');
      if (otaKey) return jsonResponse({ command: 'perform_update' });
      return jsonResponse({ command: null });
    }
```

Note: The bulk OTA keys are NOT deleted on read — they expire via TTL (5 minutes) so all tablets in the target group pick them up within the next poll cycle.

- [ ] **Step 3: Add `handleAdminUpdate()` function**

Add a new function after `handleCommandSet()`:

```javascript
async function handleAdminUpdate(request, env) {
  try {
    var adminKey = request.headers.get('X-Admin-Key') || '';
    if (adminKey !== (env.RIS_ADMIN_KEY || '')) {
      return jsonResponse({ error: 'Unauthorized' }, 401);
    }
    var data = await request.json();
    var target = data.target === 'ab' ? 'ab' : 'all';
    var kvKey = 'cmd:perform_update:' + target;
    await env.RIS_KV.put(kvKey, '1', { expirationTtl: 300 }); // 5 min TTL
    return jsonResponse({ ok: true, target: target });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}
```

- [ ] **Step 4: Wire `POST /api/admin/update` in the main router**

Find the URL routing section in `cloudflare-worker.js` (the main `fetch` handler or router). Add the new endpoint alongside the existing command endpoints:

```javascript
  if (method === 'POST' && pathname === '/api/admin/update') {
    return handleAdminUpdate(request, env);
  }
```

Place it in the same block as the other `/api/admin/*` or `/api/command` routes.

- [ ] **Step 5: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat(worker): add perform_update command + POST /api/admin/update endpoint"
```

---

### Task 5: Add "Update Now" button to `dashboard.html`

**Files:**
- Modify: `dashboard.html`

**Interfaces:**
- Consumes: `POST /api/admin/update` from Task 4.
- Produces: "⬆ Update Now" button with A/B / ALL selector in the admin smart actions bar (line 1361-1364 area). Uses existing `MONITOR_URL`, `adminKey`, `showToast()` patterns.

- [ ] **Step 1: Add `triggerOtaUpdate(target)` function**

Add after `bulkCommand()` (around line 1407):

```javascript
async function triggerOtaUpdate(target) {
  if (!confirm('Send OTA update to ' + (target === 'ab' ? 'Macchiato & Viennese' : 'ALL 6 tablets') + '?')) return;
  try {
    var resp = await fetch(MONITOR_URL + 'admin/update', {
      method: 'POST',
      headers: {'Content-Type': 'application/json', 'X-Admin-Key': adminKey},
      body: JSON.stringify({target: target})
    });
    var json = await resp.json();
    if (json.ok) {
      showToast('Update command sent to ' + (target === 'ab' ? 'Macchiato & Viennese' : 'ALL tablets') + ' — takes effect within ~90 sec');
    } else {
      showToast('Update failed: ' + (json.error || 'unknown error'));
    }
  } catch(e) {
    showToast('Update failed: ' + e.message);
  }
}
```

- [ ] **Step 2: Add "⬆ Update Now" button with inline A/B / ALL selector**

Current line 1361-1364 (the action bar `html` string):
```javascript
  let html = '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px">' +
    '<button class="adm-refresh-btn" style="background:var(--amber)" onclick="bulkCommand(\'reload\',\'all\')">↻ Reload all</button>' +
    '<button class="adm-refresh-btn" style="background:#5b6af0" onclick="bulkCommand(\'enable_test_sleep\',\'all\')" title="Send test sleep to all tablets — sleeps in 2 min, wakes in 10 min">🌙 Test Sleep</button>' +
    '</div>';
```

Replace with:
```javascript
  var html = '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px">' +
    '<button class="adm-refresh-btn" style="background:var(--amber)" onclick="bulkCommand(\'reload\',\'all\')">↻ Reload all</button>' +
    '<button class="adm-refresh-btn" style="background:#5b6af0" onclick="bulkCommand(\'enable_test_sleep\',\'all\')" title="Send test sleep to all tablets — sleeps in 2 min, wakes in 10 min">🌙 Test Sleep</button>' +
    '<span style="display:inline-flex;gap:4px;align-items:center">' +
      '<button class="adm-refresh-btn" style="background:#2d7d46" onclick="triggerOtaUpdate(\'ab\')" title="OTA update Macchiato + Viennese only">⬆ Update A/B</button>' +
      '<button class="adm-refresh-btn" style="background:#1a5c32" onclick="triggerOtaUpdate(\'all\')" title="OTA update all 6 in-office tablets">⬆ Update ALL</button>' +
    '</span>' +
    '</div>';
```

Note: changed `let html` to `var html` for ES5 consistency.

- [ ] **Step 3: Commit**

```bash
git add dashboard.html
git commit -m "feat(dashboard): add OTA Update Now buttons (A/B and ALL) to admin panel"
```

---

### Task 6: Version bump to 5.59

**Files:**
- Modify: `boot-launcher/app/build.gradle`
- Modify: `index-version.json` (APK version manifest served by Worker)
- Modify: `apk-version.json` (if present — secondary version file)

**Interfaces:**
- `versionCode` 89 → 90
- `versionName` "5.58" → "5.59"

- [ ] **Step 1: Bump `build.gradle`**

Current lines 10-11:
```
        versionCode 89
        versionName "5.58"
```

Replace with:
```
        versionCode 90
        versionName "5.59"
```

- [ ] **Step 2: Check and update version manifest files**

Read `index-version.json` at the repo root. Update `versionCode` to 90 and `versionName` to "5.59". The `apkUrl` field should point to the built APK (do not change if it already points to a static Worker URL or GitHub release URL — leave it as-is; the CI pipeline updates it).

- [ ] **Step 3: Commit**

```bash
git add boot-launcher/app/build.gradle index-version.json
git commit -m "chore: bump versionCode 89→90, versionName 5.58→5.59"
```

---

## Self-Review

**Spec coverage check:**
- `silentInstall()` in UpdateChecker ✓ (Task 1)
- 60s thread-join timeout, API 19 compatible ✓ (Task 1 — `Thread.join(60000)`)
- `onNoUpdate` / `onFailure` callbacks ✓ (Task 1)
- Reuses `getApkFile()` and `CLIENT` ✓ (Task 1)
- `ScheduleReceiver` weekdays-only (weekend guard already exists) ✓ (Task 2)
- `launchStandby` called in both callbacks ✓ (Task 2)
- `KioskWebViewActivity.performUpdate()` bridge ✓ (Task 3)
- `index.html` perform_update handler ✓ (Task 3)
- `validCommands` extended ✓ (Task 4)
- Bulk OTA KV keys (`cmd:perform_update:all`, `cmd:perform_update:ab`) ✓ (Task 4)
- 5-min TTL on bulk keys ✓ (Task 4)
- A/B targets Macchiato + Viennese ✓ (Task 4)
- `POST /api/admin/update` endpoint with X-Admin-Key auth ✓ (Task 4)
- Dashboard buttons A/B + ALL ✓ (Task 5)
- Confirmation dialog before sending ✓ (Task 5)
- Toast feedback ✓ (Task 5)
- Version bump ✓ (Task 6)
- Non-rooted fallback: `onFailure` → `launchStandby` (06:00) or do nothing (dashboard) ✓ (Tasks 2, 3)
- Manual room picker flow unchanged ✓ (no changes to `check()` or dialog flow)

**Placeholder scan:** None found.

**Type/name consistency:** `silentInstall` used consistently across Tasks 1-3. `perform_update` (underscore) used consistently in Tasks 3-5. `cmd:perform_update:all` / `cmd:perform_update:ab` used consistently in Tasks 4-5.
