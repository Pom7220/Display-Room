# Event-Driven Heartbeat + Adaptive Incident Monitoring — Design Spec

## Goal

Replace polling-based KV writes (every heartbeat) with event-driven writes (state changes only), reducing daily KV writes from ~1,150 (12 tablets, free plan limit) to ~71. During incidents, restore 20-min heartbeat writes for the affected room only, giving granular troubleshooting data exactly when needed.

## Background

Cloudflare KV free plan limit: 1,000 writes/day. At 12 tablets × 48 heartbeats/day × 2 writes each = 1,152 writes/day — over the limit before any alarm events or testing. Event-driven model brings this to ~71/day with headroom for growth.

## Architecture

Three layers of change:

1. **Worker** — heartbeat handler skips KV writes during healthy state; opens/closes incident-active flags; reads incident flag to decide write behaviour per room
2. **Worker** — heartbeat response includes `heartbeatIntervalMs` command when incident is active, reverting when closed
3. **APK (BootReceiver)** — logcat additions for clock correction tracing

---

## Section 1: Normal State (No Incident)

### Worker — `/api/heartbeat` handler

**Current:** Every heartbeat writes `roomKey` (live status) + `hbHistKey` (ring buffer) to KV = 2 writes per heartbeat.

**New:** On each heartbeat POST, check `incident_active:{room}` flag in KV.

- If flag **absent**: skip `roomKey` and `hbHistKey` writes. Update only in-memory state (existing). Return 200 with `heartbeatIntervalMs: 1800000` (30 min).
- If flag **present**: write `roomKey` + `hbHistKey` as today (full record). Return 200 with `heartbeatIntervalMs: 1200000` (20 min).

### Dashboard — live status display

Replace "last heartbeat X min ago" with time-since-last-event:

- Source: last `cold_boot`, `wake`, or heartbeat-during-incident timestamp
- Display: "Online — 4h 12m" (duration since last known state change to online)
- Grey dot threshold: unchanged (based on expected interval × 2 miss tolerance)

### KV writes in normal state

| Event | Writes |
|---|---|
| `cold_boot` | 2 (roomKey + hbHist snapshot) |
| `wake` | 1 |
| `standby` | 1 |
| Heartbeat (healthy) | 0 |

---

## Section 2: Incident Detection & Adaptive Heartbeat

### Incident open

Triggered when Worker detects N consecutive missed heartbeats (existing threshold, unchanged).

On open, additional writes:
- `incident_active:{room}` = `"1"` (no TTL — explicitly deleted on close)
- `cmd:incident_heartbeat:{room}` = `"1"` (no TTL — tells tablet to switch to 20-min interval)
- Incident record + index as today

### Heartbeat during incident

Tablet reads `heartbeatIntervalMs` from response JSON and updates its interval (existing `heartbeatIntervalMs` field already supported in tablet JS bridge — confirmed in KioskWebViewActivity).

Worker writes `roomKey` + `hbHistKey` on every heartbeat while `incident_active:{room}` flag is set — same as current behaviour, at 20-min cadence.

### Incident close

Triggered when heartbeat resumes after incident (existing logic).

On close:
- Delete `incident_active:{room}` from KV
- Delete `cmd:incident_heartbeat:{room}` from KV
- Write incident close record as today
- Next heartbeat response returns `heartbeatIntervalMs: 1800000` → tablet reverts to 30 min

### KV writes during incident (worst case: 3 tablets, 4 hours)

| Event | Writes |
|---|---|
| Incident open × 3 | 15 |
| Heartbeats × 3 tablets × 12 per 4h × 2 | 72 |
| Incident close × 3 | 9 |
| Routine events | 48 |
| **Total** | **~144/day** |

Well under 1,000 free plan limit.

---

## Section 3: Logcat Additions in BootReceiver (v5.104)

Add `android.util.Log.d("BootReceiver", ...)` calls in `fixClockAndLogBoot()`:

```java
// Before correction
Log.d("BootReceiver", "clock before fix: " + new java.util.Date().toString());

// After HTTP call, before su 0 date
Log.d("BootReceiver", "server Date header: " + dateHeader);
Log.d("BootReceiver", "setting clock to BKK: " + dateStr);

// After su 0 date
Log.d("BootReceiver", "clock after fix: " + new java.util.Date().toString());
// On exception
Log.e("BootReceiver", "fixClockAndLogBoot failed: " + e.getMessage());
```

Accessible during troubleshooting via:
```
adb -s <ip>:5555 logcat -s BootReceiver
```

Zero KV writes. Cleared on reboot — for live sessions only, not post-mortem.

---

## Implementation Scope

| Component | File | Change |
|---|---|---|
| Worker | `cloudflare-worker.js` | `/api/heartbeat` handler — add incident_active flag check, conditional KV writes, heartbeatIntervalMs in response |
| Worker | `cloudflare-worker.js` | Incident open/close — add/remove `incident_active` and `cmd:incident_heartbeat` flags |
| Worker | `cloudflare-worker.js` | Dashboard endpoint — change live status to time-since-event format |
| APK | `BootReceiver.java` | Add Log.d calls in `fixClockAndLogBoot` |
| APK | `build.gradle` | Bump to v5.104 |

## Out of Scope

- No APK changes needed for heartbeat interval switching — tablet JS already reads `heartbeatIntervalMs` from the Worker response and adjusts automatically; only Worker changes required
- Maintenance mode (no longer needed given ~71 writes/day baseline)
- Cloudflare plan upgrade
