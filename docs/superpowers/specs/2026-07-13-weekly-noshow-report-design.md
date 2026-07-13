# Weekly No-Show Report — Design Spec

**Date:** 2026-07-13  
**Status:** Approved

---

## Goal

Send a weekly email summary of room no-show incidents to the RIS admin every Friday at 17:00 BKK, so patterns (repeat offenders, problem rooms) are visible without manual API queries.

---

## Scope

- Admin summary email only (Option B). Organizer personal emails (Option C) are out of scope for this iteration.
- Delivery via Microsoft Graph `sendMail` using existing service account credentials.
- No new infrastructure — extends the existing Cloudflare Worker.

---

## Trigger

| Field | Value |
|---|---|
| Cron expression | `0 10 * * 5` |
| Fires at | Friday 10:00 UTC = 17:00 BKK |
| Report period | Monday 00:00 BKK → Friday 17:00 BKK (current week) |

The existing `scheduled()` handler gains an `isFriday` branch. Daily report logic (existing `0 13 * * *` trigger) is unchanged.

---

## Data Source

Query `incidents_index` KV for all entries where:
- `type === "noshow"`
- `reportedAt >= Monday 00:00 BKK` of current week

Parse `detail` JSON field on each record for: `organizer`, `organizerEmail`, `subject`, `meetingStart`, `meetingEnd`, `room`, `roomname`.

---

## Email Spec

**To:** `vorutchapon@central.co.th`  
**From:** RIS service account (`RIS_SVC_USER`)  
**Subject (with incidents):** `[RIS] Weekly No-Show Report — Week of DD MMM YYYY`  
**Subject (zero incidents):** `[RIS] Weekly No-Show Report — All Clear ✓`

**Body (HTML):**

```
Total no-shows this week: N
Period: Mon DD MMM – Fri DD MMM YYYY

Top organizers (by count):
1. John Doe (john@central.co.th) — 3 times
2. Jane Smith (jane@central.co.th) — 2 times
3. Bob Lee (bob@central.co.th) — 1 time
   [max 3 shown]

Most affected rooms:
1. Latte — 3 no-shows
2. Viennese — 2 no-shows
   [max 3 shown]
```

Zero-incident week: single line — "No meetings were released as no-show this week."

Email always sends (zero or not) to keep cadence consistent — silence ≠ broken.

---

## Implementation

**`cloudflare-worker.js` changes only:**

1. Add `0 10 * * 5` to `wrangler.toml` cron triggers (or Worker dashboard Triggers tab).
2. In `scheduled(event, env)`: detect Friday trigger by cron expression or event timestamp day-of-week, call `generateWeeklyNoshowReport(env)`.
3. `generateWeeklyNoshowReport(env)`:
   - Compute Monday 00:00 BKK start of current week (UTC offset +7).
   - Filter `incidents_index` for `type=noshow` within window.
   - Aggregate: total count, top-3 organizers, top-3 rooms.
   - Build HTML email string.
   - Call `getServiceToken(env)` for Graph token.
   - `POST /v1.0/users/{RIS_SVC_USER}/sendMail` with JSON body.

No APK changes. No `index.html` changes. No new KV keys.

---

## Error Handling

- If `getServiceToken` fails: log error, do not throw (Worker cron must not crash).
- If Graph `sendMail` returns non-2xx: log response body, continue.
- If `incidents_index` is empty or missing: send zero-incident email normally.

---

## Out of Scope

- Organizer personal email nudges (future Option C).
- Teams bot delivery (deferred — requires Azure bot registration).
- Dashboard UI panel for no-show stats.
- Auto-cancel or hold policy enforcement.
