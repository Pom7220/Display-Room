# Standby Retry + Rolling Incident Design

**Goal:** When StandbyActivity fails to activate at 20:30 BKK, the APK automatically retries up to 3 times over 15 minutes, files a single rolling `standby_failure` incident visible in the admin panel all night, and auto-resolves it the moment the tablet's first morning heartbeat arrives.

**Architecture:** Two-part change — APK retry logic in `KioskWebViewActivity.onResume()` using SharedPreferences for state; Worker incident accumulation using the room's existing KV record to store the open incident ID for O(1) auto-resolve on next heartbeat.

**Tech Stack:** Java (Android APK, ES5-compatible patterns), Cloudflare Workers (JavaScript), KV namespace.

## Global Constraints

- All Java must target Android API 19 (Android 4.4.2) — no lambdas, no streams, no try-with-resources
- All times discussed and computed in Bangkok time (UTC+7)
- Standby window: 20:30 BKK to 06:00 BKK (13:30–23:00 UTC)
- Max 3 retries, 5-minute cooldown between each
- Incident auto-resolves on ANY successful heartbeat from the tablet (not morning-only)
- No new KV keys beyond `openStandbyIncidentId` field added to existing room record
- No changes to existing incident schema fields — extend only
- ES5-only JavaScript in Worker (var, no arrow functions, no const/let)

---

## File Map

**Modify:**
- `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java`
  — add standby retry logic in `onResume()`; add `logStandbyRetry()` and `fileStandbyFailureIncident()` helpers
- `cloudflare-worker.js`
  — extend `handleHeartbeat()` to auto-resolve open standby incidents; extend `handleIncident()` or POST /api/incident handler to store `openStandbyIncidentId` on room record

**No changes:**
- `StandbyActivity.java` — no modification needed
- `ScheduleReceiver.java` — no modification needed
- `dashboard.html` — existing incident display already handles open incidents correctly
- `index.html` — no changes

---

## Task 1: APK — Standby retry state helpers

**Files:**
- Modify: `KioskWebViewActivity.java`

**What to build:**

Three SharedPreferences helpers, keyed by BKK date so state resets automatically each day with no cleanup code.

```java
// Returns today's BKK date string e.g. "2026-08-04"
private String todayBkk() {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    return sdf.format(new java.util.Date());
}

// Returns true if current BKK time is in the standby window (20:30–06:00)
private boolean isInStandbyWindow() {
    java.util.Calendar bkk = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    int hour = bkk.get(java.util.Calendar.HOUR_OF_DAY);
    int min  = bkk.get(java.util.Calendar.MINUTE);
    return (hour > 20 || (hour == 20 && min >= 30) || hour < 6);
}

private android.content.SharedPreferences retryPrefs() {
    return getSharedPreferences("standby_retry", MODE_PRIVATE);
}

private int getRetryCount()      { return retryPrefs().getInt("count_" + todayBkk(), 0); }
private long getRetryLastMs()    { return retryPrefs().getLong("last_ms", 0L); }
private boolean incidentFiled() { return retryPrefs().getBoolean("filed_" + todayBkk(), false); }

private void incrementRetryCount() {
    retryPrefs().edit()
        .putInt("count_" + todayBkk(), getRetryCount() + 1)
        .putLong("last_ms", System.currentTimeMillis())
        .apply();
}

private void markIncidentFiled() {
    retryPrefs().edit().putBoolean("filed_" + todayBkk(), true).apply();
}
```

**Testing:** Unit-verify that `isInStandbyWindow()` returns true for 20:30, 23:59, 00:00, 05:59; false for 06:00, 12:00, 20:29.

---

## Task 2: APK — Retry logic in onResume()

**Files:**
- Modify: `KioskWebViewActivity.java`

**What to build:**

At the end of the existing `onResume()` method, after `startPingWatchdog()` and `_lastWatchdogReloadMs = 0`, add:

```java
// Standby retry — if we're in the standby window, StandbyActivity should be covering us.
// onResume() here means it failed or crashed. Retry up to 3 times with 5-min cooldown.
if (isInStandbyWindow()) {
    int count   = getRetryCount();
    long lastMs = getRetryLastMs();
    long nowMs  = System.currentTimeMillis();
    long COOLDOWN_MS = 5L * 60L * 1000L; // 5 minutes

    if (count < 3 && (nowMs - lastMs) > COOLDOWN_MS) {
        incrementRetryCount();
        int newCount = getRetryCount();
        logStandbyRetry(newCount);
        ScheduleReceiver.launchStandby(this);

    } else if (count >= 3 && !incidentFiled()) {
        markIncidentFiled();
        fileStandbyFailureIncident(count);
    }
    // count >= 3 && incidentFiled() → do nothing; stay online until 06:00 restart
}
```

**Notes:**
- `ScheduleReceiver.launchStandby(this)` already exists and is `static` — confirm it is accessible here; if package-private, make it `public static`.
- The first call (count=0 → 1) fires immediately when `onResume()` detects the standby window. Subsequent retries only fire if `onResume()` fires again after the cooldown. This happens naturally when StandbyActivity crashes and KioskWebViewActivity regains focus.
- If StandbyActivity launches successfully, `onPause()` fires and `onResume()` does not fire again until 07:30 wake — at which point time is outside the standby window, so the block is skipped entirely.

---

## Task 3: APK — logStandbyRetry() and fileStandbyFailureIncident()

**Files:**
- Modify: `KioskWebViewActivity.java`

**What to build:**

```java
private void logStandbyRetry(int attemptNumber) {
    // POST to /api/alarm so the retry appears in the dashboard alarm chain
    String payload = "{\"room\":\"" + mRoomEmail + "\","
        + "\"roomname\":\"" + mRoomName + "\","
        + "\"event\":\"standby_retry\","
        + "\"apkVersion\":\"" + BuildConfig.VERSION_NAME + "\"}";
    postJson(WORKER_BASE_URL + "/api/alarm", payload, null);
}

private void fileStandbyFailureIncident(int retryCount) {
    // Format BKK time for the detail string
    java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
    fmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    String nowBkk = fmt.format(new java.util.Date());

    String detail = "Standby retried " + retryCount + "/3 times, gave up at " + nowBkk + " BKK. "
        + "Tablet staying online until 06:00 restart.";

    String payload = "{\"room\":\"" + mRoomEmail + "\","
        + "\"roomname\":\"" + mRoomName + "\","
        + "\"type\":\"standby_failure\","
        + "\"detail\":\"" + detail + "\","
        + "\"apkVersion\":\"" + BuildConfig.VERSION_NAME + "\"}";

    postJson(WORKER_BASE_URL + "/api/incident", payload, null);
}
```

`postJson` is the existing fire-and-forget XHR/OkHttp helper already used in the APK for alarm logging. Use the same pattern — confirm the method name in the existing code.

`mRoomEmail`, `mRoomName`, `WORKER_BASE_URL` — confirm exact field/constant names from existing code.

---

## Task 4: Worker — store openStandbyIncidentId on incident creation

**Files:**
- Modify: `cloudflare-worker.js` — `handleIncident()` (or the POST /api/incident handler)

**What to build:**

When a `standby_failure` incident is created, write the incident ID back into the room's KV heartbeat record:

```javascript
// Inside handleIncident(), after writing the new incident to KV:
if (data.type === 'standby_failure' && data.room) {
    var roomKey = 'room:' + data.room;
    var roomRaw = await env.RIS_KV.get(roomKey);
    if (roomRaw) {
        var roomRecord = JSON.parse(roomRaw);
        roomRecord.openStandbyIncidentId = incId; // incId = the new incident's ID
        await env.RIS_KV.put(roomKey, JSON.stringify(roomRecord), { expirationTtl: 7200 });
    }
}
```

This is O(1) — one extra KV read+write per standby failure incident (rare event).

---

## Task 5: Worker — auto-resolve on heartbeat

**Files:**
- Modify: `cloudflare-worker.js` — `handleHeartbeat()`

**What to build:**

At the end of `handleHeartbeat()`, after the room record is written to KV, check for an open standby incident and resolve it:

```javascript
// Auto-resolve open standby_failure incident on any successful heartbeat
if (record.openStandbyIncidentId) {
    var incKey = 'incident:' + record.openStandbyIncidentId;
    var incRaw = await env.RIS_KV.get(incKey);
    if (incRaw) {
        var inc = JSON.parse(incRaw);
        if (!inc.resolvedAt) {
            // Calculate how long the tablet was offline/failed
            var reportedAt = new Date(inc.reportedAt);
            var resolvedAt = new Date(now); // now = Date.now() at top of handleHeartbeat
            var durationMs = resolvedAt - reportedAt;
            var durationHrs = Math.floor(durationMs / 3600000);
            var durationMins = Math.floor((durationMs % 3600000) / 60000);

            // BKK time label for the resolution message
            var bkkNow = new Date(now + 7 * 3600000);
            var bkkLabel = bkkNow.toISOString().slice(11, 16) + ' BKK';
            var bkkDay = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'][bkkNow.getUTCDay()];

            inc.resolvedAt  = resolvedAt.toISOString();
            inc.resolvedBy  = 'auto_heartbeat';
            inc.resolution  = 'Back online ' + bkkDay + ' ' + bkkLabel
                + ' — standby failed for ' + durationHrs + 'h ' + durationMins + 'm';
            inc.durationMinutes = Math.round(durationMs / 60000);

            await env.RIS_KV.put(incKey, JSON.stringify(inc), { expirationTtl: 604800 });
        }
    }
    // Clear the field from room record so future heartbeats don't re-check
    record.openStandbyIncidentId = null;
    await env.RIS_KV.put(roomKey, JSON.stringify(record), { expirationTtl: 7200 });
}
```

**Note:** `now` is already defined at the top of `handleHeartbeat` as `Date.now()`. `roomKey` and `record` are already defined before this block is reached. Confirm exact variable names match existing code.

**Edge case — room KV record expires before tablet recovers (outage >2h):**

If the tablet is offline for more than 2 hours (the room record TTL), the `openStandbyIncidentId` field is lost with the expired record. When the tablet sends its first heartbeat in the morning, a fresh room record is created without that field, so auto-resolve won't trigger.

Fix: when `handleHeartbeat` creates a *new* room record (i.e. `!existingRaw`), also scan for any unresolved `standby_failure` incident for this room in the last 24 hours and resolve it. Limit the scan to the incidents index, filtered by `room` and `type === 'standby_failure'` and `!resolvedAt` — at most a handful of entries.

---

## Sequence — end to end

```
20:30  ScheduleReceiver fires → launchStandby() → StandbyActivity crashes
20:30  KioskWebViewActivity.onResume() → count=0, cooldown=0 → retry 1, log standby_retry
20:35  StandbyActivity crashes again → onResume() → count=1, 5min elapsed → retry 2, log standby_retry
20:40  StandbyActivity crashes again → onResume() → count=2, 5min elapsed → retry 3, log standby_retry
20:45  StandbyActivity crashes again → onResume() → count=3, incident not filed → POST standby_failure
       Worker creates incident, writes openStandbyIncidentId into room KV record
20:45  Admin panel shows Decaffinato: OPEN standby_failure incident
...
07:34  Tablet wakes (07:30 alarm), KioskWebViewActivity starts, heartbeat fires
       Worker handleHeartbeat() sees openStandbyIncidentId → resolves incident:
       "Back online Mon 07:34 BKK — standby failed for 11h 4m"
       Clears openStandbyIncidentId from room record
07:34  Admin panel: incident now shows RESOLVED with timestamp
```

---

## What this does NOT fix

- The root cause of StandbyActivity crashing (unknown — being investigated via `standby_resumed` diagnostic added in v3.10.178)
- Latte's separate crash pattern (different device, different failure mode)
- PoE schedule fallback (Option A — deferred, pending switch model confirmation)
