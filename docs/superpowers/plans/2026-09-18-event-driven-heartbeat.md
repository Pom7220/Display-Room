# Event-Driven Heartbeat + Adaptive Incident Monitoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace polling KV writes with event-driven writes, reducing daily KV usage from ~1,150 to ~71 at 12 tablets, while restoring 20-min granular writes during incidents only.

**Architecture:** The Worker heartbeat handler checks an `incident_active:{room}` KV flag per room — skipping writes when absent, writing full hbHist when present. Incident open/close logic sets/deletes this flag. The Worker returns `heartbeatIntervalMs` in every heartbeat response so tablets self-adjust to 20 min during incidents and revert to 30 min when resolved. BootReceiver gains logcat tracing for the clock correction path.

**Tech Stack:** Cloudflare Worker (ES2017 JS), Cloudflare KV, Android Java (BootReceiver), android.util.Log

## Global Constraints

- Worker JS must stay ES2017 (no optional chaining, no nullish coalescing) — existing codebase standard
- All KV writes use `expirationTtl` — no permanent keys except `incident_active` and `cmd:incident_heartbeat` which are explicitly deleted
- Android minimum API 19 (Android 4.4); logcat additions use `android.util.Log` only — no new imports
- APK version bump to 5.104 for the BootReceiver logcat change
- Do not change tablet heartbeat POST frequency in APK — tablet JS already reads `heartbeatIntervalMs` from Worker response

---

### Task 1: Worker — skip KV writes during healthy state

**Files:**
- Modify: `cloudflare-worker.js` lines 237–338 (`handleHeartbeat`)

**Interfaces:**
- Produces: `handleHeartbeat` checks `incident_active:{room}` flag before writing; returns `heartbeatIntervalMs` in response

- [ ] **Step 1: Read the current handleHeartbeat function**

Open `cloudflare-worker.js` and read lines 237–338 to understand the current write gates (`criticalChange`, `staleEnough`) and the response shape `{ ok: true, command }`.

- [ ] **Step 2: Add incident_active flag check — replace the write gate**

Replace the existing `criticalChange || staleEnough` gate with one that also checks `incident_active:{room}`. The write only happens when an incident is active OR the existing gate passes AND incident is not active (for backward compat during transition — remove staleEnough gate entirely after verifying incident detection works).

Find this block in `handleHeartbeat` (around line 247):
```javascript
var [existingRaw, pendingCmd] = await Promise.all([
  env.RIS_KV.get(roomKey),
  env.RIS_KV.get(cmdKey)
]);
```

Replace with:
```javascript
var [existingRaw, pendingCmd, incidentActiveRaw] = await Promise.all([
  env.RIS_KV.get(roomKey),
  env.RIS_KV.get(cmdKey),
  env.RIS_KV.get('incident_active:' + data.room)
]);
var incidentActive = !!incidentActiveRaw;
```

- [ ] **Step 3: Remove the staleEnough / criticalChange gate, replace with incidentActive**

Find the gate block (around line 260–300):
```javascript
var msSinceLast = prev ? Date.now() - new Date(prev.timestamp).getTime() : Infinity;
var criticalChange = !prev
  || prev.status          !== newStatus
  || prev.hasRefreshToken !== newRefresh
  || data.restarted === true;
var staleEnough = msSinceLast > 55 * 60 * 1000;

if (criticalChange || staleEnough) {
  var record = { ... };
  await env.RIS_KV.put(roomKey, JSON.stringify(record), { expirationTtl: 7200 });

  var hbHistKey = 'hb_history:' + data.room;
  var hbHistRaw = await env.RIS_KV.get(hbHistKey);
  var hbHist = hbHistRaw ? JSON.parse(hbHistRaw) : [];
  hbHist.unshift({ timestamp: new Date().toISOString(), status: newStatus });
  if (hbHist.length > 20) hbHist = hbHist.slice(0, 20);
  await env.RIS_KV.put(hbHistKey, JSON.stringify(hbHist), { expirationTtl: 604800 });
}
```

Replace with:
```javascript
if (incidentActive) {
  var record = {
    room: data.room,
    roomname: data.roomname || '',
    status: newStatus,
    tokenExpiry: data.tokenExpiry || null,
    hasRefreshToken: newRefresh,
    version: newVersion,
    apkVersion: newApk,
    lastCal: data.lastCal || null,
    meetingCount: data.meetingCount || 0,
    uptime: data.uptime || 0,
    log: (data.log || []).slice(-10),
    qrAvgPerDay: data.qrAvgPerDay || 0,
    qrPeakDay: data.qrPeakDay || 0,
    middayReload: newMiddayReload,
    pollStats: data.pollStats || null,
    timestamp: new Date().toISOString(),
    ip: request.headers.get('CF-Connecting-IP') || ''
  };
  await env.RIS_KV.put(roomKey, JSON.stringify(record), { expirationTtl: 7200 });

  var hbHistKey = 'hb_history:' + data.room;
  var hbHistRaw = await env.RIS_KV.get(hbHistKey);
  var hbHist = hbHistRaw ? JSON.parse(hbHistRaw) : [];
  hbHist.unshift({ timestamp: new Date().toISOString(), status: newStatus });
  if (hbHist.length > 20) hbHist = hbHist.slice(0, 20);
  await env.RIS_KV.put(hbHistKey, JSON.stringify(hbHist), { expirationTtl: 604800 });
}
```

- [ ] **Step 4: Return heartbeatIntervalMs in response**

Find the return statement (around line 331):
```javascript
return jsonResponse({
  ok: true,
  command: pendingCmd ? JSON.parse(pendingCmd) : null
});
```

Replace with:
```javascript
return jsonResponse({
  ok: true,
  command: pendingCmd ? JSON.parse(pendingCmd) : null,
  heartbeatIntervalMs: incidentActive ? 1200000 : 1800000
});
```

- [ ] **Step 5: Verify manually**

Deploy the Worker to Cloudflare. Send a test heartbeat POST with no `incident_active` flag set:
```bash
curl -X POST https://ris-display.ris-display.workers.dev/api/heartbeat \
  -H "Content-Type: application/json" \
  -d '{"room":"test@test.com","status":"live","roomname":"Test"}'
```
Expected response: `{"ok":true,"command":null,"heartbeatIntervalMs":1800000}`
Expected KV: no new write to `room:test@test.com` (verify in Cloudflare KV dashboard — key absent or unchanged).

- [ ] **Step 6: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat: skip KV writes during healthy state, return heartbeatIntervalMs"
```

---

### Task 2: Worker — incident_active flag on incident open/close

**Files:**
- Modify: `cloudflare-worker.js` — `handleAlarmLog` (standby section) and heartbeat incident auto-resolve section

**Interfaces:**
- Consumes: `incidentActive` flag from Task 1
- Produces: `incident_active:{room}` KV key set on incident open, deleted on incident close

- [ ] **Step 1: Find incident open logic**

Search `cloudflare-worker.js` for where standby_failure incidents are created (grep for `standby_open` or `incident:`). Read those lines to understand the existing open/close flow.

The incident open path is in `handleAlarmLog` where `data.event === 'standby'` is detected as a failure (tablet missed wake after standby), and in the health agent. The auto-close path is in `handleHeartbeat` where `standby_open:{room}` key is found and resolved.

- [ ] **Step 2: Set incident_active flag on incident open**

Find where `standby_open:{room}` is written (the incident pointer key). It appears around line 754:
```javascript
await env.RIS_KV.put('standby_open:' + data.room, incidentKey, { expirationTtl: 172800 });
```

Add the `incident_active` flag write immediately after:
```javascript
await env.RIS_KV.put('standby_open:' + data.room, incidentKey, { expirationTtl: 172800 });
await env.RIS_KV.put('incident_active:' + data.room, '1');
```

Note: no `expirationTtl` on `incident_active` — it is explicitly deleted on close.

- [ ] **Step 3: Delete incident_active flag on incident close**

Find the auto-resolve block in `handleHeartbeat` where `standby_open:{room}` is deleted (around line 328):
```javascript
await env.RIS_KV.delete('standby_open:' + data.room);
```

Add deletion of the incident_active flag immediately after:
```javascript
await env.RIS_KV.delete('standby_open:' + data.room);
await env.RIS_KV.delete('incident_active:' + data.room);
```

- [ ] **Step 4: Also write hbHist snapshot on incident open**

When an incident opens, write a final hbHist snapshot so the troubleshooting trail captures the last known good state. Add after the `incident_active` put in Step 2:
```javascript
await env.RIS_KV.put('incident_active:' + data.room, '1');
// Snapshot hbHist at incident open so last-known-good timestamp is preserved
var hbSnapKey = 'hb_history:' + data.room;
var hbSnapRaw = await env.RIS_KV.get(hbSnapKey);
var hbSnap = hbSnapRaw ? JSON.parse(hbSnapRaw) : [];
hbSnap.unshift({ timestamp: new Date().toISOString(), status: 'incident_open' });
if (hbSnap.length > 20) hbSnap = hbSnap.slice(0, 20);
await env.RIS_KV.put(hbSnapKey, JSON.stringify(hbSnap), { expirationTtl: 604800 });
```

- [ ] **Step 5: Verify manually**

Set `incident_active:test@test.com` = `1` in KV manually via Cloudflare dashboard. Send a heartbeat POST for that room and confirm:
- Response includes `heartbeatIntervalMs: 1200000`
- KV write happened (room record updated, hbHist updated)

Then delete `incident_active:test@test.com` and send another heartbeat:
- Response includes `heartbeatIntervalMs: 1800000`
- No KV write

- [ ] **Step 6: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat: set/delete incident_active flag on incident open/close"
```

---

### Task 3: Worker — write roomKey on alarm events (cold_boot, wake, standby)

**Files:**
- Modify: `cloudflare-worker.js` — `handleAlarmLog`

**Interfaces:**
- Produces: `roomKey` always written on `cold_boot` and `wake` events (not just `standby`) so dashboard shows correct state after reboot

- [ ] **Step 1: Read current handleAlarmLog**

Read `handleAlarmLog` (lines 344–386). Currently it writes `alarm_log:{room}` on every event, and writes `roomKey` only on `standby` event.

- [ ] **Step 2: Add roomKey write for cold_boot and wake**

After the existing standby block (around line 379), add:
```javascript
if (data.event === 'cold_boot' || data.event === 'wake') {
  var roomKey = 'room:' + data.room;
  var prevRaw = await env.RIS_KV.get(roomKey);
  var prev = prevRaw ? JSON.parse(prevRaw) : {};
  var eventRecord = Object.assign({}, prev, {
    room:       data.room,
    roomname:   data.roomname || prev.roomname || '',
    status:     'live',
    apkVersion: data.apkVersion || prev.apkVersion || '',
    timestamp:  new Date().toISOString()
  });
  // TTL 7200s (2h) — next scheduled alarm event or incident will refresh
  await env.RIS_KV.put(roomKey, JSON.stringify(eventRecord), { expirationTtl: 7200 });
}
```

- [ ] **Step 3: Verify manually**

Send a `cold_boot` alarm POST:
```bash
curl -X POST https://ris-display.ris-display.workers.dev/api/alarm \
  -H "Content-Type: application/json" \
  -d '{"room":"test@test.com","roomname":"Test","event":"cold_boot","apkVersion":"5.104"}'
```
Expected: `room:test@test.com` KV key updated with `status: "live"` and fresh timestamp.

- [ ] **Step 4: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat: write roomKey on cold_boot and wake alarm events"
```

---

### Task 4: APK — BootReceiver logcat additions (v5.104)

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java`
- Modify: `boot-launcher/app/build.gradle`

**Interfaces:**
- Produces: `adb logcat -s BootReceiver` shows clock before/after correction, Date header, su result

- [ ] **Step 1: Add Log import to BootReceiver**

At the top of `BootReceiver.java`, the imports currently include `android.os.Build` etc. Add:
```java
import android.util.Log;
```

- [ ] **Step 2: Add logcat calls in fixClockAndLogBoot**

Find `fixClockAndLogBoot` in `BootReceiver.java`. Add log calls at key points:

After `try {` at the top of the retry loop, add:
```java
if (attempt == 0) {
    Log.d("BootReceiver", "clock before fix: " + new java.util.Date(System.currentTimeMillis()));
}
```

After `String dateHeader = resp.header("Date");`, add:
```java
Log.d("BootReceiver", "server Date header: " + dateHeader);
```

After `String dateStr = String.format(...)`, add:
```java
Log.d("BootReceiver", "setting clock to BKK: " + dateStr);
```

After `Runtime.getRuntime().exec(new String[]{"su", "0", "date", dateStr}).waitFor();`, add:
```java
Log.d("BootReceiver", "clock after fix: " + new java.util.Date(System.currentTimeMillis()));
```

In the `catch (Exception ignored) {}` block, change to:
```java
} catch (Exception e) {
    Log.e("BootReceiver", "fixClockAndLogBoot attempt " + attempt + " failed: " + e.getMessage());
}
```

- [ ] **Step 3: Bump version to 5.104**

In `boot-launcher/app/build.gradle`, change:
```
versionCode 603
versionName "5.103"
```
to:
```
versionCode 604
versionName "5.104"
```

- [ ] **Step 4: Verify logcat output**

After deploying v5.104 to Latte and rebooting:
```
.\adb -s 10.0.54.109:5555 logcat -s BootReceiver
```
Expected output (example):
```
D BootReceiver: clock before fix: Thu Sep 18 22:06:12 GMT+07:00 2026
D BootReceiver: server Date header: Fri, 18 Sep 2026 08:35:44 GMT
D BootReceiver: setting clock to BKK: 091815352026.44
D BootReceiver: clock after fix: Thu Sep 18 15:35:45 GMT+07:00 2026
```

- [ ] **Step 5: Commit and push (triggers CI build)**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java
git add boot-launcher/app/build.gradle
git commit -m "feat: add logcat tracing to BootReceiver clock correction (v5.104)"
git push
```

---

## Deployment Order

1. Deploy Worker (Tasks 1–3) — no tablet changes, safe to deploy first
2. Verify KV write count drops on Cloudflare KV metrics page
3. Deploy APK v5.104 via "Update All" on dashboard
4. Reboot one tablet (Latte) and verify logcat output
