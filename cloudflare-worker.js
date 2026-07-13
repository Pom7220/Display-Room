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
  // Cron trigger — runs daily at 20:00 UTC+7 (13:00 UTC)
  async scheduled(event, env) {
    if (!env.RIS_KV) return;

    // Weekly report cron: 0 11 * * 6 (Friday 11:00 UTC = 18:00 BKK)
    // Daily report cron:  0 13 * * * (daily 13:00 UTC = 20:00 BKK)
    var nowBkkDay = new Date(Date.now() + 7 * 3600000).getUTCDay(); // 5 = Friday
    var isFriday = nowBkkDay === 5;

    await generateDailyReport(env);
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

    // Build heartbeat record
    var record = {
      room: data.room,
      roomname: data.roomname || '',
      status: data.status || 'unknown',
      tokenExpiry: data.tokenExpiry || null,
      hasRefreshToken: !!data.hasRefreshToken,
      version: data.version || '',
      apkVersion: data.apkVersion || '',
      lastCal: data.lastCal || null,
      meetingCount: data.meetingCount || 0,
      uptime: data.uptime || 0,
      log: (data.log || []).slice(-10),
      qrAvgPerDay: data.qrAvgPerDay || 0,
      qrPeakDay: data.qrPeakDay || 0,
      timestamp: new Date().toISOString(),
      ip: request.headers.get('CF-Connecting-IP') || ''
    };

    // Store in KV (TTL 1 hour — if no heartbeat for 1h, record expires)
    await env.RIS_KV.put(
      'room:' + data.room,
      JSON.stringify(record),
      { expirationTtl: 3600 }
    );

    // Check if there's a pending command for this tablet
    var cmdKey = 'cmd:' + data.room;
    var pendingCmd = await env.RIS_KV.get(cmdKey);

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
        record.isOnline = lastSeen < 30;
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

    var validCommands = ['reload', 'clear_tokens', 'clear_config', 'force_fullscreen', 're_auth', 're_auth_remote'];
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
      noshows.push({
        room: record.room,
        roomname: record.roomname || record.room,
        organizer: detail.organizer || '',
        organizerEmail: detail.organizerEmail || '',
        subject: detail.subject || '(no title)'
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

async function getServiceToken(env) {
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
