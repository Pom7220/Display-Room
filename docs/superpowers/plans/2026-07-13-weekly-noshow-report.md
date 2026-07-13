# Weekly No-Show Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send a weekly HTML email to vorutchapon@central.co.th every Friday 17:00 BKK summarising room no-show incidents for the week.

**Architecture:** All logic lives in `cloudflare-worker.js`. A second Cloudflare cron trigger (`0 10 * * 5`) calls `generateWeeklyNoshowReport(env)` which reads the existing `incidents_index` KV, aggregates no-show data, and sends via Microsoft Graph `sendMail`. A manual-trigger endpoint (`POST /api/noshow/send-report`) allows testing without waiting for Friday.

**Tech Stack:** Cloudflare Workers (JS), Cloudflare KV, Microsoft Graph API (`/v1.0/users/{user}/sendMail`), existing `getServiceToken()` ROPC helper.

## Global Constraints

- `cloudflare-worker.js` only — no APK, no `index.html` changes.
- No new KV keys or infrastructure.
- Use existing `getServiceToken(env)` for Graph auth — do not duplicate token logic.
- BKK timezone = UTC+7. All time calculations must account for this offset explicitly.
- Email always sends even when no-show count is zero.
- Worker cron handler must never throw — catch all errors and log.
- No `wrangler` CLI available — cron trigger must be added manually in Cloudflare dashboard Triggers tab after deploy.

---

### Task 1: Report data aggregator + email sender

**Files:**
- Modify: `cloudflare-worker.js` — add `generateWeeklyNoshowReport(env)` and `sendWeeklyEmail(env, data)` functions

**Interfaces:**
- Produces: `generateWeeklyNoshowReport(env)` → `Promise<void>` (fetches data, builds email, sends it)
- Internal: `sendWeeklyEmail(env, {total, weekStart, weekEnd, byOrganizer, byRoom})` → `Promise<void>`
- Consumed by: Task 2 `scheduled()` handler and manual trigger endpoint

- [ ] **Step 1: Add `generateWeeklyNoshowReport` function**

Add this function to `cloudflare-worker.js` immediately before the `// HELPERS` section (around line 650 — after `handleNoshowStats` and before `jwtClaim`):

```javascript
// ═══════════════════════════════════════
// WEEKLY NO-SHOW REPORT — email summary
// ═══════════════════════════════════════

async function generateWeeklyNoshowReport(env) {
  try {
    // Compute Monday 00:00 BKK of the current week.
    // BKK = UTC+7. We shift "now" by +7h, find Monday in that space,
    // then shift back to UTC for comparison against stored ISO strings.
    var nowUtcMs = Date.now();
    var bkkOffsetMs = 7 * 60 * 60 * 1000;
    var nowBkk = new Date(nowUtcMs + bkkOffsetMs);

    // Day of week in BKK: 0=Sun,1=Mon,...,6=Sat
    var dowBkk = nowBkk.getUTCDay();
    // Days since Monday (Mon=0 offset)
    var daysSinceMon = (dowBkk === 0) ? 6 : dowBkk - 1;

    // Monday 00:00 BKK as UTC ms
    var monBkkMs = nowUtcMs - (daysSinceMon * 86400000)
      - (nowBkk.getUTCHours() * 3600000)
      - (nowBkk.getUTCMinutes() * 60000)
      - (nowBkk.getUTCSeconds() * 1000)
      - nowBkk.getUTCMilliseconds();
    var monCutoff = new Date(monBkkMs).toISOString();

    // Friday 17:00 BKK = Friday 10:00 UTC — use nowUtcMs as upper bound
    var weekEndLabel = nowBkk; // "now" in BKK is Friday 17:00

    // Format helper: "13 Jul 2026"
    var months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    function fmtDate(d) {
      return d.getUTCDate() + ' ' + months[d.getUTCMonth()] + ' ' + d.getUTCFullYear();
    }
    var monLabel = fmtDate(new Date(monBkkMs));
    var friLabel = fmtDate(weekEndLabel);

    // Query incidents_index for noshow entries this week
    var indexRaw = await env.RIS_KV.get('incidents_index');
    var index = indexRaw ? JSON.parse(indexRaw) : [];

    var noshows = [];
    for (var i = 0; i < index.length; i++) {
      var val = await env.RIS_KV.get(index[i]);
      if (!val) continue;
      var record = JSON.parse(val);
      // incidents_index is newest-first; stop when before cutoff
      if (record.reportedAt < monCutoff) break;
      if (record.type !== 'noshow') continue;
      var detail = {};
      try { detail = JSON.parse(record.detail); } catch(e) {}
      noshows.push({
        room: record.room,
        roomname: record.roomname || record.room,
        organizer: detail.organizer || '',
        organizerEmail: detail.organizerEmail || '',
        subject: detail.subject || '(no title)'
      });
    }

    // Aggregate by organizer
    var orgMap = {};
    noshows.forEach(function(n) {
      var key = n.organizerEmail || n.organizer || 'unknown';
      if (!orgMap[key]) orgMap[key] = { name: n.organizer, email: n.organizerEmail, count: 0 };
      orgMap[key].count++;
    });
    var byOrganizer = Object.keys(orgMap).map(function(k) { return orgMap[k]; });
    byOrganizer.sort(function(a, b) { return b.count - a.count; });

    // Aggregate by room
    var roomMap = {};
    noshows.forEach(function(n) {
      var key = n.room;
      if (!roomMap[key]) roomMap[key] = { name: n.roomname, count: 0 };
      roomMap[key].count++;
    });
    var byRoom = Object.keys(roomMap).map(function(k) { return roomMap[k]; });
    byRoom.sort(function(a, b) { return b.count - a.count; });

    await sendWeeklyEmail(env, {
      total: noshows.length,
      monLabel: monLabel,
      friLabel: friLabel,
      byOrganizer: byOrganizer.slice(0, 3),
      byRoom: byRoom.slice(0, 3)
    });
  } catch(e) {
    console.error('generateWeeklyNoshowReport failed:', e.message);
  }
}

async function sendWeeklyEmail(env, data) {
  var token = await getServiceToken(env);
  if (!token) {
    console.error('sendWeeklyEmail: getServiceToken returned null — check RIS_SVC_USER/PASSWORD');
    return;
  }

  var hasIncidents = data.total > 0;
  var weekLabel = data.monLabel + ' – ' + data.friLabel;

  var subject = hasIncidents
    ? '[RIS] Weekly No-Show Report — Week of ' + data.monLabel
    : '[RIS] Weekly No-Show Report — All Clear ✓';

  // Build HTML body
  var bodyRows = '';
  if (!hasIncidents) {
    bodyRows = '<p style="color:#2e7d32">No meetings were released as no-show this week.</p>';
  } else {
    // Top organizers table
    var orgRows = data.byOrganizer.map(function(o, i) {
      return '<tr><td style="padding:4px 12px 4px 0">' + (i+1) + '. ' + (o.name || o.email)
        + '</td><td style="padding:4px 0;color:#555">' + (o.email ? '(' + o.email + ')' : '')
        + '</td><td style="padding:4px 0 4px 16px;text-align:right;font-weight:bold">'
        + o.count + (o.count === 1 ? ' time' : ' times') + '</td></tr>';
    }).join('');

    // Top rooms table
    var roomRows = data.byRoom.map(function(r, i) {
      return '<tr><td style="padding:4px 12px 4px 0">' + (i+1) + '. ' + r.name
        + '</td><td style="padding:4px 0 4px 16px;text-align:right;font-weight:bold">'
        + r.count + (r.count === 1 ? ' no-show' : ' no-shows') + '</td></tr>';
    }).join('');

    bodyRows = '<p><strong>Total no-shows this week: ' + data.total + '</strong><br>'
      + '<span style="color:#666;font-size:13px">Period: ' + weekLabel + '</span></p>'
      + '<h3 style="margin-bottom:6px">Top Organizers</h3>'
      + '<table style="border-collapse:collapse;font-size:14px">' + orgRows + '</table>'
      + '<h3 style="margin-top:20px;margin-bottom:6px">Most Affected Rooms</h3>'
      + '<table style="border-collapse:collapse;font-size:14px">' + roomRows + '</table>';
  }

  var html = '<!DOCTYPE html><html><body style="font-family:sans-serif;max-width:600px;padding:24px">'
    + '<h2 style="color:#1a1a2e">RIS Room Display — Weekly No-Show Report</h2>'
    + '<p style="color:#555;font-size:13px">Week: ' + weekLabel + '</p>'
    + '<hr style="border:none;border-top:1px solid #eee;margin:16px 0">'
    + bodyRows
    + '<hr style="border:none;border-top:1px solid #eee;margin:24px 0">'
    + '<p style="color:#999;font-size:11px">Sent automatically by RIS Room Display every Friday 17:00 BKK.</p>'
    + '</body></html>';

  var mailPayload = {
    message: {
      subject: subject,
      body: { contentType: 'HTML', content: html },
      toRecipients: [{ emailAddress: { address: 'vorutchapon@central.co.th' } }]
    },
    saveToSentItems: false
  };

  var svcUser = env.RIS_SVC_USER || '';
  var resp = await fetch(
    'https://graph.microsoft.com/v1.0/users/' + encodeURIComponent(svcUser) + '/sendMail',
    {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + token,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(mailPayload)
    }
  );

  if (resp.status === 202) {
    console.log('Weekly no-show email sent OK');
  } else {
    var errBody = await resp.text().catch(function(){ return ''; });
    console.error('sendWeeklyEmail Graph error', resp.status, errBody);
  }
}
```

- [ ] **Step 2: Add manual trigger endpoint for testing**

In the `fetch()` handler, add this route immediately after the `/api/noshow` route:

```javascript
      // POST /api/noshow/send-report — manually trigger weekly email (protected, for testing)
      if (path === '/api/noshow/send-report' && method === 'POST') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        await generateWeeklyNoshowReport(env);
        return jsonResponse({ ok: true, message: 'Weekly report triggered' });
      }
```

- [ ] **Step 3: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat: weekly no-show email report — aggregator + Graph sendMail"
```

---

### Task 2: Wire into scheduled() handler + Cloudflare cron trigger

**Files:**
- Modify: `cloudflare-worker.js` — update `scheduled()` to detect Friday and call `generateWeeklyNoshowReport`

**Interfaces:**
- Consumes: `generateWeeklyNoshowReport(env)` from Task 1
- Produces: Friday 10:00 UTC cron fires weekly email

- [ ] **Step 1: Update `scheduled()` handler**

The current `scheduled()` at the top of `cloudflare-worker.js` looks like:

```javascript
  async scheduled(event, env) {
    if (!env.RIS_KV) return;
    await generateDailyReport(env);
  },
```

Replace it with:

```javascript
  async scheduled(event, env) {
    if (!env.RIS_KV) return;

    // Detect which cron fired by checking day-of-week in BKK time.
    // Weekly report cron: 0 10 * * 5 (Friday 10:00 UTC = 17:00 BKK)
    // Daily report cron:  0 13 * * * (daily 13:00 UTC = 20:00 BKK)
    var nowBkkDay = new Date(Date.now() + 7 * 3600000).getUTCDay(); // 5 = Friday
    var isFriday = nowBkkDay === 5;

    await generateDailyReport(env);
    if (isFriday) {
      await generateWeeklyNoshowReport(env);
    }
  },
```

Note: both reports run on Friday — daily at 20:00 BKK, weekly at 17:00 BKK. The `isFriday` check on the 17:00 BKK cron correctly fires only on Fridays. The daily cron at 20:00 BKK also runs on Fridays but `isFriday` there is also true — this is acceptable (daily report + weekly report both generate on Friday evenings, 3h apart).

- [ ] **Step 2: Verify logic manually**

Check: on Friday at 10:00 UTC, `new Date(Date.now() + 7*3600000).getUTCDay()` = 5. On other days it will be 0–4 or 6, so `isFriday` = false. No test framework available — reason through it.

- [ ] **Step 3: Commit**

```bash
git add cloudflare-worker.js
git commit -m "feat: wire weekly no-show report into scheduled() cron handler"
```

- [ ] **Step 4: Push to GitHub**

```bash
git push
```

- [ ] **Step 5: Deploy Worker + add cron trigger in Cloudflare dashboard**

1. Copy full `cloudflare-worker.js` content into the Cloudflare Worker editor and click **Deploy**.
2. Go to Worker → **Triggers** tab → **Cron Triggers** → **Add Cron Trigger**.
3. Enter: `0 10 * * 5` → Save.
4. Existing `0 13 * * *` trigger remains unchanged.

- [ ] **Step 6: Test via manual endpoint**

```bash
curl -X POST https://ris-display.ris-display.workers.dev/api/noshow/send-report \
  -H "X-Admin-Key: <your-admin-key>"
```

Expected response: `{"ok":true,"message":"Weekly report triggered"}`
Expected result: email arrives at vorutchapon@central.co.th within ~30 seconds.

If no email arrives, check Cloudflare Worker logs (Dashboard → Worker → Logs) for `sendWeeklyEmail Graph error` lines.
