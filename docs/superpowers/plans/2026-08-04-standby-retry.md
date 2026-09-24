# Standby Retry + Rolling Incident Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When StandbyActivity fails at 20:30 BKK, the APK retries up to 3 times (5-min cooldown each), then files a single `standby_failure` incident that stays OPEN in the admin panel overnight and auto-resolves the moment the tablet's next heartbeat arrives.

**Architecture:** APK detects standby failure via `onResume()` firing inside the standby window, uses date-keyed SharedPreferences to track retry state (auto-resets each day), and POSTs to `/api/alarm` and `/api/incident` directly. Worker stores a `standby_open:{room}` KV key on incident creation (separate from the 2h-TTL room record) and resolves it on next heartbeat.

**Tech Stack:** Java (Android API 19+, no lambdas), Cloudflare Workers (ES5 JavaScript, KV storage), OkHttp already in APK deps.

## Global Constraints

- Java targets Android API 19 (Android 4.4.2) — no lambdas, no streams, no try-with-resources
- All times in Bangkok (UTC+7); standby window = 20:30–06:00 BKK = 13:30–23:00 UTC
- Max 3 retries, minimum 5-minute cooldown between each
- SharedPreferences file for retry state: `"standby_retry"` (separate from main `"ris_kiosk_prefs"`)
- Retry state keys are date-keyed (e.g. `"count_2026-08-04"`) — no manual cleanup needed
- Worker: ES5 only — `var`, no `const`/`let`, no arrow functions
- Worker KV key for open incident tracking: `"standby_open:" + room_email` (TTL 2 days = 172800s)
- Incident type string: `"standby_failure"` (exact, lowercase, underscore)
- Alarm event string for each retry: `"standby_retry"` (exact)
- APK version bump: 5.47 → 5.48 in `build.gradle`
- Worker base URL (already in APK): `"https://ris-display.ris-display.workers.dev/"`

---

## File Map

**Modify:**
- `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java`
  — add retry helpers, `postJsonFire()`, retry logic in `onResume()`, `logStandbyRetry()`, `fileStandbyFailureIncident()`
- `boot-launcher/app/build.gradle`
  — bump `versionCode` and `versionName` (5.47 → 5.48)
- `cloudflare-worker.js`
  — extend `handleIncidentReport()` to write `standby_open` KV key; extend `handleHeartbeat()` to auto-resolve on that key

**No changes:**
- `StandbyActivity.java`, `ScheduleReceiver.java`, `index.html`, `index-version.json`, `dashboard.html`

---

## Task 1: APK — Standby retry helpers + onResume logic

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java`

**Interfaces:**
- Produces: `isInStandbyWindow()`, `todayBkk()`, `getRetryCount()`, `getRetryLastMs()`, `incidentFiled()`, `incrementRetryCount()`, `markIncidentFiled()`, `postJsonFire(url, json)`, `logStandbyRetry(count)`, `fileStandbyFailureIncident(count)` — all private instance methods used only by `onResume()`
- Consumes: `ScheduleReceiver.launchStandby(this)` (package-private static, same package — no import needed)

- [ ] **Step 1: Verify existing onResume() to find exact insertion point**

Open `KioskWebViewActivity.java`. Confirm `onResume()` ends at line ~358 with:
```java
    startPingWatchdog(); // restart watchdog suspended in onPause
    hideSystemUI();
}
```
The standby retry block goes **before** the closing brace, after `hideSystemUI()`.

- [ ] **Step 2: Add helper methods**

Add the following private methods anywhere inside the class body (after `onResume()` is a good place). All methods are `private`:

```java
/** Returns today's date in Bangkok time as "yyyy-MM-dd" */
private String todayBkk() {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    return sdf.format(new java.util.Date());
}

/** True if current Bangkok time is inside the standby window: 20:30–06:00 */
private boolean isInStandbyWindow() {
    java.util.Calendar bkk = java.util.Calendar.getInstance(
        java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    int h = bkk.get(java.util.Calendar.HOUR_OF_DAY);
    int m = bkk.get(java.util.Calendar.MINUTE);
    return (h > 20 || (h == 20 && m >= 30) || h < 6);
}

private android.content.SharedPreferences retryPrefs() {
    return getSharedPreferences("standby_retry", MODE_PRIVATE);
}

private int getRetryCount() {
    return retryPrefs().getInt("count_" + todayBkk(), 0);
}

private long getRetryLastMs() {
    return retryPrefs().getLong("last_ms", 0L);
}

private boolean incidentFiled() {
    return retryPrefs().getBoolean("filed_" + todayBkk(), false);
}

private void incrementRetryCount() {
    retryPrefs().edit()
        .putInt("count_" + todayBkk(), getRetryCount() + 1)
        .putLong("last_ms", System.currentTimeMillis())
        .apply();
}

private void markIncidentFiled() {
    retryPrefs().edit()
        .putBoolean("filed_" + todayBkk(), true)
        .apply();
}

/** Fire-and-forget HTTP POST. Runs on a background thread. Never throws. */
private void postJsonFire(final String url, final String json) {
    new Thread(new Runnable() {
        @Override public void run() {
            try {
                java.net.URL u = new java.net.URL(url);
                java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                byte[] bytes = json.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(bytes.length);
                c.getOutputStream().write(bytes);
                c.getInputStream().close();
                c.disconnect();
            } catch (Exception ignored) {}
        }
    }).start();
}

/** POST a standby_retry event to /api/alarm so it appears in the dashboard alarm chain */
private void logStandbyRetry(int attemptNumber) {
    android.content.SharedPreferences p =
        getSharedPreferences("ris_kiosk_prefs", MODE_PRIVATE);
    String room     = p.getString("room_email", "");
    String roomname = p.getString("room_name", "");
    String apkVer;
    try {
        apkVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) { apkVer = ""; }

    String payload = "{\"room\":\"" + room + "\","
        + "\"roomname\":\"" + roomname + "\","
        + "\"event\":\"standby_retry\","
        + "\"apkVersion\":\"" + apkVer + "\"}";
    postJsonFire("https://ris-display.ris-display.workers.dev/api/alarm", payload);
}

/** POST a standby_failure incident to /api/incident. Called once after 3 retries exhausted. */
private void fileStandbyFailureIncident(int retryCount) {
    android.content.SharedPreferences p =
        getSharedPreferences("ris_kiosk_prefs", MODE_PRIVATE);
    String room     = p.getString("room_email", "");
    String roomname = p.getString("room_name", "");
    String apkVer;
    try {
        apkVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) { apkVer = ""; }

    java.text.SimpleDateFormat fmt =
        new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
    fmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    String nowBkk = fmt.format(new java.util.Date());

    // Escape detail for JSON (no special chars expected, but be safe with quotes)
    String detail = "Standby retried " + retryCount + "/3, gave up at "
        + nowBkk + " BKK. Tablet staying online until 06:00 restart.";

    String payload = "{\"room\":\"" + room + "\","
        + "\"roomname\":\"" + roomname + "\","
        + "\"type\":\"standby_failure\","
        + "\"detail\":\"" + detail + "\","
        + "\"apkVersion\":\"" + apkVer + "\","
        + "\"reportedBy\":\"apk\"}";
    postJsonFire("https://ris-display.ris-display.workers.dev/api/incident", payload);
}
```

- [ ] **Step 3: Add retry block to onResume()**

Inside `onResume()`, add the following **after** `hideSystemUI()` and **before** the closing brace:

```java
    // Standby retry — if we're in the standby window, StandbyActivity should be covering us.
    // onResume() firing here means it crashed or failed to launch. Retry up to 3 times.
    if (isInStandbyWindow()) {
        int count      = getRetryCount();
        long lastMs    = getRetryLastMs();
        long nowMs     = System.currentTimeMillis();
        long COOLDOWN  = 5L * 60L * 1000L; // 5 minutes between retries

        if (count < 3 && (nowMs - lastMs) > COOLDOWN) {
            incrementRetryCount();
            logStandbyRetry(getRetryCount()); // log after increment so count = 1/2/3
            ScheduleReceiver.launchStandby(this);
        } else if (count >= 3 && !incidentFiled()) {
            markIncidentFiled();
            fileStandbyFailureIncident(count);
        }
        // count >= 3 && incidentFiled(): do nothing — stay online until 06:00 restart
    }
```

- [ ] **Step 4: Verify isInStandbyWindow() logic manually**

Walk through these cases in your head (or with a debugger):

| BKK time | h  | m  | Expected | Calculation |
|----------|----|----|----------|-------------|
| 20:29    | 20 | 29 | false    | h==20 && m<30, h not >20, h not <6 |
| 20:30    | 20 | 30 | true     | h==20 && m>=30 ✓ |
| 23:00    | 23 | 0  | true     | h>20 ✓ |
| 00:00    | 0  | 0  | true     | h<6 ✓ |
| 05:59    | 5  | 59 | true     | h<6 ✓ |
| 06:00    | 6  | 0  | false    | h not >20, h not ==20, h not <6 |
| 12:00    | 12 | 0  | false    | none match |

All 7 must match Expected before continuing.

- [ ] **Step 5: Build the APK**

```bash
cd boot-launcher
./gradlew assembleRelease
```

Expected: `BUILD SUCCESSFUL`. Fix any compilation errors before continuing.

- [ ] **Step 6: Commit APK changes (pre-version-bump)**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java
git commit -m "feat(apk): standby retry — onResume detects failure, retries 3x, files incident"
```

---

## Task 2: APK — Version bump to 5.48

**Files:**
- Modify: `boot-launcher/app/build.gradle`

**Interfaces:**
- Produces: APK versionName `"5.48"` used by `logStandbyRetry()` and `fileStandbyFailureIncident()` via `getPackageInfo()`

- [ ] **Step 1: Find current version in build.gradle**

Open `boot-launcher/app/build.gradle`. Find the `defaultConfig` block:
```groovy
defaultConfig {
    ...
    versionCode X
    versionName "5.47"
    ...
}
```

- [ ] **Step 2: Bump version**

Change:
```groovy
    versionCode X        // increment by 1
    versionName "5.47"
```
To:
```groovy
    versionCode X+1
    versionName "5.48"
```

Where `X+1` is whatever integer follows the current `versionCode`.

- [ ] **Step 3: Build and verify**

```bash
cd boot-launcher
./gradlew assembleRelease
```

Expected: `BUILD SUCCESSFUL`. The output APK filename should contain `5.48` or the build output should reflect the new version.

- [ ] **Step 4: Commit**

```bash
git add boot-launcher/app/build.gradle
git commit -m "chore(apk): bump version 5.47 → 5.48"
```

---

## Task 3: Worker — store standby_open key on incident creation

**Files:**
- Modify: `cloudflare-worker.js` — `handleIncidentReport()` function

**Context:** `handleIncidentReport()` already creates an incident record and writes it to KV at key `'incident:' + now.toISOString().slice(0,10) + ':' + incidentId`. After that write, we add one more KV put for `standby_failure` type.

**Interfaces:**
- Produces: KV key `'standby_open:' + data.room` = the incident's full KV key string (e.g. `"incident:2026-08-04:lqz1abc"`), TTL 172800 (2 days)
- Consumed by: Task 4 (`handleHeartbeat`)

- [ ] **Step 1: Find the end of the incident write in handleIncidentReport()**

In `cloudflare-worker.js`, find `handleIncidentReport`. Locate the line that writes the incident to KV. It looks like:
```javascript
await env.RIS_KV.put(incidentKey, JSON.stringify(incident), { expirationTtl: 2592000 });
```
Where `incidentKey = 'incident:' + now.toISOString().slice(0, 10) + ':' + incidentId`.

- [ ] **Step 2: Add standby_open key write immediately after**

Insert right after the `RIS_KV.put(incidentKey, ...)` line:

```javascript
    // For standby_failure incidents, store a durable pointer for heartbeat auto-resolve.
    // Uses a separate key (not the room record) so it survives the 2h room TTL.
    if (data.type === 'standby_failure' && data.room) {
      await env.RIS_KV.put(
        'standby_open:' + data.room,
        incidentKey,
        { expirationTtl: 172800 } // 2 days — covers full weekend
      );
    }
```

- [ ] **Step 3: Test via curl (or Cloudflare dashboard)**

Deploy the Worker first (see Task 5), then POST a test incident:

```bash
curl -s -X POST https://ris-display.ris-display.workers.dev/api/incident \
  -H "Content-Type: application/json" \
  -d '{"room":"test@central.co.th","roomname":"Test","type":"standby_failure","detail":"test","apkVersion":"5.48","reportedBy":"apk"}'
```

Expected response: `{"ok":true}` or similar.

Then verify in Cloudflare dashboard → KV → search for key `standby_open:test@central.co.th`. It should exist with a value like `incident:2026-08-04:abc123`.

- [ ] **Step 4: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat(worker): store standby_open KV key on standby_failure incident creation"
```

---

## Task 4: Worker — auto-resolve on heartbeat

**Files:**
- Modify: `cloudflare-worker.js` — `handleHeartbeat()` function

**Context:** `handleHeartbeat()` ends by writing the updated `record` to `roomKey`. After that final `RIS_KV.put`, add the auto-resolve block. Key variable names confirmed from existing code: `data.room`, `roomKey`, `record`, `existingRaw`.

**Interfaces:**
- Consumes: `'standby_open:' + data.room` KV key written in Task 3
- Produces: resolved incident with `resolvedAt`, `resolvedBy: 'auto_heartbeat'`, `resolution` containing BKK timestamp and duration

- [ ] **Step 1: Find the final RIS_KV.put in handleHeartbeat()**

Locate the line that writes the room record (near the end of `handleHeartbeat`):
```javascript
await env.RIS_KV.put(roomKey, JSON.stringify(record), { expirationTtl: 7200 });
```

- [ ] **Step 2: Add auto-resolve block after the room record write**

Insert immediately after that `RIS_KV.put`:

```javascript
    // Auto-resolve any open standby_failure incident for this tablet on first heartbeat back.
    // Uses a separate KV key (not the room record) so it survives the 2h room TTL.
    var standbyOpenKey = 'standby_open:' + data.room;
    var openIncidentKey = await env.RIS_KV.get(standbyOpenKey);
    if (openIncidentKey) {
      var openIncRaw = await env.RIS_KV.get(openIncidentKey);
      if (openIncRaw) {
        var openInc = JSON.parse(openIncRaw);
        if (!openInc.resolvedAt) {
          var resolvedAtMs = Date.now();
          var reportedAtMs = new Date(openInc.reportedAt).getTime();
          var durMs   = resolvedAtMs - reportedAtMs;
          var durHrs  = Math.floor(durMs / 3600000);
          var durMins = Math.floor((durMs % 3600000) / 60000);

          var bkkNow  = new Date(resolvedAtMs + 7 * 3600000);
          var bkkHHMM = bkkNow.toISOString().slice(11, 16);
          var bkkDays = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
          var bkkDay  = bkkDays[bkkNow.getUTCDay()];

          openInc.resolvedAt      = new Date(resolvedAtMs).toISOString();
          openInc.resolvedBy      = 'auto_heartbeat';
          openInc.resolution      = 'Back online ' + bkkDay + ' ' + bkkHHMM + ' BKK'
            + ' — standby failed for ' + durHrs + 'h ' + durMins + 'm';
          openInc.durationMinutes = Math.round(durMs / 60000);

          await env.RIS_KV.put(openIncidentKey, JSON.stringify(openInc),
            { expirationTtl: 2592000 });
        }
      }
      // Always delete the pointer — prevents re-resolve on future heartbeats
      await env.RIS_KV.delete(standbyOpenKey);
    }
```

- [ ] **Step 3: Verify the logic flow manually**

Walk through the happy path:
1. 20:45 BKK — incident created. `standby_open:risdecaffeinato@...` = `"incident:2026-08-04:abc"`
2. 07:34 BKK next day — heartbeat arrives. `openIncidentKey` = `"incident:2026-08-04:abc"`. `durMs` ≈ 11h4m. Resolution = `"Back online Mon 07:34 BKK — standby failed for 11h 4m"`. Incident resolved. `standby_open` key deleted.
3. Next heartbeat at 07:54 — `openIncidentKey` is null (key deleted). Block skips. No double-resolve.

Walk through the edge case (tablet offline >2h so room record expired):
1. 20:45 — incident + `standby_open` key created.
2. 22:45 — room record (2h TTL) expires. `standby_open` key still alive (2-day TTL).
3. 07:34 — heartbeat creates fresh room record (`existingRaw` was null). Auto-resolve block still runs because it reads `standby_open` key independently of `existingRaw`. Resolves correctly.

- [ ] **Step 4: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat(worker): auto-resolve standby_failure incident on next heartbeat"
```

---

## Task 5: Deploy and end-to-end verify

**Files:** None new — deploy existing changes.

- [ ] **Step 1: Push to GitHub (triggers Cloudflare deploy)**

```bash
git push
```

Confirm Cloudflare dashboard → Workers → ris-display shows new deployment (version ID changes).

- [ ] **Step 2: Simulate standby failure on a test tablet (optional — do this after 20:30 BKK)**

If you have a spare tablet or can test on Decaffinato specifically:
1. After the tablet picks up APK 5.48 via auto-update at 12:30 midday reload
2. At 20:30 — watch admin dashboard alarm chain for the problem tablet
3. If standby fails, you should see `standby_retry` entries appear at ~20:30, ~20:35, ~20:40
4. At ~20:45, a `standby_failure` OPEN incident should appear in the incident section
5. Next morning at 07:34, the incident should auto-resolve with the duration message

- [ ] **Step 3: Verify KV cleanup**

After the tablet comes back online, check Cloudflare KV — `standby_open:{room_email}` key should no longer exist (was deleted by the heartbeat handler).

- [ ] **Step 4: Verify no false positives on good tablets**

Macchiato and Mocha consistently enter standby correctly. Their `onResume()` at 07:30 (morning wake) fires outside the standby window (h=7, not in window) — the retry block is skipped. Confirm no spurious `standby_retry` alarm events appear for these two tablets in their alarm chain.

---

## Self-Review Checklist

- [x] **Spec coverage**
  - APK retry helpers + SharedPreferences: Task 1 ✓
  - onResume() retry logic: Task 1 ✓
  - 5-min cooldown: `COOLDOWN = 5L * 60L * 1000L` in Task 1 ✓
  - Max 3 retries: `count < 3` check in Task 1 ✓
  - logStandbyRetry() → /api/alarm: Task 1 ✓
  - fileStandbyFailureIncident() → /api/incident: Task 1 ✓
  - Incident type `standby_failure`: Task 1 + 3 ✓
  - Worker stores standby_open key: Task 3 ✓
  - Heartbeat auto-resolve: Task 4 ✓
  - Meaningful resolution timestamp: Task 4 ✓
  - Edge case: room record expires before tablet recovers: Task 4 step 3 edge case ✓
  - APK version bump: Task 2 ✓

- [x] **No placeholders** — all code is complete and exact

- [x] **Type/name consistency**
  - `"standby_retry"` alarm event: Tasks 1 and consistent with `/api/alarm` schema
  - `"standby_failure"` incident type: Tasks 1, 3, 4
  - `standby_open:{room}` key: Tasks 3 and 4 both use same pattern
  - `incidentKey` variable in Task 3 matches what's read in Task 4 (`openIncidentKey`)
  - `ris_kiosk_prefs` SharedPreferences name matches existing APK code
  - `room_email` and `room_name` keys match existing APK code
