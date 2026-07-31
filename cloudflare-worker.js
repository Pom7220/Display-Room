/**
 * RIS Room Display — Cloudflare Worker
 * v2.0 — Proxy + Remote Monitoring API
 *
 * Routes:
 *   GET  /              → proxy to GitHub Pages (existing)
 *   GET  /*             → proxy to GitHub Pages (existing)
 *   POST /api/heartbeat → tablet sends health report (every 20 min)
 *   GET  /api/status    → admin dashboard reads all room statuses
 *   POST /api/command   → admin sends command to a tablet
 *   GET  /api/command?room=email → tablet polls for pending command
 *   GET  /api/incidents → incident history with resolution tracking
 *   POST /api/incident  → report or resolve an incident
 *
 * KV Keys:
 *   room:{email}        → latest heartbeat JSON
 *   cmd:{email}         → pending command JSON (deleted after tablet reads)
 *   incident:{timestamp}→ incident record JSON
 *   incidents_index     → array of incident keys (last 200)
 */

export default {
  // Cron triggers:
  //   0 1  * * * (daily 01:00 UTC = 08:00 BKK) — missed-wake check (tablets should be up 30min after 07:30 alarm)
  //   0 11 * * 6 (Friday 11:00 UTC = 18:00 BKK) — weekly noshow report
  //   0 14 * * * (daily 14:00 UTC = 21:00 BKK) — daily health digest
  async scheduled(event, env) {
    if (!env.RIS_KV) return;

    var nowUtcHour = new Date(Date.now()).getUTCHours();
    if (nowUtcHour === 1) {
      // 08:00 BKK — check for tablets that failed to wake at 07:30
      await checkMissedWakes(env);
      return;
    }

    var nowBkkDay = new Date(Date.now() + 7 * 3600000).getUTCDay(); // 0=Sun, 5=Fri, 6=Sat
    var isFriday = nowBkkDay === 5;
    var report = await generateDailyReport(env);
    await sendDailyHealthDigest(env, report);
    if (isFriday) {
      await generateWeeklyNoshowReport(env);
    }
  },

  async fetch(request, env) {
    var url = new URL(request.url);
    var path = url.pathname;
    var method = request.method;

    // ── CORS headers for API routes ──
    if (method === 'OPTIONS') {
      return new Response(null, {
        headers: corsHeaders()
      });
    }

    // ── API ROUTES ──
    if (path.startsWith('/api/')) {
      // Require KV binding (except calendar/book/event which only need ROPC secrets)
      var calendarPaths = ['/api/calendar', '/api/book', '/api/event'];
      var isCalendarPath = calendarPaths.indexOf(path) > -1;
      if (!env.RIS_KV && !isCalendarPath) {
        return jsonResponse({ error: 'KV not configured' }, 500);
      }

      // GET /api/calendar — fetch room calendar via service account
      if (path === '/api/calendar' && method === 'GET') {
        return handleCalendar(request, url, env);
      }

      // POST /api/book — book a room via service account
      if (path === '/api/book' && method === 'POST') {
        return handleBook(request, env);
      }

      // PATCH /api/event — extend a meeting via service account
      if (path === '/api/event' && method === 'PATCH') {
        return handleEventPatch(request, env);
      }

      // DELETE /api/event — auto-release: delete room event via service account
      if (path === '/api/event' && method === 'DELETE') {
        return handleEventDelete(request, env);
      }

      // GET /api/version — proxies apk-version.json for Android 4.4 (no TLS 1.2 direct)
      if (path === '/api/version' && method === 'GET') {
        return handleVersion();
      }

      // GET /api/index-version — returns the latest deployed index.html version
      if (path === '/api/index-version' && method === 'GET') {
        return handleIndexVersion();
      }

      // GET /api/apk — proxies APK binary so tablets never hit GitHub Pages TLS directly
      if (path === '/api/apk' && method === 'GET') {
        return handleApk();
      }

      // POST /api/heartbeat — tablet sends health report
      if (path === '/api/heartbeat' && method === 'POST') {
        return handleHeartbeat(request, env);
      }

      // POST /api/alarm — APK logs alarm events (standby/wake/restart)
      if (path === '/api/alarm' && method === 'POST') {
        return handleAlarmLog(request, env);
      }

      // GET /api/status — admin reads all room statuses
      if (path === '/api/status' && method === 'GET') {
        return handleStatus(env);
      }

      // POST /api/command — admin sends command to tablet (protected)
      if (path === '/api/command' && method === 'POST') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        return handleCommandSet(request, env);
      }

      // GET /api/command?room=email — tablet polls for command
      if (path === '/api/command' && method === 'GET') {
        return handleCommandGet(url, env);
      }

      // GET /api/noshow — no-show incidents filtered and stats (protected)
      if (path === '/api/noshow' && method === 'GET') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        return handleNoshowStats(url, env);
      }

      // POST /api/health/send-digest — manually trigger daily health digest email
      if (path === '/api/health/send-digest' && method === 'POST') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        var digestReport = await generateDailyReport(env);
        await sendDailyHealthDigest(env, digestReport);
        return jsonResponse({ ok: true });
      }

      // POST /api/noshow/send-report — manually trigger weekly email (protected, for testing)
      if (path === '/api/noshow/send-report' && method === 'POST') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        await generateWeeklyNoshowReport(env);
        return jsonResponse({ ok: true, message: 'Weekly report triggered' });
      }

      // GET /api/incidents — incident history (protected)
      if (path === '/api/incidents' && method === 'GET') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        return handleIncidentsList(url, env);
      }

      // POST /api/incident — report or resolve incident
      if (path === '/api/incident' && method === 'POST') {
        return handleIncidentReport(request, env);
      }

      // GET /api/reports — daily summary reports
      if (path === '/api/reports' && method === 'GET') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        return handleReportsList(url, env);
      }

      // GET /api/test-reauth — test ROPC credentials without sending to tablet
      if (path === '/api/test-reauth' && method === 'GET') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        var svcUser = env.RIS_SVC_USER || '';
        var svcPass = env.RIS_SVC_PASSWORD || '';
        var tenantId = env.RIS_TENANT_ID || '';
        var clientId = env.RIS_CLIENT_ID || '';
        if (!svcUser || !svcPass || !tenantId || !clientId) {
          return jsonResponse({ error: 'Missing secrets', have: {
            RIS_SVC_USER: !!svcUser, RIS_SVC_PASSWORD: !!svcPass,
            RIS_TENANT_ID: !!tenantId, RIS_CLIENT_ID: !!clientId
          }});
        }
        try {
          var tokenUrl = 'https://login.microsoftonline.com/' + tenantId + '/oauth2/v2.0/token';
          var clientSecret = env.RIS_CLIENT_SECRET || '';
          var body = 'client_id=' + encodeURIComponent(clientId)
            + (clientSecret ? '&client_secret=' + encodeURIComponent(clientSecret) : '')
            + '&scope=' + encodeURIComponent('Calendars.ReadWrite openid offline_access')
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
            return jsonResponse({ ok: false, error: data.error, description: data.error_description });
          }
          return jsonResponse({ ok: true, message: 'ROPC works', has_access_token: !!data.access_token, has_refresh_token: !!data.refresh_token });
        } catch(e) {
          return jsonResponse({ ok: false, error: e.message });
        }
      }

      // POST /api/reports/generate — manually trigger report generation
      if (path === '/api/reports/generate' && method === 'POST') {
        if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
        if (!env.RIS_KV) return jsonResponse({ error: 'KV not configured' }, 500);
        var report = await generateDailyReport(env);
        return jsonResponse({ ok: true, report: report });
      }

      return jsonResponse({ error: 'Not found' }, 404);
    }

    // ── PROXY to GitHub Pages (existing functionality) ──
    return handleProxy(request, url);
  }
};

// ═══════════════════════════════════════
// HEARTBEAT
// ═══════════════════════════════════════

async function handleHeartbeat(request, env) {
  try {
    var data = await request.json();
    if (!data.room) {
      return jsonResponse({ error: 'Missing room' }, 400);
    }

    var roomKey = 'room:' + data.room;
    var cmdKey  = 'cmd:'  + data.room;

    // Read existing record and pending command in parallel (reads are cheap)
    var [existingRaw, pendingCmd] = await Promise.all([
      env.RIS_KV.get(roomKey),
      env.RIS_KV.get(cmdKey)
    ]);

    var prev = existingRaw ? JSON.parse(existingRaw) : null;
    var newStatus      = data.status  || 'unknown';
    var newVersion     = data.version || '';
    var newApk         = data.apkVersion || '';
    var newRefresh     = !!data.hasRefreshToken;
    var newMiddayReload = data.middayReload || null;

    // Only write if something health-critical changed, or >55 min since last write.
    // This keeps heartbeat KV writes to ~1/hour per room instead of 3/hour.
    var msSinceLast = prev ? Date.now() - new Date(prev.timestamp).getTime() : Infinity;
    var criticalChange = !prev
      || prev.status          !== newStatus
      || prev.version         !== newVersion
      || prev.apkVersion      !== newApk
      || prev.hasRefreshToken !== newRefresh
      || (newMiddayReload && newMiddayReload !== 'pending' && prev.middayReload !== newMiddayReload);
    var staleEnough = msSinceLast > 55 * 60 * 1000;

    if (criticalChange || staleEnough) {
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
      // TTL 2 hours — covers up to 55-min write interval with headroom
      await env.RIS_KV.put(roomKey, JSON.stringify(record), { expirationTtl: 7200 });
    }

    return jsonResponse({
      ok: true,
      command: pendingCmd ? JSON.parse(pendingCmd) : null
    });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// ALARM LOG — APK alarm events
// ═══════════════════════════════════════

async function handleAlarmLog(request, env) {
  try {
    var data = await request.json();
    if (!data.room || !data.event) {
      return jsonResponse({ error: 'Missing room or event' }, 400);
    }

    var entry = {
      event: data.event,
      roomname: data.roomname || '',
      apkVersion: data.apkVersion || '',
      ts: new Date().toISOString()
    };

    // Append to per-room alarm log (last 50 events)
    var key = 'alarm_log:' + data.room;
    var raw = await env.RIS_KV.get(key);
    var log = raw ? JSON.parse(raw) : [];
    log.unshift(entry);
    if (log.length > 50) log = log.slice(0, 50);
    await env.RIS_KV.put(key, JSON.stringify(log), { expirationTtl: 604800 }); // 7 days

    return jsonResponse({ ok: true });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// STATUS — all rooms
// ═══════════════════════════════════════

async function handleStatus(env) {
  try {
    // List all room keys
    var list = await env.RIS_KV.list({ prefix: 'room:' });
    var rooms = [];

    for (var i = 0; i < list.keys.length; i++) {
      var val = await env.RIS_KV.get(list.keys[i].name);
      if (val) {
        var record = JSON.parse(val);
        var lastSeen = Math.round(
          (Date.now() - new Date(record.timestamp).getTime()) / 60000
        );
        record.lastSeenMinutes = lastSeen;
        record.isOnline = lastSeen < 70; // writes every ~55min, so 70min gives safe headroom
        // Include recent alarm events for this room
        var alarmRaw = await env.RIS_KV.get('alarm_log:' + record.room);
        record.alarmLog = alarmRaw ? JSON.parse(alarmRaw).slice(0, 10) : [];
        rooms.push(record);
      }
    }

    // Sort by room name
    rooms.sort(function(a, b) {
      return (a.roomname || a.room).localeCompare(b.roomname || b.room);
    });

    return jsonResponse({ rooms: rooms, timestamp: new Date().toISOString() });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// COMMAND — set and get
// ═══════════════════════════════════════

async function handleCommandSet(request, env) {
  try {
    var data = await request.json();
    if (!data.room || !data.command) {
      return jsonResponse({ error: 'Missing room or command' }, 400);
    }

    var validCommands = ['reload', 'clear_tokens', 'clear_config', 'force_fullscreen', 're_auth', 're_auth_remote', 'fetchcal'];
    if (validCommands.indexOf(data.command) === -1) {
      return jsonResponse({ error: 'Invalid command. Valid: ' + validCommands.join(', ') }, 400);
    }

    // Special handling for re_auth_remote — fetch tokens server-side via ROPC
    if (data.command === 're_auth_remote') {
      return handleRemoteReauth(data.room, data.sentBy || 'admin', env);
    }

    var cmd = {
      command: data.command,
      sentBy: data.sentBy || 'admin',
      sentAt: new Date().toISOString()
    };

    // Store command (TTL 30 min — expires if tablet doesn't pick it up)
    await env.RIS_KV.put(
      'cmd:' + data.room,
      JSON.stringify(cmd),
      { expirationTtl: 1800 }
    );

    return jsonResponse({ ok: true, command: cmd });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// REMOTE RE-AUTH — ROPC flow server-side
// ═══════════════════════════════════════
// Authenticates with Azure AD using stored service account credentials.
// Returns tokens to the tablet via the command channel.
// Password NEVER touches the tablet — stays in Cloudflare secrets.

async function handleRemoteReauth(room, sentBy, env) {
  var svcUser = env.RIS_SVC_USER || '';
  var svcPass = env.RIS_SVC_PASSWORD || '';
  var tenantId = env.RIS_TENANT_ID || '';
  var clientId = env.RIS_CLIENT_ID || '';

  if (!svcUser || !svcPass || !tenantId || !clientId) {
    return jsonResponse({
      error: 'Remote re-auth not configured. Set RIS_SVC_USER, RIS_SVC_PASSWORD, RIS_TENANT_ID, RIS_CLIENT_ID in Worker secrets.'
    }, 400);
  }

  try {
    // ROPC token request to Azure AD
    var tokenUrl = 'https://login.microsoftonline.com/' + tenantId + '/oauth2/v2.0/token';
    var clientSecret = env.RIS_CLIENT_SECRET || '';
    var body = 'client_id=' + encodeURIComponent(clientId)
      + (clientSecret ? '&client_secret=' + encodeURIComponent(clientSecret) : '')
      + '&scope=' + encodeURIComponent('Calendars.ReadWrite Calendars.ReadWrite.Shared Mail.Send User.Read openid profile offline_access')
      + '&username=' + encodeURIComponent(svcUser)
      + '&password=' + encodeURIComponent(svcPass)
      + '&grant_type=password';

    var resp = await fetch(tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    });

    var tokenData = await resp.json();

    if (tokenData.error) {
      return jsonResponse({
        error: 'Azure AD rejected ROPC: ' + (tokenData.error_description || tokenData.error)
      }, 401);
    }

    // Build inject_tokens command with the fresh tokens
    var cmd = {
      command: 'inject_tokens',
      sentBy: sentBy,
      sentAt: new Date().toISOString(),
      tokens: {
        access_token: tokenData.access_token,
        refresh_token: tokenData.refresh_token || '',
        id_token: tokenData.id_token || '',
        expires_in: tokenData.expires_in || 3600
      }
    };

    // Store command for the tablet to pick up
    await env.RIS_KV.put(
      'cmd:' + room,
      JSON.stringify(cmd),
      { expirationTtl: 1800 }
    );

    return jsonResponse({
      ok: true,
      message: 'Tokens fetched via ROPC and queued for ' + room,
      command: { command: 'inject_tokens', sentBy: sentBy, sentAt: cmd.sentAt }
    });
  } catch (e) {
    return jsonResponse({ error: 'ROPC failed: ' + e.message }, 500);
  }
}

async function handleCommandGet(url, env) {
  try {
    var room = url.searchParams.get('room');
    if (!room) {
      return jsonResponse({ error: 'Missing room param' }, 400);
    }

    var cmdKey = 'cmd:' + room;
    var cmd = await env.RIS_KV.get(cmdKey);

    if (cmd) {
      // Delete after reading (one-time delivery)
      await env.RIS_KV.delete(cmdKey);
      return jsonResponse({ command: JSON.parse(cmd) });
    }

    return jsonResponse({ command: null });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// INCIDENTS — report, resolve, list
// ═══════════════════════════════════════

async function handleIncidentReport(request, env) {
  try {
    var data = await request.json();
    if (!data.room || !data.type) {
      return jsonResponse({ error: 'Missing room or type' }, 400);
    }

    var now = new Date();
    var incidentId = now.getTime().toString(36);

    if (data.action === 'resolve') {
      // Resolve an existing incident
      var existingKey = data.incidentKey;
      if (existingKey) {
        var existing = await env.RIS_KV.get(existingKey);
        if (existing) {
          var record = JSON.parse(existing);
          record.resolvedAt = now.toISOString();
          record.resolvedBy = data.resolvedBy || 'auto';
          record.resolution = data.resolution || 'auto-resolved';
          record.durationMinutes = Math.round(
            (now.getTime() - new Date(record.reportedAt).getTime()) / 60000
          );
          await env.RIS_KV.put(existingKey, JSON.stringify(record), { expirationTtl: 2592000 }); // 30 days
          return jsonResponse({ ok: true, incident: record });
        }
      }
      return jsonResponse({ error: 'Incident not found' }, 404);
    }

    // Create new incident
    var incident = {
      id: incidentId,
      room: data.room,
      roomname: data.roomname || '',
      type: data.type,
      detail: data.detail || '',
      reportedAt: now.toISOString(),
      reportedBy: data.reportedBy || 'tablet',
      resolvedAt: null,
      resolvedBy: null,
      resolution: null,
      durationMinutes: null,
      autoResolvable: data.autoResolvable || false
    };

    var incidentKey = 'incident:' + now.toISOString().slice(0, 10) + ':' + incidentId;

    // Store incident (TTL 30 days)
    await env.RIS_KV.put(incidentKey, JSON.stringify(incident), { expirationTtl: 2592000 });

    // Update index (last 200 incidents)
    var indexRaw = await env.RIS_KV.get('incidents_index');
    var index = indexRaw ? JSON.parse(indexRaw) : [];
    index.unshift(incidentKey);
    if (index.length > 200) index = index.slice(0, 200);
    await env.RIS_KV.put('incidents_index', JSON.stringify(index));

    return jsonResponse({ ok: true, incident: incident, key: incidentKey });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

async function handleNoshowStats(url, env) {
  try {
    var days = parseInt(url.searchParams.get('days') || '30');
    var room = url.searchParams.get('room') || null;
    var cutoff = new Date(Date.now() - days * 86400000).toISOString();

    var indexRaw = await env.RIS_KV.get('incidents_index');
    var index = indexRaw ? JSON.parse(indexRaw) : [];

    var noshows = [];
    for (var i = 0; i < index.length; i++) {
      var val = await env.RIS_KV.get(index[i]);
      if (!val) continue;
      var record = JSON.parse(val);
      if (record.type !== 'noshow') continue;
      if (record.reportedAt < cutoff) break; // index is newest-first
      if (room && record.room !== room) continue;
      // Parse detail JSON for organizer info
      var detail = {};
      try { detail = JSON.parse(record.detail); } catch(e) {}
      noshows.push({
        room: record.room,
        roomname: record.roomname,
        reportedAt: record.reportedAt,
        subject: detail.subject || '',
        organizer: detail.organizer || '',
        organizerEmail: detail.organizerEmail || '',
        meetingStart: detail.meetingStart || '',
        meetingEnd: detail.meetingEnd || ''
      });
    }

    // Count by organizer email
    var byOrganizer = {};
    noshows.forEach(function(n) {
      var key = n.organizerEmail || n.organizer || 'unknown';
      if (!byOrganizer[key]) byOrganizer[key] = { organizer: n.organizer, organizerEmail: n.organizerEmail, count: 0 };
      byOrganizer[key].count++;
    });
    var organizerRank = Object.keys(byOrganizer).map(function(k) { return byOrganizer[k]; });
    organizerRank.sort(function(a, b) { return b.count - a.count; });

    // Count by room
    var byRoom = {};
    noshows.forEach(function(n) {
      var key = n.room;
      if (!byRoom[key]) byRoom[key] = { room: n.room, roomname: n.roomname, count: 0 };
      byRoom[key].count++;
    });
    var roomRank = Object.keys(byRoom).map(function(k) { return byRoom[k]; });
    roomRank.sort(function(a, b) { return b.count - a.count; });

    return jsonResponse({
      total: noshows.length,
      days: days,
      byOrganizer: organizerRank,
      byRoom: roomRank,
      recent: noshows.slice(0, 20),
      timestamp: new Date().toISOString()
    });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

async function handleIncidentsList(url, env) {
  try {
    var limit = parseInt(url.searchParams.get('limit') || '50');
    var room = url.searchParams.get('room') || null;

    var indexRaw = await env.RIS_KV.get('incidents_index');
    var index = indexRaw ? JSON.parse(indexRaw) : [];

    var incidents = [];
    var count = 0;

    for (var i = 0; i < index.length && count < limit; i++) {
      var val = await env.RIS_KV.get(index[i]);
      if (val) {
        var record = JSON.parse(val);
        if (!room || record.room === room) {
          incidents.push(record);
          count++;
        }
      }
    }

    // Calculate stats
    var totalIncidents = incidents.length;
    var resolved = incidents.filter(function(inc) { return inc.resolvedAt; });
    var autoResolved = resolved.filter(function(inc) { return inc.resolvedBy === 'auto'; });
    var avgDuration = 0;
    if (resolved.length > 0) {
      var totalMins = 0;
      for (var j = 0; j < resolved.length; j++) {
        totalMins += (resolved[j].durationMinutes || 0);
      }
      avgDuration = Math.round(totalMins / resolved.length);
    }

    return jsonResponse({
      incidents: incidents,
      stats: {
        total: totalIncidents,
        resolved: resolved.length,
        open: totalIncidents - resolved.length,
        autoResolved: autoResolved.length,
        avgResolutionMinutes: avgDuration
      },
      timestamp: new Date().toISOString()
    });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// ═══════════════════════════════════════
// PROXY — existing GitHub Pages proxy
// ═══════════════════════════════════════

async function handleProxy(request, url) {
  var path = url.pathname === '/' ? '/' : url.pathname;
  var target = 'https://pom7220.github.io/Display-Room' + path + url.search;

  var response = await fetch(target, {
    headers: {
      'User-Agent': request.headers.get('User-Agent') || 'RIS-Proxy',
      'Accept': request.headers.get('Accept') || '*/*',
      'Accept-Encoding': request.headers.get('Accept-Encoding') || '',
      'Cache-Control': 'no-cache',
    },
    cf: { cacheEverything: false },
  });

  var newHeaders = new Headers(response.headers);
  newHeaders.set('Access-Control-Allow-Origin', '*');
  newHeaders.set('Cache-Control', 'no-store');
  newHeaders.delete('Content-Security-Policy');

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: newHeaders,
  });
}

// ═══════════════════════════════════════
// DAILY REPORTS
// ═══════════════════════════════════════

async function generateDailyReport(env) {
  var today = new Date().toISOString().slice(0, 10);

  // Collect current room statuses
  var list = await env.RIS_KV.list({ prefix: 'room:' });
  var rooms = [];
  for (var i = 0; i < list.keys.length; i++) {
    var val = await env.RIS_KV.get(list.keys[i].name);
    if (val) rooms.push(JSON.parse(val));
  }

  // Collect today's incidents
  var indexRaw = await env.RIS_KV.get('incidents_index');
  var index = indexRaw ? JSON.parse(indexRaw) : [];
  var todayIncidents = [];
  for (var j = 0; j < Math.min(index.length, 100); j++) {
    var inc = await env.RIS_KV.get(index[j]);
    if (inc) {
      var record = JSON.parse(inc);
      if (record.reportedAt && record.reportedAt.startsWith(today)) {
        todayIncidents.push(record);
      }
    }
  }

  var resolved = todayIncidents.filter(function(i) { return i.resolvedAt; });
  var autoResolved = resolved.filter(function(i) { return i.resolvedBy === 'auto'; });
  var open = todayIncidents.filter(function(i) { return !i.resolvedAt; });

  var totalDuration = 0;
  resolved.forEach(function(i) { totalDuration += (i.durationMinutes || 0); });

  var report = {
    date: today,
    generatedAt: new Date().toISOString(),
    tabletsDeployed: rooms.length,
    tabletsOnline: rooms.filter(function(r) {
      var age = (Date.now() - new Date(r.timestamp).getTime()) / 60000;
      return age < 30;
    }).length,
    rooms: rooms.map(function(r) {
      return {
        room: r.room,
        roomname: r.roomname,
        status: r.status,
        version: r.version,
        uptime: r.uptime,
        hasRefreshToken: r.hasRefreshToken,
        meetingCount: r.meetingCount,
        lastSeen: r.timestamp
      };
    }),
    incidents: {
      total: todayIncidents.length,
      resolved: resolved.length,
      autoResolved: autoResolved.length,
      open: open.length,
      avgResolutionMinutes: resolved.length > 0 ? Math.round(totalDuration / resolved.length) : 0,
      details: todayIncidents
    },
    tokenRefreshes: {
      note: 'Count from incident reports — actual refreshes happen silently'
    }
  };

  // Store report (TTL 90 days)
  await env.RIS_KV.put('report:' + today, JSON.stringify(report), { expirationTtl: 7776000 });

  // Update report index (last 90 days)
  var riRaw = await env.RIS_KV.get('reports_index');
  var ri = riRaw ? JSON.parse(riRaw) : [];
  if (ri.indexOf('report:' + today) === -1) {
    ri.unshift('report:' + today);
    if (ri.length > 90) ri = ri.slice(0, 90);
    await env.RIS_KV.put('reports_index', JSON.stringify(ri));
  }

  return report;
}

async function handleReportsList(url, env) {
  try {
    var limit = parseInt(url.searchParams.get('limit') || '7');
    var riRaw = await env.RIS_KV.get('reports_index');
    var ri = riRaw ? JSON.parse(riRaw) : [];

    var reports = [];
    for (var i = 0; i < Math.min(ri.length, limit); i++) {
      var val = await env.RIS_KV.get(ri[i]);
      if (val) reports.push(JSON.parse(val));
    }

    return jsonResponse({ reports: reports, timestamp: new Date().toISOString() });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// WEEKLY NO-SHOW REPORT — email summary
// ═══════════════════════════════════════

async function generateWeeklyNoshowReport(env) {
  try {
    var nowUtcMs = Date.now();
    var bkkOffsetMs = 7 * 60 * 60 * 1000;
    var nowBkk = new Date(nowUtcMs + bkkOffsetMs);

    var dowBkk = nowBkk.getUTCDay();
    var daysSinceMon = (dowBkk === 0) ? 6 : dowBkk - 1;

    var monBkkMs = nowUtcMs - (daysSinceMon * 86400000)
      - (nowBkk.getUTCHours() * 3600000)
      - (nowBkk.getUTCMinutes() * 60000)
      - (nowBkk.getUTCSeconds() * 1000)
      - nowBkk.getUTCMilliseconds();
    var monCutoff = new Date(monBkkMs).toISOString();

    var months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    function fmtDate(d) {
      return d.getUTCDate() + ' ' + months[d.getUTCMonth()] + ' ' + d.getUTCFullYear();
    }
    var monLabel = fmtDate(new Date(monBkkMs));
    var friLabel = fmtDate(nowBkk);

    var indexRaw = await env.RIS_KV.get('incidents_index');
    var index = indexRaw ? JSON.parse(indexRaw) : [];

    var noshows = [];
    for (var i = 0; i < index.length; i++) {
      var val = await env.RIS_KV.get(index[i]);
      if (!val) continue;
      var record = JSON.parse(val);
      if (record.reportedAt < monCutoff) break;
      if (record.type !== 'noshow') continue;
      var detail = {};
      try { detail = JSON.parse(record.detail); } catch(e) {}
      if (detail.organizer === 'Instant Booking') continue;
      noshows.push({
        room: record.room,
        roomname: record.roomname || record.room,
        organizer: detail.organizer || '',
        organizerEmail: detail.organizerEmail || '',
        subject: detail.subject || '(no title)',
        meetingStart: detail.meetingStart || '',
        meetingEnd: detail.meetingEnd || '',
        reportedAt: record.reportedAt || '',
        isLateCheckin: !!(record.resolvedAt && record.resolution === 'late_checkin')
      });
    }

    var orgMap = {};
    noshows.forEach(function(n) {
      var key = n.organizerEmail || n.organizer || 'unknown';
      if (!orgMap[key]) orgMap[key] = { name: n.organizer, email: n.organizerEmail, count: 0 };
      orgMap[key].count++;
    });
    var byOrganizer = Object.keys(orgMap).map(function(k) { return orgMap[k]; });
    byOrganizer.sort(function(a, b) { return b.count - a.count; });

    var roomMap = {};
    noshows.forEach(function(n) {
      var key = n.room;
      if (!roomMap[key]) roomMap[key] = { name: n.roomname, count: 0 };
      roomMap[key].count++;
    });
    var byRoom = Object.keys(roomMap).map(function(k) { return roomMap[k]; });
    byRoom.sort(function(a, b) { return b.count - a.count; });

    var lateCheckins = noshows.filter(function(n) { return n.isLateCheckin; }).length;
    await sendWeeklyEmail(env, {
      total: noshows.length,
      lateCheckins: lateCheckins,
      monLabel: monLabel,
      friLabel: friLabel,
      byOrganizer: byOrganizer.slice(0, 3),
      byRoom: byRoom.slice(0, 3),
      noshows: noshows
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

  var bodyRows = '';
  if (!hasIncidents) {
    bodyRows = '<p style="color:#2e7d32">No meetings were released as no-show this week.</p>';
  } else {
    var orgRows = data.byOrganizer.map(function(o, i) {
      return '<tr><td style="padding:4px 12px 4px 0">' + (i+1) + '. ' + (o.name || o.email)
        + '</td><td style="padding:4px 0;color:#555">' + (o.email ? '(' + o.email + ')' : '')
        + '</td><td style="padding:4px 0 4px 16px;text-align:right;font-weight:bold">'
        + o.count + (o.count === 1 ? ' time' : ' times') + '</td></tr>';
    }).join('');

    // Build daily detail — group by BKK date
    var months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    function pad2(n) { return n < 10 ? '0' + n : '' + n; }
    function fmtBkkTime(iso) {
      if (!iso) return '';
      var d = new Date(new Date(iso).getTime() + 7 * 3600000);
      return pad2(d.getUTCHours()) + ':' + pad2(d.getUTCMinutes());
    }
    function bkkDateKey(iso) {
      if (!iso) return '';
      var d = new Date(new Date(iso).getTime() + 7 * 3600000);
      return d.getUTCFullYear() + '-' + pad2(d.getUTCMonth() + 1) + '-' + pad2(d.getUTCDate());
    }
    function bkkDateLabel(iso) {
      if (!iso) return '';
      var d = new Date(new Date(iso).getTime() + 7 * 3600000);
      return d.getUTCDate() + ' ' + months[d.getUTCMonth()] + ' ' + d.getUTCFullYear();
    }

    // Build daily detail — group by BKK date
    var byDate = {};
    (data.noshows || []).forEach(function(n) {
      var ts = n.meetingStart || n.reportedAt;
      var key = bkkDateKey(ts) || 'unknown';
      var label = bkkDateLabel(ts) || 'Unknown date';
      if (!byDate[key]) byDate[key] = { label: label, items: [], noshow: 0, late: 0 };
      byDate[key].items.push(n);
      if (n.isLateCheckin) byDate[key].late++; else byDate[key].noshow++;
    });
    var sortedDateKeys = Object.keys(byDate).sort();
    // Single table for all days — Outlook ignores table-layout:fixed/colgroup,
    // so width attributes on every th/td are the only reliable alignment method.
    function trunc(str, max) { return str && str.length > max ? str.slice(0, max - 1) + '…' : (str || ''); }
    var W = { time: '105', meet: '185', org: '150', room: '85', status: '75' };
    var allRows = '<tr>'
      + '<th width="' + W.time + '" style="width:' + W.time + 'px;padding:4px 8px 4px 0;text-align:left;font-size:12px;color:#888;border-bottom:2px solid #ddd">Time</th>'
      + '<th width="' + W.meet + '" style="width:' + W.meet + 'px;padding:4px 8px 4px 0;text-align:left;font-size:12px;color:#888;border-bottom:2px solid #ddd">Meeting</th>'
      + '<th width="' + W.org  + '" style="width:' + W.org  + 'px;padding:4px 8px 4px 0;text-align:left;font-size:12px;color:#888;border-bottom:2px solid #ddd">Organizer</th>'
      + '<th width="' + W.room + '" style="width:' + W.room + 'px;padding:4px 8px 4px 0;text-align:left;font-size:12px;color:#888;border-bottom:2px solid #ddd">Room</th>'
      + '<th width="' + W.status + '" style="width:' + W.status + 'px;padding:4px 0;text-align:left;font-size:12px;color:#888;border-bottom:2px solid #ddd">Status</th>'
      + '</tr>';
    sortedDateKeys.forEach(function(dk) {
      var group = byDate[dk];
      group.items.sort(function(a, b) {
        return (a.meetingStart || a.reportedAt) < (b.meetingStart || b.reportedAt) ? -1 : 1;
      });
      var dayTotals = group.noshow + ' no-show' + (group.noshow !== 1 ? 's' : '')
        + (group.late > 0 ? ', ' + group.late + ' late check-in' + (group.late !== 1 ? 's' : '') : '');
      // Date separator row spanning all columns
      allRows += '<tr><td colspan="5" style="padding:18px 0 4px;font-size:14px;font-weight:bold;color:#1a1a2e">'
        + group.label
        + ' <span style="font-weight:normal;color:#888;font-size:12px">— ' + dayTotals + '</span>'
        + '</td></tr>';
      group.items.forEach(function(n) {
        var timeRange = n.meetingStart
          ? fmtBkkTime(n.meetingStart) + ' – ' + fmtBkkTime(n.meetingEnd)
          : fmtBkkTime(n.reportedAt);
        var status = n.isLateCheckin
          ? '<span style="color:#e65100;font-size:11px">late check-in</span>'
          : '<span style="color:#c62828;font-size:11px">no-show</span>';
        allRows += '<tr style="border-bottom:1px solid #f0f0f0">'
          + '<td width="' + W.time + '" style="width:' + W.time + 'px;padding:5px 8px 5px 0;white-space:nowrap;color:#555;font-size:13px">' + timeRange + '</td>'
          + '<td width="' + W.meet + '" style="width:' + W.meet + 'px;padding:5px 8px 5px 0;font-size:13px;overflow:hidden">' + trunc(n.subject, 28) + '</td>'
          + '<td width="' + W.org  + '" style="width:' + W.org  + 'px;padding:5px 8px 5px 0;font-size:13px;color:#333;overflow:hidden">' + trunc(n.organizer || n.organizerEmail || '—', 22) + '</td>'
          + '<td width="' + W.room + '" style="width:' + W.room + 'px;padding:5px 8px 5px 0;font-size:13px;color:#555">' + n.roomname + '</td>'
          + '<td width="' + W.status + '" style="width:' + W.status + 'px;padding:5px 0;font-size:11px">' + status + '</td>'
          + '</tr>';
      });
    });
    var dailyHtml = '<table style="border-collapse:collapse;width:100%;font-size:13px">' + allRows + '</table>';

    var completeNoshow = data.total - data.lateCheckins;
    bodyRows = '<p><strong>Total no-shows this week: ' + data.total + '</strong>'
      + ' &nbsp;<span style="color:#c62828;font-size:13px">(' + completeNoshow + ' complete no-show'
      + (completeNoshow !== 1 ? 's' : '') + ', '
      + data.lateCheckins + ' late check-in'
      + (data.lateCheckins !== 1 ? 's' : '') + ')</span><br>'
      + '<span style="color:#666;font-size:13px">Period: ' + weekLabel + '</span></p>'
      + '<h3 style="margin-bottom:6px">Top Organizers</h3>'
      + '<table style="border-collapse:collapse;font-size:14px">' + orgRows + '</table>'
      + '<h3 style="margin-top:28px;margin-bottom:4px">Daily Detail</h3>'
      + dailyHtml;
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

// ═══════════════════════════════════════
// KNOWN ROOM REGISTRY
// ═══════════════════════════════════════
// All 6 deployed tablets. Used to surface offline rooms that have no KV heartbeat.
var KNOWN_ROOMS = [
  { email: 'risaffogato@central.co.th',    name: 'Affogato' },
  { email: 'rismacchiato@central.co.th',   name: 'Macchiato' },
  { email: 'rismocha@central.co.th',       name: 'Mocha' },
  { email: 'risviennese@central.co.th',    name: 'Viennese' },
  { email: 'risdecaffeinato@central.co.th', name: 'Decaffinato' },
  { email: 'rislatte@central.co.th',       name: 'Latte' },
];

// ═══════════════════════════════════════
// MISSED-WAKE DETECTION (runs at 08:00 BKK = 01:00 UTC)
// ═══════════════════════════════════════
// Tablets should wake at 07:30 BKK via APK alarm. At 08:00 BKK (30 min later),
// any tablet with no heartbeat in the last 90 min failed to wake — file an incident.
async function checkMissedWakes(env) {
  // Skip on weekends — LG tablets stay in standby (wake_weekend = skip by design)
  var bkkDay = new Date(Date.now() + 7 * 3600000).getUTCDay(); // 0=Sun, 6=Sat
  if (bkkDay === 0 || bkkDay === 6) return;

  var now = Date.now();
  var cutoffMs = 90 * 60 * 1000;
  for (var i = 0; i < KNOWN_ROOMS.length; i++) {
    var kr = KNOWN_ROOMS[i];
    var raw = await env.RIS_KV.get('room:' + kr.email, 'json');
    var lastSeen = (raw && raw.timestamp) ? new Date(raw.timestamp).getTime() : 0;
    if (!lastSeen || now - lastSeen > cutoffMs) {
      var minsAgo = lastSeen ? Math.round((now - lastSeen) / 60000) : null;
      var detail = minsAgo ? kr.name + ' did not wake at 07:30 BKK — last heartbeat ' + minsAgo + ' min ago' : kr.name + ' has never sent a heartbeat';
      // Create incident directly in KV (same schema as POST /api/incident)
      var incId = 'inc_' + Date.now() + '_' + Math.floor(Math.random() * 9999);
      var incident = {
        id: incId,
        room: kr.email,
        roomname: kr.name,
        type: 'tablet_missed_wake',
        detail: detail,
        reportedAt: new Date(now).toISOString(),
        reportedBy: 'worker_cron',
        resolvedAt: null,
        resolvedBy: null,
        resolution: null,
        durationMinutes: null,
        autoResolvable: false
      };
      var incKey = 'incident:' + new Date(now).toISOString().slice(0, 10) + ':' + incId;
      await env.RIS_KV.put(incKey, JSON.stringify(incident), { expirationTtl: 2592000 });
      var idxRaw = await env.RIS_KV.get('incidents_index');
      var idx = idxRaw ? JSON.parse(idxRaw) : [];
      idx.unshift(incKey);
      if (idx.length > 200) idx = idx.slice(0, 200);
      await env.RIS_KV.put('incidents_index', JSON.stringify(idx));
    }
  }
}

// ═══════════════════════════════════════
// DAILY HEALTH DIGEST EMAIL
// ═══════════════════════════════════════

async function sendDailyHealthDigest(env, report) {
  try {
    var token = await getServiceToken(env);
    var now = Date.now();
    var bkkNow = new Date(now + 7 * 3600000);
    var todayBkk = bkkNow.toISOString().slice(0, 10);

    // ── Collect full room data (heartbeat + alarm log from separate KV key) ──
    var list = await env.RIS_KV.list({ prefix: 'room:' });
    var roomData = [];
    for (var i = 0; i < list.keys.length; i++) {
      var val = await env.RIS_KV.get(list.keys[i].name);
      if (!val) continue;
      var rec = JSON.parse(val);
      // Alarm log is stored separately — merge it in (same as handleStatus does)
      var alarmRaw = await env.RIS_KV.get('alarm_log:' + rec.room);
      rec.alarmLog = alarmRaw ? JSON.parse(alarmRaw) : [];
      roomData.push(rec);
    }
    roomData.sort(function(a, b) { return (a.roomname || '').localeCompare(b.roomname || ''); });

    // Deduplicate by roomname — guards against future email-typo mismatches producing ghost rows.
    var _byName = {};
    roomData.forEach(function(r) {
      var nm = r.roomname || r.room || '';
      if (!_byName[nm] || (r.timestamp && (!_byName[nm].timestamp || r.timestamp > _byName[nm].timestamp))) {
        _byName[nm] = r;
      }
    });
    roomData = Object.keys(_byName).map(function(k) { return _byName[k]; });

    // ── Inject offline stubs for any known room missing from KV (expired heartbeat) ──
    var seenRooms = {};
    roomData.forEach(function(r) { seenRooms[r.room] = true; });
    KNOWN_ROOMS.forEach(function(kr) {
      if (!seenRooms[kr.email]) {
        roomData.push({ room: kr.email, roomname: kr.name, timestamp: null, version: null, apkVersion: null, alarmLog: [], _noHeartbeat: true });
      }
    });
    roomData.sort(function(a, b) { return (a.roomname || '').localeCompare(b.roomname || ''); });

    // ── Collect today's + yesterday's incidents ──
    var indexRaw = await env.RIS_KV.get('incidents_index');
    var incIndex = indexRaw ? JSON.parse(indexRaw) : [];
    var cutoff24h = new Date(now - 24 * 3600000).toISOString();
    var expireCutoff48h = new Date(now - 48 * 3600000).toISOString();
    var recentIncidents = [];
    for (var j = 0; j < Math.min(incIndex.length, 200); j++) {
      var inc = await env.RIS_KV.get(incIndex[j]);
      if (!inc) continue;
      var rec = JSON.parse(inc);
      if (rec.reportedAt >= cutoff24h) {
        recentIncidents.push(rec);
      } else if (!rec.resolvedAt && rec.type === 'noshow' && rec.reportedAt < expireCutoff48h) {
        rec.resolvedAt = new Date(now).toISOString();
        rec.resolvedBy = 'system';
        rec.resolution = 'auto-expired';
        rec.durationMinutes = Math.round((now - new Date(rec.reportedAt).getTime()) / 60000);
        await env.RIS_KV.put(incIndex[j], JSON.stringify(rec), { expirationTtl: 2592000 });
      }
    }

    // ── Alarm chain analysis ──
    // Expected today (UTC): restart ~23:00 yesterday, wake ~00:30 today, standby ~13:30 today
    var ALARM_WINDOW_MS = 25 * 60 * 1000; // ±25 min tolerance
    var todayUTC = new Date(now).toISOString().slice(0, 10);
    var yestUTC  = new Date(now - 86400000).toISOString().slice(0, 10);

    function alarmExpected(isoTarget) {
      return now > new Date(isoTarget).getTime() + ALARM_WINDOW_MS;
    }

    var restartTarget = yestUTC + 'T23:00:00Z'; // 06:00 BKK today
    var wakeTarget    = todayUTC + 'T00:30:00Z'; // 07:30 BKK today
    var standbyTarget = todayUTC + 'T13:30:00Z'; // 20:30 BKK today

    function findAlarm(log, eventType, targetIso) {
      var targetMs = new Date(targetIso).getTime();
      return (log || []).some(function(e) {
        return e.event === eventType && Math.abs(new Date(e.ts).getTime() - targetMs) < ALARM_WINDOW_MS;
      });
    }

    // Pre-index unexpected reboots per room for alarm-gap correlation and crash-boot grouping
    var twoDaysAgoUTC = new Date(now - 2 * 86400000).toISOString().slice(0, 10);
    var middayTarget  = todayUTC + 'T05:30:00Z'; // 12:30 BKK today
    var expectedBootWindowsUTC = [
      restartTarget, wakeTarget, standbyTarget, middayTarget,
      twoDaysAgoUTC + 'T23:00:00Z',
      yestUTC + 'T00:30:00Z',
      yestUTC + 'T13:30:00Z',
      yestUTC + 'T05:30:00Z'
    ];
    var _rebootsByRoom = {};
    recentIncidents.forEach(function(inc) {
      if (inc.type !== 'unexpected_reboot') return;
      var key = inc.room || '';
      if (!_rebootsByRoom[key]) _rebootsByRoom[key] = [];
      _rebootsByRoom[key].push(inc.reportedAt);
    });

    // ── Per-room analysis ──
    var allVersions = [];
    var allApkVersions = [];
    var roomRows = '';
    var anomalies = [];
    var crashBoots = [];

    roomData.forEach(function(r) {
      var ageMins = r.timestamp ? (now - new Date(r.timestamp).getTime()) / 60000 : Infinity;
      var online = ageMins < 70;
      var statusIcon = online ? '✅' : '❌';
      var flags = [];

      // Version check
      if (r.version) allVersions.push(r.version);
      if (r.apkVersion) allApkVersions.push(r.apkVersion);

      // Alarm chain gaps
      var alarmLog = r.alarmLog || [];
      var restartOk = !alarmExpected(restartTarget) || findAlarm(alarmLog, 'restart', restartTarget);
      var wakeOk    = !alarmExpected(wakeTarget)    || findAlarm(alarmLog, 'wake', wakeTarget) || findAlarm(alarmLog, 'wake_weekend', wakeTarget);
      var standbyOk = !alarmExpected(standbyTarget) || findAlarm(alarmLog, 'standby', standbyTarget);
      // Alarm-gap check: if a reboot happened after the alarm target, it explains the gap (ℹ️ not ⚠️)
      var roomReboots = _rebootsByRoom[r.room] || [];
      function rebootAfter(targetIso) {
        var tMs = new Date(targetIso).getTime();
        var hits = roomReboots.filter(function(ts) { return new Date(ts).getTime() > tMs; });
        if (!hits.length) return null;
        hits.sort();
        return new Date(new Date(hits[0]).getTime() + 7 * 3600000).toISOString().slice(11, 16);
      }
      if (!restartOk) {
        var rb = rebootAfter(restartTarget);
        flags.push(rb ? 'ℹ️ 06:00 restart (reboot at ' + rb + ' BKK)' : '⚠️ missing 06:00 restart');
      }
      if (!wakeOk) {
        var rb2 = rebootAfter(wakeTarget);
        flags.push(rb2 ? 'ℹ️ 07:30 wake (reboot at ' + rb2 + ' BKK)' : '⚠️ missing 07:30 wake');
      }
      if (!standbyOk) {
        var rb3 = rebootAfter(standbyTarget);
        flags.push(rb3 ? 'ℹ️ 20:30 standby (reboot at ' + rb3 + ' BKK)' : '⚠️ missing 20:30 standby');
      }

      // Slow poll warnings (>2s response = Chrome memory pressure)
      if (r.pollStats && r.pollStats.slowCount > 0) {
        flags.push('⚠️ ' + r.pollStats.slowCount + ' slow poll' + (r.pollStats.slowCount > 1 ? 's' : '') + ' (last ' + r.pollStats.lastSlowMs + 'ms)');
      }

      // Offline
      if (!online) {
        var offlineDesc = r.timestamp ? Math.round(ageMins) + 'min' : 'no heartbeat ever';
        var lastSeenDesc = r.timestamp ? ' (last seen ' + new Date(r.timestamp).toISOString() + ')' : ' (never seen — heartbeat expired or never sent)';
        flags.push('❌ offline ' + offlineDesc);
        anomalies.push(r.roomname + ': offline ' + offlineDesc + lastSeenDesc);
      }

      // Only ⚠️/❌ flags go to anomaly count; ℹ️ (reboot-explained gaps) are informational only
      var warnFlags = flags.filter(function(f) { return f.indexOf('ℹ️') !== 0; });
      if (warnFlags.length) {
        anomalies.push(r.roomname + ': ' + warnFlags.join(', '));
      }

      var middayIcon = '';
      if (r.middayReload === 'done')    middayIcon = '♻️ reloaded';
      else if (r.middayReload === 'skipped') middayIcon = '⏭️ skipped';
      else                              middayIcon = '— ';

      var flagStr = flags.length ? ' — ' + flags.join(', ') : '';
      roomRows += '<tr>'
        + '<td style="padding:4px 10px 4px 0;font-weight:600">' + statusIcon + ' ' + r.roomname + '</td>'
        + '<td style="padding:4px 10px;color:#555;font-size:12px">' + ((r.version && r.version.charAt(0) === 'v') ? r.version : '? (standby)') + ' / APK ' + (r.apkVersion || (r.version && r.version.charAt(0) !== 'v' ? r.version : '?')) + '</td>'
        + '<td style="padding:4px 10px;color:#555;font-size:12px">' + (r.timestamp ? Math.round(ageMins) + 'm ago' : 'no heartbeat') + '</td>'
        + '<td style="padding:4px 10px;color:#555;font-size:12px">' + middayIcon + '</td>'
        + '<td style="padding:4px 0;font-size:12px;color:' + (warnFlags.length ? '#cc3333' : (flags.length ? '#888' : '#339933')) + '">'
          + (flags.length ? flags.join(' ') : '✓ OK') + '</td>'
        + '</tr>';
    });

    // ── Version consistency ──
    var uniqueVersions = allVersions.filter(function(v, i, a) { return a.indexOf(v) === i; });
    var uniqueApk = allApkVersions.filter(function(v, i, a) { return a.indexOf(v) === i; });
    if (uniqueVersions.length > 1) anomalies.push('Version mismatch across tablets: ' + uniqueVersions.join(', '));
    if (uniqueApk.length > 1) anomalies.push('APK version mismatch: ' + uniqueApk.join(', '));

    // ── Unexpected reboot incidents — group events within 5 min into clusters ──
    var _allCrashBoots = [];
    recentIncidents.forEach(function(inc) {
      if (inc.type !== 'unexpected_reboot') return;
      var incMs = new Date(inc.reportedAt).getTime();
      _allCrashBoots.push({
        room: inc.roomname || inc.room || '?',
        email: inc.room || '',
        ts: inc.reportedAt,
        tsMs: incMs,
        desc: inc.detail || 'boot detected'
      });
    });
    _allCrashBoots.sort(function(a, b) { return a.tsMs - b.tsMs; });

    // Cluster: events within 5 min of each other → one summary row
    var _clusters = [];
    _allCrashBoots.forEach(function(boot) {
      var last = _clusters[_clusters.length - 1];
      if (last && boot.tsMs - last.endMs < 5 * 60000) {
        last.boots.push(boot);
        last.endMs = boot.tsMs;
      } else {
        _clusters.push({ boots: [boot], endMs: boot.tsMs });
      }
    });

    _clusters.forEach(function(cl) {
      var rooms = cl.boots.map(function(b) { return b.room; });
      var uniqueRooms = rooms.filter(function(v, i, a) { return a.indexOf(v) === i; });
      var firstTs = cl.boots[0].ts;
      var bkkTime = new Date(new Date(firstTs).getTime() + 7 * 3600000).toISOString().slice(11, 16);
      if (uniqueRooms.length === 1) {
        anomalies.push('Unexpected reboot: ' + uniqueRooms[0] + ' at '
          + new Date(firstTs).toISOString().replace('T', ' ').slice(0, 16) + ' UTC');
        crashBoots.push(cl.boots[0]);
      } else {
        anomalies.push('Mass reboot at ' + bkkTime + ' BKK — ' + uniqueRooms.length
          + ' tablets: ' + uniqueRooms.join(', '));
        crashBoots.push({
          room: uniqueRooms.join(', '),
          email: '',
          ts: firstTs,
          desc: 'Mass reboot — ' + cl.boots.length + ' event(s) across ' + uniqueRooms.length + ' tablets'
        });
      }
    });

    // ── Open incidents — only non-auto-resolvable ones ──
    var openIncidents = recentIncidents.filter(function(i) {
      return !i.resolvedAt && !i.autoResolvable && i.type !== 'noshow';
    });
    openIncidents.forEach(function(inc) {
      var detail = '';
      try { detail = JSON.parse(inc.detail || '{}').subject || inc.detail || ''; } catch(e) { detail = inc.detail || ''; }
      anomalies.push('Open: [' + inc.type + '] ' + (inc.roomname || inc.room || '') + (detail ? ' — ' + detail.slice(0, 60) : ''));
    });

    // ── KV write estimate (exclude offline stubs — they generate no KV writes) ──
    var activeRoomCount = roomData.filter(function(r) { return !r._noHeartbeat; }).length;
    var kvWritesPerDay = activeRoomCount * 24 + activeRoomCount * 3 + 5; // heartbeat ~1/hr + 3 alarms + overhead

    // ── Summary line ──
    var onlineCount = roomData.filter(function(r) {
      return r.timestamp && (now - new Date(r.timestamp).getTime()) / 60000 < 70;
    }).length;
    anomalies = anomalies.filter(function(a, i, arr) { return arr.indexOf(a) === i; });
    var allOk = anomalies.length === 0;
    var subjectEmoji = allOk ? '✅' : '⚠️';
    var subject = '[RIS] Daily Health Digest ' + subjectEmoji + ' — ' + todayBkk
      + (allOk ? ' — All Good' : ' — ' + anomalies.length + ' issue(s)');

    // ── Anomaly section ──
    var anomalyHtml = '';
    if (anomalies.length) {
      anomalyHtml = '<h3 style="color:#cc3333;margin-top:24px">⚠️ Issues (' + anomalies.length + ')</h3>'
        + '<ul style="font-size:13px;color:#333;line-height:1.8">'
        + anomalies.map(function(a) { return '<li>' + a + '</li>'; }).join('')
        + '</ul>';
    } else {
      anomalyHtml = '<p style="color:#339933;font-weight:600;margin-top:16px">✅ No anomalies detected — everything looks healthy.</p>';
    }

    // ── Crash boot detail ──
    var crashHtml = '';
    if (crashBoots.length) {
      crashHtml = '<h3 style="margin-top:20px">Unexpected Reboots / Crashes</h3>'
        + '<table style="border-collapse:collapse;width:100%;font-size:12px">'
        + '<tr style="background:#f5f5f5"><th style="padding:4px 8px;text-align:left">Room</th>'
        + '<th style="padding:4px 8px;text-align:left">Time (UTC)</th>'
        + '<th style="padding:4px 8px;text-align:left">Time (BKK)</th>'
        + '<th style="padding:4px 8px;text-align:left">Detail</th></tr>'
        + crashBoots.map(function(c) {
          return '<tr><td style="padding:4px 8px">' + c.room + '</td>'
            + '<td style="padding:4px 8px">' + new Date(c.ts).toISOString().replace('T', ' ').slice(0, 16) + ' UTC</td>'
            + '<td style="padding:4px 8px">' + (new Date(new Date(c.ts).getTime() + 7*3600000)).toISOString().slice(11, 16) + ' BKK</td>'
            + '<td style="padding:4px 8px;color:#888">' + c.desc + '</td></tr>';
        }).join('')
        + '</table>';
    }

    // ── Claude prompt block (plain text, monospace) ──
    var claudeData = {
      reportDate: todayBkk,
      generatedAtUTC: new Date(now).toISOString(),
      tablets: roomData.map(function(r) {
        var ageMins = r.timestamp ? Math.round((now - new Date(r.timestamp).getTime()) / 60000) : null;
        return {
          room: r.roomname,
          online: ageMins !== null && ageMins < 70,
          lastSeenMinutesAgo: ageMins,
          version: (r.version && r.version.charAt(0) === 'v') ? r.version : (r.apkVersion ? '? (weekend/standby)' : r.version),
          apkVersion: r.apkVersion || (r.version && r.version.charAt(0) !== 'v' ? r.version : ''),
          uptime: r.uptime,
          meetingCount: r.meetingCount,
          alarmLogToday: (r.alarmLog || []).filter(function(e) {
            return e.ts && e.ts.startsWith(todayUTC) || e.ts.startsWith(yestUTC);
          }),
          alarmChain: {
            restart06h: findAlarm(r.alarmLog, 'restart', restartTarget),
            wake07h30: findAlarm(r.alarmLog, 'wake', wakeTarget) || findAlarm(r.alarmLog, 'wake_weekend', wakeTarget),
            standby20h30: alarmExpected(standbyTarget) ? findAlarm(r.alarmLog, 'standby', standbyTarget) : 'pending'
          }
        };
      }),
      incidents: recentIncidents.filter(function(i) { return !i.autoResolvable; }),
      anomalies: anomalies,
      kvWriteEstimate: kvWritesPerDay + '/day (' + roomData.length + ' tablets)'
    };

    var claudePrompt = 'You are analyzing RIS Room Display — a meeting room kiosk system for 12 rooms at Central Silom Tower, Bangkok. 5 tablets (Affogato, Decaffinato, Macchiato, Mocha, Viennese) run Android 4.4.2 (LG); 1 tablet (Latte) runs Android 10 (Lenovo). APK has 3 scheduled alarms daily: restart at 06:00 BKK, wake at 07:30 BKK, standby at 20:30 BKK. The alarm chain is self-scheduling and breaks on cold restart.\n\n'
      + 'Here is today\'s health snapshot:\n\n'
      + JSON.stringify(claudeData, null, 2) + '\n\n'
      + 'Please:\n'
      + '1. Identify any tablets with problems (offline, alarm gaps, unexpected reboots)\n'
      + '2. Spot patterns across rooms (e.g. same room crashing repeatedly, alarm chain consistently failing)\n'
      + '3. Flag anything that needs action today vs. can wait\n'
      + '4. Suggest 1-2 specific improvements to monitoring or the tablet software based on what you see';

    var claudeHtml = '<h3 style="margin-top:28px">📋 Paste to Claude for Deeper Analysis</h3>'
      + '<p style="font-size:12px;color:#555">Copy the block below and paste into Claude (claude.ai or Claude Code):</p>'
      + '<pre style="background:#1a1a2e;color:#e0e0e0;padding:16px;border-radius:8px;font-size:11px;'
      + 'white-space:pre-wrap;word-break:break-all;max-height:300px;overflow-y:auto">'
      + claudePrompt.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
      + '</pre>';

    var html = '<!DOCTYPE html><html><body style="font-family:sans-serif;max-width:680px;padding:24px">'
      + '<h2 style="color:#1a1a2e">RIS Room Display — Daily Health Digest</h2>'
      + '<p style="color:#555;font-size:13px">' + todayBkk + ' &nbsp;·&nbsp; Generated 20:00 BKK &nbsp;·&nbsp; '
      + onlineCount + '/' + roomData.length + ' tablets online</p>'
      + '<hr style="border:none;border-top:1px solid #eee;margin:16px 0">'
      + anomalyHtml
      + '<h3 style="margin-top:24px">Tablet Status</h3>'
      + '<table style="border-collapse:collapse;width:100%;font-size:13px">'
      + '<tr style="background:#f5f5f5">'
      + '<th style="padding:4px 10px 4px 0;text-align:left">Room</th>'
      + '<th style="padding:4px 10px;text-align:left">Version / APK</th>'
      + '<th style="padding:4px 10px;text-align:left">Last seen</th>'
      + '<th style="padding:4px 0;text-align:left">Alarm chain</th>'
      + '</tr>'
      + roomRows
      + '</table>'
      + (uniqueVersions.length > 1
          ? '<p style="color:#cc3333;font-size:12px;margin-top:8px">⚠️ Version mismatch: ' + uniqueVersions.join(', ') + '</p>'
          : '<p style="color:#888;font-size:12px;margin-top:8px">All tablets: ' + (uniqueVersions[0] || '?') + ' / APK ' + (uniqueApk[0] || '?') + '</p>')
      + crashHtml
      + '<hr style="border:none;border-top:1px solid #eee;margin:24px 0">'
      + claudeHtml
      + '<hr style="border:none;border-top:1px solid #eee;margin:24px 0">'
      + '<p style="color:#999;font-size:11px">Sent automatically by RIS Room Display daily at 20:00 BKK.</p>'
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
      console.log('Daily health digest sent OK');
    } else {
      var errBody = await resp.text().catch(function(){ return ''; });
      console.error('sendDailyHealthDigest Graph error', resp.status, errBody);
    }
  } catch (e) {
    console.error('sendDailyHealthDigest failed', e.message);
  }
}

// ═══════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════

// Decode a JWT claim without cryptographic verification.
// Used to check tenant ID + expiry on org user MSAL tokens.
function jwtClaim(token, claim) {
  try {
    var parts = (token || '').split('.');
    if (parts.length < 2) return null;
    var b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4 !== 0) b64 += '=';
    var payload = JSON.parse(atob(b64));
    return payload[claim] != null ? payload[claim] : null;
  } catch (e) { return null; }
}

// Returns true if the Authorization: Bearer header carries a non-expired
// token whose tenant ID matches RIS_TENANT_ID — i.e. a signed-in org user.
function isOrgUserToken(request, env) {
  var auth = request.headers.get('Authorization') || '';
  if (!auth.startsWith('Bearer ')) return false;
  var token = auth.slice(7);
  var tid = jwtClaim(token, 'tid');
  var exp = jwtClaim(token, 'exp');
  var tenantId = (env && env.RIS_TENANT_ID) || '817e531d-191b-4cf5-8812-f0061d89b53d';
  var nowSec = Math.floor(Date.now() / 1000);
  return tid === tenantId && exp > nowSec;
}

// ═══════════════════════════════════════
// CALENDAR — fetch room events via service account
// ═══════════════════════════════════════

async function handleCalendar(request, url, env) {
  var tabletKey = request.headers.get('X-Tablet-Key') || '';
  if (!tabletKey && !isOrgUserToken(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
  var room = url.searchParams.get('room') || '';
  var start = url.searchParams.get('startDateTime') || '';
  var end = url.searchParams.get('endDateTime') || '';
  if (!room) return jsonResponse({ error: 'Missing room' }, 400);

  var token = await getServiceToken(env);
  if (!token) return jsonResponse({ error: 'Auth failed — check RIS_SVC_USER/PASSWORD/TENANT_ID/CLIENT_ID' }, 401);

  try {
    var graphUrl = 'https://graph.microsoft.com/v1.0/users/' + encodeURIComponent(room)
      + '/calendarView?startDateTime=' + encodeURIComponent(start)
      + '&endDateTime=' + encodeURIComponent(end)
      + '&$select=id,subject,start,end,showAs,isAllDay,organizer,attendees,onlineMeeting,bodyPreview'
      + '&$top=50';
    var resp = await fetch(graphUrl, { headers: { 'Authorization': 'Bearer ' + token } });
    var data = await resp.json();
    if (data.error) return jsonResponse({ error: data.error.message, value: [] }, 200);
    return jsonResponse({ value: data.value || [] });
  } catch (e) {
    return jsonResponse({ error: e.message, value: [] }, 200);
  }
}

// ═══════════════════════════════════════
// BOOK — create room event via service account
// ═══════════════════════════════════════

async function handleBook(request, env) {
  var tabletKey = request.headers.get('X-Tablet-Key') || '';
  var adminKey = request.headers.get('X-Admin-Key') || '';
  var expectedAdmin = (env && env.RIS_ADMIN_KEY) || '';
  var adminOk = expectedAdmin && adminKey === expectedAdmin;
  if (!tabletKey && !adminOk && !isOrgUserToken(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
  var body;
  try { body = await request.json(); } catch(e) { return jsonResponse({ error: 'Invalid JSON' }, 400); }
  if (!body.room || !body.start || !body.end) return jsonResponse({ error: 'Missing room/start/end' }, 400);

  var token = await getServiceToken(env);
  if (!token) return jsonResponse({ error: 'Auth failed' }, 401);

  try {
    var event = {
      subject: body.subject || 'Meeting',
      start: { dateTime: body.start, timeZone: 'Asia/Bangkok' },
      end: { dateTime: body.end, timeZone: 'Asia/Bangkok' },
      location: { displayName: body.roomName || body.room },
      attendees: [{ emailAddress: { address: body.room }, type: 'resource' }]
    };
    if (body.organizerEmail) {
      event.attendees.push({ emailAddress: { address: body.organizerEmail, name: body.organizerName || '' }, type: 'required' });
    }
    var resp = await fetch('https://graph.microsoft.com/v1.0/users/' + encodeURIComponent(body.room) + '/events', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: JSON.stringify(event)
    });
    var data = await resp.json();
    if (data.error) return jsonResponse({ error: data.error.message }, 400);
    return jsonResponse({ ok: true, id: data.id });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// EVENT PATCH — extend meeting end time
// ═══════════════════════════════════════

async function handleEventPatch(request, env) {
  var tabletKey = request.headers.get('X-Tablet-Key') || '';
  if (!tabletKey && !isOrgUserToken(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
  var body;
  try { body = await request.json(); } catch(e) { return jsonResponse({ error: 'Invalid JSON' }, 400); }
  if (!body.room || !body.eventId || !body.end) return jsonResponse({ error: 'Missing room/eventId/end' }, 400);

  var token = await getServiceToken(env);
  if (!token) return jsonResponse({ error: 'Auth failed' }, 401);

  try {
    var resp = await fetch('https://graph.microsoft.com/v1.0/users/' + encodeURIComponent(body.room) + '/events/' + body.eventId, {
      method: 'PATCH',
      headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ end: { dateTime: body.end, timeZone: 'Asia/Bangkok' } })
    });
    var data = await resp.json();
    if (data.error) return jsonResponse({ error: data.error.message }, 400);
    return jsonResponse({ ok: true });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// SERVICE TOKEN — ROPC for Graph API calls
// ═══════════════════════════════════════

// Module-level cache — survives across requests within the same Worker instance.
// Workers are evicted after ~30s idle, so this naturally resets; no stale-token risk.
var _cachedToken = null;
var _cachedTokenExpiry = 0; // Unix ms

async function getServiceToken(env) {
  // Return cached token if still valid for at least 5 more minutes
  if (_cachedToken && Date.now() < _cachedTokenExpiry - 5 * 60 * 1000) {
    return _cachedToken;
  }
  var svcUser = env.RIS_SVC_USER || '';
  var svcPass = env.RIS_SVC_PASSWORD || '';
  var tenantId = env.RIS_TENANT_ID || '';
  var clientId = env.RIS_CLIENT_ID || '';
  var clientSecret = env.RIS_CLIENT_SECRET || '';
  if (!svcUser || !svcPass || !tenantId || !clientId) return null;
  try {
    var resp = await fetch('https://login.microsoftonline.com/' + tenantId + '/oauth2/v2.0/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'client_id=' + encodeURIComponent(clientId)
        + (clientSecret ? '&client_secret=' + encodeURIComponent(clientSecret) : '')
        + '&scope=' + encodeURIComponent('Calendars.ReadWrite Calendars.ReadWrite.Shared User.Read')
        + '&username=' + encodeURIComponent(svcUser)
        + '&password=' + encodeURIComponent(svcPass)
        + '&grant_type=password'
    });
    var data = await resp.json();
    if (data.access_token) {
      _cachedToken = data.access_token;
      // expires_in is in seconds; default to 1h if missing
      _cachedTokenExpiry = Date.now() + Math.min((data.expires_in || 3600), 10 * 60) * 1000;
    }
    return data.access_token || null;
  } catch(e) { return null; }
}

// ═══════════════════════════════════════
// VERSION — proxy apk-version.json for Android 4.4 TLS compat
// ═══════════════════════════════════════

async function handleVersion() {
  try {
    var resp = await fetch('https://pom7220.github.io/Display-Room/apk-version.json',
      { cf: { cacheEverything: false } });
    var data = await resp.json();
    return jsonResponse(data);
  } catch(e) {
    return jsonResponse({ error: 'version fetch failed' }, 502);
  }
}

async function handleIndexVersion() {
  try {
    var resp = await fetch('https://pom7220.github.io/Display-Room/index-version.json',
      { cf: { cacheEverything: false } });
    var data = await resp.json();
    return jsonResponse(data);
  } catch(e) {
    return jsonResponse({ error: 'index-version fetch failed' }, 502);
  }
}

// ═══════════════════════════════════════
// APK — proxy binary download for Android 4.4 TLS compat
// ═══════════════════════════════════════

async function handleApk() {
  try {
    var resp = await fetch('https://pom7220.github.io/Display-Room/ris-boot-launcher.apk',
      { cf: { cacheEverything: false } });
    if (!resp.ok) return new Response('APK fetch failed', { status: 502 });
    return new Response(resp.body, {
      status: 200,
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="ris-boot-launcher.apk"',
        'Cache-Control': 'no-store'
      }
    });
  } catch(e) {
    return new Response('APK fetch error: ' + e.message, { status: 502 });
  }
}

// ═══════════════════════════════════════
// EVENT DELETE — auto-release room booking
// ═══════════════════════════════════════

async function handleEventDelete(request, env) {
  var tabletKey = request.headers.get('X-Tablet-Key') || '';
  if (!tabletKey && !isOrgUserToken(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
  var body;
  try { body = await request.json(); } catch(e) { return jsonResponse({ error: 'Invalid JSON' }, 400); }
  if (!body.room || !body.eventId) return jsonResponse({ error: 'Missing room/eventId' }, 400);

  var token = await getServiceToken(env);
  if (!token) return jsonResponse({ error: 'Auth failed' }, 401);

  try {
    var resp = await fetch('https://graph.microsoft.com/v1.0/users/' + encodeURIComponent(body.room) + '/events/' + body.eventId, {
      method: 'DELETE',
      headers: { 'Authorization': 'Bearer ' + token }
    });
    if (resp.status === 404) return jsonResponse({ ok: true, note: 'already gone' });
    if (resp.status >= 400) {
      var data = await resp.json().catch(function(){ return {}; });
      return jsonResponse({ error: (data.error && data.error.message) || 'Delete failed' }, resp.status);
    }
    return jsonResponse({ ok: true });
  } catch (e) {
    return jsonResponse({ error: e.message }, 500);
  }
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PATCH, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Key, X-Tablet-Key, Authorization',
    'Access-Control-Max-Age': '86400'
  };
}

function checkAdminKey(request, env) {
  var adminKey = env.RIS_ADMIN_KEY || '';
  if (!adminKey) return true; // No key configured = open access (backward compat)
  var provided = request.headers.get('X-Admin-Key') || '';
  return provided === adminKey;
}

function jsonResponse(data, status) {
  return new Response(JSON.stringify(data), {
    status: status || 200,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*'
    }
  });
}
