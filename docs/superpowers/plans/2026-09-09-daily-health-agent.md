# RIS Tablet Daily Health Agent — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace manual tablet monitoring with a twice-daily Claude Code scheduled agent that reads Worker diagnostics, diagnoses each tablet with evidence, applies safe auto-fixes, and posts a structured report to the Claude Code session.

**Architecture:** Worker gains a `/api/diagnostics` endpoint (hb_history ring buffer + alarm_log + incidents per room) and `/api/fix-log` endpoint (agent logs every action taken). A Claude Code scheduled agent runs at 01:00 UTC (08:00 BKK) and 14:00 UTC (21:00 BKK), calls diagnostics, diagnoses, fixes, and posts the report.

**Tech Stack:** Cloudflare Worker (vanilla JS, KV), Claude Code scheduled agents (cloud), curl for manual testing.

## Global Constraints

- Worker file: `cloudflare-worker.js` — vanilla ES2020 JS, no imports, no npm, no TypeScript
- KV write budget: stay under 1,000 writes/day at 12 tablets — hb_history writes only on criticalChange or staleEnough (same gate as heartbeat)
- Admin key header: `X-Admin-Key: RIS-ADMIN-KEY2026` — required on all new protected endpoints
- Worker URL: `https://ris-display.ris-display.workers.dev`
- hb_history TTL: 604800 s (7 days); fix_log TTL: 604800 s (7 days)
- hb_history max entries: 20, newest first; fix_log max entries: 50, newest first
- OTA cap per agent run: max 2 tablets per run
- Phase C (scoring/promotion) is deferred — do NOT implement it in this plan
- All times in Worker responses are UTC ISO 8601; all times in agent reports are BKK (UTC+7)
- No-show report and 21:00 daily digest cron are removed in Task 3

---

### Task 1: Worker — hb_history ring buffer + /api/diagnostics endpoint

**Files:**
- Modify: `cloudflare-worker.js` (handleHeartbeat, fetch router, new handleDiagnostics function)

**Interfaces:**
- Produces: `GET /api/diagnostics` → `{ generatedAt: string, rooms: DiagRoom[] }`
- Produces: KV key `hb_history:<room>` → `Array<{ timestamp: string, status: string }>` (newest first, max 20)
- `DiagRoom`: `{ room, roomname, heartbeat: { status, timestamp, apkVersion, hasRefreshToken }, heartbeatHistory: [{timestamp, status}], alarmLog: [{event, ts, ...}], openIncidents: [] }`

- [ ] **Step 1: Add hb_history write inside the existing criticalChange/staleEnough gate in handleHeartbeat**

Find the block in `cloudflare-worker.js` that starts with `if (criticalChange || staleEnough) {` and contains the `await env.RIS_KV.put(roomKey, ...)` call. Immediately after that `put` call, add:

```javascript
      // Heartbeat history ring buffer — gives agent the same timeline view a human
      // gets from Cloudflare log view. Written on same gate as the heartbeat record.
      var hbHistKey = 'hb_history:' + data.room;
      var hbHistRaw = await env.RIS_KV.get(hbHistKey);
      var hbHist = hbHistRaw ? JSON.parse(hbHistRaw) : [];
      hbHist.unshift({ timestamp: new Date().toISOString(), status: newStatus });
      if (hbHist.length > 20) hbHist = hbHist.slice(0, 20);
      await env.RIS_KV.put(hbHistKey, JSON.stringify(hbHist), { expirationTtl: 604800 });
```

- [ ] **Step 2: Add the handleDiagnostics function**

Add this new function after the `handleStatus` function (around line 432):

```javascript
// ═══════════════════════════════════════
// DIAGNOSTICS — agent health snapshot
// ═══════════════════════════════════════

async function handleDiagnostics(env) {
  try {
    var list = await env.RIS_KV.list({ prefix: 'room:' });
    var rooms = [];

    for (var i = 0; i < list.keys.length; i++) {
      var val = await env.RIS_KV.get(list.keys[i].name);
      if (!val) continue;
      var record = JSON.parse(val);
      var room = record.room;

      var hbHistRaw = await env.RIS_KV.get('hb_history:' + room);
      var alarmRaw  = await env.RIS_KV.get('alarm_log:'  + room);

      rooms.push({
        room: room,
        roomname: record.roomname || '',
        heartbeat: {
          status:          record.status,
          timestamp:       record.timestamp,
          apkVersion:      record.apkVersion || '',
          hasRefreshToken: record.hasRefreshToken
        },
        heartbeatHistory: hbHistRaw ? JSON.parse(hbHistRaw) : [],
        alarmLog:         alarmRaw  ? JSON.parse(alarmRaw).slice(0, 20) : [],
        openIncidents:    []
      });
    }

    rooms.sort(function(a, b) {
      return (a.roomname || a.room).localeCompare(b.roomname || b.room);
    });

    return jsonResponse({ generatedAt: new Date().toISOString(), rooms: rooms });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}
```

- [ ] **Step 3: Add the /api/diagnostics route to the fetch router**

In the fetch router (the `if (path.startsWith('/api/'))` block), add this route after the `/api/status` route:

```javascript
      // GET /api/diagnostics — full per-room snapshot for health agent (protected)
      if (path === '/api/diagnostics' && method === 'GET') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        return handleDiagnostics(env);
      }
```

- [ ] **Step 4: Deploy the Worker**

```bash
cd "D:/OneDrive - Central Group/Claude.AI project/Room-Display"
git add cloudflare-worker.js
git commit -m "feat(worker): hb_history ring buffer + /api/diagnostics endpoint"
git push origin main
```

Wait ~2 min for GitHub Actions to deploy, then verify:

- [ ] **Step 5: Verify hb_history is being written**

Send a test heartbeat to confirm the ring buffer fills:

```bash
curl -s -X POST https://ris-display.ris-display.workers.dev/api/heartbeat \
  -H "Content-Type: application/json" \
  -d '{"room":"ristest@central.co.th","roomname":"Test","status":"online","restarted":true}' | python -m json.tool
```

Expected: `{"ok": true, "command": null}`

- [ ] **Step 6: Verify /api/diagnostics returns data**

```bash
curl -s https://ris-display.ris-display.workers.dev/api/diagnostics \
  -H "X-Admin-Key: RIS-ADMIN-KEY2026" | python -m json.tool
```

Expected: JSON with `generatedAt` and `rooms` array. Each room has `heartbeat`, `heartbeatHistory` (may be empty until next heartbeat write), `alarmLog`, `openIncidents`.

---

### Task 2: Worker — /api/fix-log endpoint

**Files:**
- Modify: `cloudflare-worker.js` (new handleFixLog function + route)

**Interfaces:**
- Consumes: Task 1's `checkAdminKey`, `jsonResponse`
- Produces: `POST /api/fix-log` → `{ ok: true }` (writes fix entry to KV)
- Produces: `GET /api/fix-log?room=<email>` → `Array<FixEntry>` (reads fix history)
- `FixEntry`: `{ timestamp, room, roomname, action, triggerCondition, rootCauseLabel, expectedOutcome, actualOutcome, resolvedAt, humanNote }`

- [ ] **Step 1: Add handleFixLog function**

Add after `handleDiagnostics`:

```javascript
// ═══════════════════════════════════════
// FIX LOG — agent action history
// ═══════════════════════════════════════

async function handleFixLog(request, env) {
  try {
    var url = new URL(request.url);
    if (request.method === 'GET') {
      var room = url.searchParams.get('room') || '';
      if (!room) return jsonResponse({ error: 'Missing room' }, 400);
      var raw = await env.RIS_KV.get('fix_log:' + room);
      return jsonResponse(raw ? JSON.parse(raw) : []);
    }

    // POST — agent logs a fix action
    var data = await request.json();
    if (!data.room || !data.action) {
      return jsonResponse({ error: 'Missing room or action' }, 400);
    }
    var entry = {
      timestamp:       new Date().toISOString(),
      room:            data.room,
      roomname:        data.roomname || '',
      action:          data.action,
      triggerCondition: data.triggerCondition || '',
      rootCauseLabel:  data.rootCauseLabel  || 'unknown',
      expectedOutcome: data.expectedOutcome || '',
      actualOutcome:   data.actualOutcome   || null,
      resolvedAt:      data.resolvedAt      || null,
      humanNote:       data.humanNote       || null
    };
    var key = 'fix_log:' + data.room;
    var raw = await env.RIS_KV.get(key);
    var log = raw ? JSON.parse(raw) : [];
    log.unshift(entry);
    if (log.length > 50) log = log.slice(0, 50);
    await env.RIS_KV.put(key, JSON.stringify(log), { expirationTtl: 604800 });
    return jsonResponse({ ok: true });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}
```

- [ ] **Step 2: Add /api/fix-log routes to the fetch router**

Add after the `/api/diagnostics` route:

```javascript
      // GET /api/fix-log?room=email — read agent fix history (protected)
      // POST /api/fix-log — agent logs a fix action (protected)
      if (path === '/api/fix-log') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        return handleFixLog(request, env);
      }
```

- [ ] **Step 3: Deploy and verify**

```bash
git add cloudflare-worker.js
git commit -m "feat(worker): /api/fix-log endpoint for agent action history"
git push origin main
```

After deploy, test write then read:

```bash
curl -s -X POST https://ris-display.ris-display.workers.dev/api/fix-log \
  -H "X-Admin-Key: RIS-ADMIN-KEY2026" \
  -H "Content-Type: application/json" \
  -d '{"room":"ristest@central.co.th","roomname":"Test","action":"reload","triggerCondition":"offline_35m_process_alive","rootCauseLabel":"unknown_process_death","expectedOutcome":"online_within_5m"}' | python -m json.tool
```

Expected: `{"ok": true}`

```bash
curl -s "https://ris-display.ris-display.workers.dev/api/fix-log?room=ristest%40central.co.th" \
  -H "X-Admin-Key: RIS-ADMIN-KEY2026" | python -m json.tool
```

Expected: Array with 1 entry matching what was posted.

---

### Task 3: Remove old cron from Worker

**Files:**
- Modify: `cloudflare-worker.js` (scheduled() function)

**Context:** The Worker currently runs three cron triggers:
- `0 1 * * *` (01:00 UTC / 08:00 BKK) — `checkMissedWakes()`
- `0 11 * * 6` (Friday 11:00 UTC / 18:00 BKK) — `generateWeeklyNoshowReport()`
- `0 14 * * *` (14:00 UTC / 21:00 BKK) — `generateDailyReport()` + `sendDailyHealthDigest()`

The Claude Code agent replaces all three. The scheduled() function should become a no-op.

- [ ] **Step 1: Replace scheduled() body with a no-op**

Find the `async scheduled(event, env)` function and replace its body:

```javascript
  async scheduled(event, env) {
    // Cron handling moved to Claude Code scheduled agent (ris-health-agent).
    // This function is kept as a no-op so the Worker cron binding can be
    // removed gradually without a deploy error.
  },
```

- [ ] **Step 2: Remove cron comment from file header**

Find the comment block at the top of the file (lines 23–26):

```javascript
  // Cron triggers:
  //   0 1  * * * (daily 01:00 UTC = 08:00 BKK) — missed-wake check (tablets should be up 30min after 07:30 alarm)
  //   0 11 * * 6 (Friday 11:00 UTC = 18:00 BKK) — weekly noshow report
  //   0 14 * * * (daily 14:00 UTC = 21:00 BKK) — daily health digest
```

Replace with:

```javascript
  // Cron triggers: removed — tablet health monitoring is now handled by the
  // Claude Code scheduled agent (ris-health-agent, runs 01:00 + 14:00 UTC).
```

- [ ] **Step 3: Deploy**

```bash
git add cloudflare-worker.js
git commit -m "feat(worker): remove cron handlers — replaced by Claude Code health agent"
git push origin main
```

---

### Task 4: Create the health agent definition

**Files:**
- Create: `.claude/agents/ris-health-agent.md`

**Context:** Claude Code scheduled agents read their instructions from an agent definition file. This file defines what the agent does on each run. The admin key `RIS-ADMIN-KEY2026` is included here since the repo is private. The Worker URL is `https://ris-display.ris-display.workers.dev`.

Target APK version comes from `apk-version.json` (currently `{"versionCode":118,"versionName":"5.87"}`). The agent fetches this at runtime via `GET /api/version`.

- [ ] **Step 1: Create .claude/agents/ directory**

```bash
mkdir -p "D:/OneDrive - Central Group/Claude.AI project/Room-Display/.claude/agents"
```

- [ ] **Step 2: Write the agent definition file**

Create `.claude/agents/ris-health-agent.md` with this exact content:

```markdown
---
description: RIS tablet health monitor — diagnose, auto-fix, and report tablet status twice daily
tools:
  - WebFetch
  - Bash
---

You are the RIS Tablet Health Agent. You run automatically at 08:00 and 21:00 BKK.

## Configuration

- Worker URL: https://ris-display.ris-display.workers.dev
- Admin key header: X-Admin-Key: RIS-ADMIN-KEY2026
- OTA cap: max 2 OTA updates per run (pick most outdated tablets first)
- Offline threshold for reload: heartbeat age > 30 min AND < 120 min
- Dead threshold (no action): heartbeat age >= 120 min (KV TTL expired — process dead)

## Each Run — Exact Steps

### 1. Get current BKK time

BKK = UTC + 7 hours. Determine if this is the morning run (01:00 UTC) or evening run (14:00 UTC).

### 2. Fetch target APK version

GET https://ris-display.ris-display.workers.dev/api/version

Parse `versionCode` and `versionName` from the JSON response. This is the target all tablets should be on.

### 3. Fetch diagnostics

GET https://ris-display.ris-display.workers.dev/api/diagnostics
Header: X-Admin-Key: RIS-ADMIN-KEY2026

### 4. For each room in the response, check fix_log from previous run

GET https://ris-display.ris-display.workers.dev/api/fix-log?room=<encoded room email>
Header: X-Admin-Key: RIS-ADMIN-KEY2026

Find any entry with `actualOutcome: null` from the previous run (within last 14 hours). Check if the tablet is now online — if yes, update the entry by POST-ing to /api/fix-log with the same fields plus `actualOutcome: "online"` and `resolvedAt: <now ISO>`.

### 5. Diagnose each tablet

For each room, compute:

**heartbeatAgeMin** = (now - heartbeat.timestamp) / 60000

**Classify each tablet into exactly one category:**

| Category | Condition |
|----------|-----------|
| HEALTHY | heartbeatAgeMin < 70 AND apkVersion matches target |
| HEALTHY_OUTDATED | heartbeatAgeMin < 70 AND apkVersion does NOT match target |
| RECOVERED | heartbeatAgeMin < 70 AND alarmLog contains `webview_process_restart` in last 2 hours |
| OFFLINE_RECOVERABLE | 30 <= heartbeatAgeMin < 120 |
| OFFLINE_DEAD | heartbeatAgeMin >= 120 |

RECOVERED takes priority over HEALTHY if both conditions are true.

**Determine root cause (evidence-based only):**

- If RECOVERED: "GPU freeze — webview_process_restart at [ts]. hb_history gap [from]–[to]. Watchdog self-recovered."
- If OFFLINE_RECOVERABLE and heartbeatHistory shows gap started just after a 07:30 wake_alarm event in alarmLog: "JS loop died after wake alarm. hb_history gap since [ts]."
- If OFFLINE_RECOVERABLE and heartbeatHistory shows gap started just after 06:00 and previous hb was <55 min ago: "KV stale-write skip at 06:00 restart (possible if v3.10.205 not yet deployed)."
- If OFFLINE_RECOVERABLE and no matching pattern: "Unknown — hb_history flat since [ts]. No webview_process_restart in alarm_log. Cause unclear without ADB."
- If OFFLINE_DEAD: "Process dead — KV record expired (>2h). Watchdog did not recover. Physical intervention needed (PoE cycle)."
- If HEALTHY_OUTDATED: "APK outdated — running [current] vs target [target]."

### 6. Apply auto-fixes

**Reload** (for OFFLINE_RECOVERABLE):
POST https://ris-display.ris-display.workers.dev/api/command
Header: X-Admin-Key: RIS-ADMIN-KEY2026
Body: {"room": "<email>", "command": "reload", "sentBy": "health_agent"}

**OTA update** (for HEALTHY_OUTDATED — max 2 per run, most outdated first):
POST https://ris-display.ris-display.workers.dev/api/command
Header: X-Admin-Key: RIS-ADMIN-KEY2026
Body: {"room": "<email>", "command": "perform_update", "sentBy": "health_agent"}

**Log every fix** to /api/fix-log immediately after sending the command:
POST https://ris-display.ris-display.workers.dev/api/fix-log
Header: X-Admin-Key: RIS-ADMIN-KEY2026
Body: {
  "room": "<email>",
  "roomname": "<name>",
  "action": "reload" | "perform_update",
  "triggerCondition": "<e.g. offline_47m_process_alive>",
  "rootCauseLabel": "<root cause label from diagnosis>",
  "expectedOutcome": "online_within_5m" | "updated_within_10m",
  "actualOutcome": null,
  "resolvedAt": null
}

**No action for OFFLINE_DEAD** — flag for physical intervention only.

### 7. Post the report

Format and post the report to this Claude Code session using the exact format below. Use real tablet names, real timestamps in BKK, real root causes from evidence.

```
🏥 RIS Health — [HH:MM] BKK [YYYY-MM-DD]  ([Morning|Evening] run)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ HEALTHY ([N]): [name] · [name] · ...

─── [Name] — ⚠️ RECOVERED ────────────────────────────
Status:      Back online (heartbeat [N]m ago)
Root cause:  [evidence-based diagnosis]
Fix applied: None — watchdog self-recovered.
Expected:    Stable. Next heartbeat due ~[HH:MM].
Post-fix:    Monitoring. Will confirm at [next run time] run.

─── [Name] — 🔴 OFFLINE [N]m ─────────────────────────
Status:      Last heartbeat [HH:MM], now [HH:MM] ([N]m gap).
Root cause:  [evidence-based diagnosis]
Fix applied: reload command sent at [HH:MM].
Expected:    Online within 5 min if process alive.
Post-fix:    Will verify at [next run time] run.

─── [Name] — 🔴 DEAD (>[N]h) ─────────────────────────
Status:      KV record expired. Last known heartbeat [HH:MM].
Root cause:  [evidence-based diagnosis]
Fix applied: None — process dead, reload won't help.
Action:      ⚠️ Physical intervention needed: PoE cycle or ADB.
Post-fix:    N/A — awaiting physical fix.

─── [Name] — 📦 OUTDATED ──────────────────────────────
Status:      Online — running APK [current] vs target [target].
Root cause:  OTA pending.
Fix applied: perform_update command sent at [HH:MM]. [Or: "Deferred (OTA cap reached this run)"]
Expected:    Updated and restarted within 10 min.
Post-fix:    Will verify at [next run time] run.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Physical intervention needed: [list rooms or "none"]
OTA this run: [N]/2 cap used ([names])

👤 Your notes for next run → reply to this message
```

If ALL tablets are healthy, the report is:

```
🏥 RIS Health — [HH:MM] BKK [YYYY-MM-DD]  ([Morning|Evening] run)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ ALL HEALTHY ([N] tablets) — nothing to action.
Next check: [HH:MM] BKK
```
```

- [ ] **Step 3: Commit the agent definition**

```bash
git add .claude/agents/ris-health-agent.md
git commit -m "feat(agent): add ris-health-agent definition for twice-daily tablet health monitoring"
git push origin main
```

---

### Task 5: Schedule the agent

**Context:** Use the Claude Code `schedule` skill to create two scheduled runs of the `ris-health-agent` — 01:00 UTC (08:00 BKK) and 14:00 UTC (21:00 BKK) daily.

**Interfaces:**
- Consumes: `.claude/agents/ris-health-agent.md` from Task 4
- Produces: Two cloud scheduled agents running daily, posting reports to this Claude Code session

- [ ] **Step 1: Invoke the schedule skill to create the morning run**

Use the `schedule` skill with the following parameters:
- Name: `ris-health-morning`
- Schedule: `0 1 * * *` (01:00 UTC = 08:00 BKK daily)
- Prompt: "Run the ris-health-agent for the morning (08:00 BKK) health check."
- Agent type: `ris-health-agent`

- [ ] **Step 2: Invoke the schedule skill to create the evening run**

Use the `schedule` skill with the following parameters:
- Name: `ris-health-evening`
- Schedule: `0 14 * * *` (14:00 UTC = 21:00 BKK daily)
- Prompt: "Run the ris-health-agent for the evening (21:00 BKK) health check."
- Agent type: `ris-health-agent`

- [ ] **Step 3: Verify both schedules are created**

Use `schedule` skill with `list` action. Confirm both `ris-health-morning` and `ris-health-evening` appear with correct cron times.

- [ ] **Step 4: Trigger a manual test run**

Use `schedule` skill to run `ris-health-morning` immediately (one-shot "run now"). Confirm the agent:
1. Fetches diagnostics successfully
2. Posts a report to this session
3. Any fix commands appear in the fix_log (check via `GET /api/fix-log?room=<a room email>`)

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|-----------------|------|
| hb_history ring buffer, KV key `hb_history:<room>`, 20 entries, 7-day TTL, written on same gate as heartbeat | Task 1 ✓ |
| GET /api/diagnostics — heartbeat, heartbeatHistory, alarmLog, openIncidents per room | Task 1 ✓ (openIncidents always [] for now — incidents data is complex, deferred) |
| fix_log KV key, 50 entries, 7-day TTL, GET + POST /api/fix-log | Task 2 ✓ |
| Remove 21:00 daily digest and Friday noshow cron | Task 3 ✓ |
| Agent diagnoses using 6 pattern rules from spec | Task 4 ✓ |
| Auto-fix: reload for OFFLINE_RECOVERABLE, OTA for HEALTHY_OUTDATED, max 2 OTA | Task 4 ✓ |
| Report format per spec | Task 4 ✓ |
| Scheduled 08:00 + 21:00 BKK | Task 5 ✓ |
| Phase C deferred | Not implemented ✓ |

**Note on openIncidents:** The spec calls for open incidents per room. Fetching from `incidents_index` inside a per-room loop would be expensive (one extra KV read per room). Deferred to a follow-up — the agent still has all other data needed for diagnosis.

**Placeholder scan:** No TBD/TODO/similar found.

**Type consistency:** `DiagRoom.heartbeatHistory` matches `hb_history` ring buffer structure throughout. `FixEntry` fields match between handleFixLog and agent definition.
