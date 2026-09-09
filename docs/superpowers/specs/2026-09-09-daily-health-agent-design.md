# RIS Tablet Daily Health Agent — Design Spec

**Date:** 2026-09-09  
**Author:** VoRutchapon  
**Status:** Approved for implementation

---

## Goal

Replace manual dashboard monitoring and the existing 21:00 daily report with a twice-daily Claude Code scheduled agent that collects tablet health data, diagnoses issues with evidence, applies safe auto-fixes, and posts a structured report to the Claude Code session for human review and input between runs.

## Background

Six tablets (expanding to 12) run a kiosk WebView APK on LG Android 4.4. The most critical failure window is 06:00–08:30 BKK daily — the restart alarm fires at 06:00 and the wake alarm at 07:30. Manual monitoring requires copying logs into Claude Code for analysis, which is slow and reactive. This agent makes monitoring proactive and evidence-based.

---

## Architecture

```
Cloudflare Worker  ──►  /api/diagnostics (new endpoint)
       │
       ▼
Scheduled Claude Code Agent  (08:00 + 21:00 BKK)
       │
       ├── Diagnose each tablet
       ├── Apply auto-fixes (reload / OTA, max 2 OTA per run)
       ├── Write fix outcome to fix_log KV
       └── Post structured report to Claude Code session
              │
              ▼
         User replies with notes / corrections
              │
              ▼
         Next run reads user notes → adjusts diagnosis
```

---

## Schedule

| Run | Time (BKK) | Focus |
|-----|-----------|-------|
| Morning | 08:00 | 06:00 restart + 07:30 wake window — highest risk period |
| Evening | 21:00 | End-of-day standby setup + overnight health |

The existing 21:00 daily summary report and the no-show report are **removed** — this agent replaces both.

---

## Data Collection

### New Worker Endpoint: `GET /api/diagnostics`

Protected by `X-Admin-Key` header. Returns a single JSON payload covering all rooms:

```json
{
  "generatedAt": "2026-09-10T01:00:00.000Z",
  "rooms": [
    {
      "room": "risaffogato@central.co.th",
      "roomname": "Affogato",
      "heartbeat": {
        "status": "online",
        "timestamp": "2026-09-10T00:31:00.000Z",
        "apkVersion": "5.87",
        "hasRefreshToken": true
      },
      "heartbeatHistory": [
        { "timestamp": "2026-09-10T00:31:00.000Z", "status": "online" },
        { "timestamp": "2026-09-09T23:01:00.000Z", "status": "online" }
      ],
      "alarmLog": [
        { "event": "wake_alarm", "timestamp": "...", "detail": "07:30" },
        { "event": "webview_process_restart", "timestamp": "...", "reason": "ping_timeout" }
      ],
      "openIncidents": []
    }
  ]
}
```

### Heartbeat History Ring Buffer (new KV key)

**Key:** `hb_history:<room>`  
**TTL:** 7 days  
**Capacity:** last 20 entries  
**Written:** every time the heartbeat KV record is written (same gate — criticalChange or staleEnough)  
**Structure:** array of `{ timestamp, status }`, newest first, capped at 20

This gives the agent the same timeline the human assembles manually from the Cloudflare log view.

### Existing Data Sources Used

| KV Key | Content |
|--------|---------|
| `heartbeat:<room>` | Current status, apkVersion, timestamp |
| `alarm_log:<room>` | Last 20 alarm events (wake, standby, restart, webview_process_restart) |
| `incident:<key>` | Open incidents with detail |
| `fix_log:<room>` | Agent fix history for Phase C confidence scoring (new) |

---

## Diagnosis Rules

A diagnosis is only filed if supporting evidence exists in the data. If no pattern matches, the agent states what data was checked and flags the tablet for human input.

| Pattern | Root Cause Label | Evidence Required | Confidence |
|---------|-----------------|-------------------|------------|
| hb_history gap after 07:30 wake event | JS loop died after wake alarm | Gap in hb_history + wake_alarm in alarm_log | High |
| `webview_process_restart` in alarm_log, tablet now online | GPU freeze — watchdog self-recovered | alarm_log entry + current heartbeat online | High |
| No heartbeat within 10 min of 06:00, previous hb <55 min old | KV stale-write skip (fixed v3.10.205, flag if still occurs) | hb_history gap at 06:00 window | High |
| Heartbeat age >2h (KV TTL expired), no alarm events | Process dead — watchdog did not recover | Flat hb_history, no alarm_log activity | High |
| apkVersion behind target version | OTA pending | heartbeat.apkVersion !== target | High |
| No pattern matched | Unknown — needs human input | States all data checked | Low |

---

## Auto-Fix Actions (Phase B)

| Fix | Trigger Condition | Expected Outcome | Limit |
|-----|------------------|-----------------|-------|
| Send `reload` command | Offline >30 min, heartbeat age <2h (process alive) | Tablet online within 5 min | Unlimited |
| Send OTA update | apkVersion behind target AND tablet currently online | Updated + restarted within 10 min | **Max 2 per run** |
| No action | Heartbeat age >2h (process dead) | Flagged for physical intervention (PoE cycle / ADB) | — |

**OTA selection when >2 tablets need updates:** agent picks the two most outdated (lowest versionCode). Remaining tablets deferred to next run.

**OTA daily cap:** 2 per run × 2 runs = max 4 OTA pushes per day across all tablets — well within Cloudflare limits.

---

## Report Format

Posted to the Claude Code session after each run. One block per unhealthy tablet; healthy tablets listed on one line.

```
🏥 RIS Health — 08:00 BKK 2026-09-10
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ HEALTHY (4): Macchiato · Viennese · Decaffinato · Latte

─── Affogato — ⚠️ RECOVERED ──────────────────────────
Status:          Back online (heartbeat 3m ago)
Root cause:      GPU freeze — webview_process_restart at 07:38.
                 hb_history shows gap 07:13–07:41. Watchdog fired,
                 process restarted, first heartbeat 07:41.
Fix applied:     None — watchdog self-recovered before agent run.
Expected:        Stable. Next heartbeat due ~08:11.
Post-fix:        Monitoring. Will confirm at 21:00 run.

─── Mocha — 🔴 OFFLINE 47m ───────────────────────────
Status:          Last heartbeat 07:13, now 08:00 (47m gap).
Root cause:      hb_history flat since 07:13. No
                 webview_process_restart in alarm_log → watchdog
                 did not fire → process likely died before 10-min
                 ping timeout elapsed. Cause unknown without ADB.
Fix applied:     reload command sent at 08:02.
Expected:        Online within 5 min if process alive.
Post-fix:        Will verify at 21:00 run. If still offline → PoE cycle needed.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Physical intervention needed: none confirmed yet (Mocha pending)
OTA: none (all tablets on v5.87)

👤 Your notes for next run → reply to this message
```

---

## User Input Loop

After posting, the agent reads the previous run's reply thread at the start of the next run. User notes adjust diagnosis — for example:

> "Mocha had a PoE switch issue, I cycled it at 09:00"

The agent updates the fix_log entry from "unknown process death" to "confirmed power loss — PoE cycle resolved" and factors this into pattern learning.

---

## Fix Outcome Logging

Each auto-fix is written to `fix_log:<room>` KV (7-day TTL) with:

```json
{
  "timestamp": "2026-09-10T01:02:00.000Z",
  "action": "reload",
  "triggerCondition": "offline_47m_process_alive",
  "rootCauseLabel": "unknown_process_death",
  "expectedOutcome": "online_within_5m",
  "actualOutcome": null,
  "resolvedAt": null,
  "humanNote": null
}
```

The evening run fills in `actualOutcome` and `resolvedAt` based on the current heartbeat.

---

## Phase C Promotion Path

After 14 days of fix_log data, the agent scores each fix type:

**Success rate** = fixes where tablet came back online within 15 min / total fixes of that type

| Success Rate | Action |
|-------------|--------|
| ≥90% over 14 days | Fix type promoted to silent — no per-tablet block in report, only weekly summary line |
| 70–89% | Fix type stays in report with confidence note |
| <70% | Fix type demoted — flagged for human review before applying |

Phase C begins only when the user explicitly approves the promotion for each fix type after reviewing the 14-day score. The 08:00/21:00 reports always continue — Phase C reduces noise, not visibility.

---

## KV Write Budget (12 tablets at full rollout)

| Source | Writes/day |
|--------|-----------|
| Heartbeat + hb_history (same gate) | ~576 |
| `restarted` flag writes | ~36 |
| alarm_log | ~60 |
| fix_log + commands | ~20 |
| **Total** | **~692** |

Cloudflare KV free tier limit: **1,000 writes/day**. ~30% headroom at 12 tablets.

If headroom tightens: reduce hb_history to write only on criticalChange (halves those writes to ~288, total ~404/day).

---

## Implementation Scope

### Sub-project 1: Worker changes
- Add `hb_history:<room>` ring buffer write to heartbeat handler
- Add `fix_log:<room>` write on agent-triggered actions
- Add `GET /api/diagnostics` endpoint

### Sub-project 2: Scheduled agent
- Claude Code scheduled agent (cron: 08:00 + 21:00 BKK)
- Reads `/api/diagnostics`, runs diagnosis rules, applies fixes
- Posts structured report to Claude Code session
- Reads previous session reply for user notes

### Sub-project 3: Phase C scoring (deferred)
- 14-day fix_log aggregation
- Promotion scoring per fix type
- User approval gate before any silent promotion

Sub-projects 1 and 2 are built together (agent is useless without the diagnostics endpoint). Sub-project 3 is deferred until 14 days of fix_log data exists.

---

## What Is Removed

- ❌ Existing 21:00 daily summary report
- ❌ No-show report
- ✅ All other existing Worker endpoints, KV keys, and alarm events are unchanged
