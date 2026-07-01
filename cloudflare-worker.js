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
    await generateDailyReport(env);
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
      // Require KV binding (except /api/token which uses only ROPC secrets)
      if (!env.RIS_KV && path !== '/api/token') {
        return jsonResponse({ error: 'KV not configured' }, 500);
      }

      // POST /api/heartbeat — tablet sends health report
      if (path === '/api/heartbeat' && method === 'POST') {
        return handleHeartbeat(request, env);
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
          var body = 'client_id=' + encodeURIComponent(clientId)
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

      // POST /api/token — APK fetches ROPC tokens on boot (TokenFetcher.java)
      if (path === '/api/token' && method === 'POST') {
        var adminKey = env.RIS_ADMIN_KEY || '';
        var providedKey = request.headers.get('X-Admin-Key') || '';
        if (!adminKey || providedKey !== adminKey) {
          return jsonResponse({ error: 'Unauthorized' }, 401);
        }
        return handleTokenFetch(env);
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
      lastCal: data.lastCal || null,
      meetingCount: data.meetingCount || 0,
      uptime: data.uptime || 0,
      log: (data.log || []).slice(-10),
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
        // Calculate "last seen" minutes
        var lastSeen = Math.round(
          (Date.now() - new Date(record.timestamp).getTime()) / 60000
        );
        record.lastSeenMinutes = lastSeen;
        record.isOnline = lastSeen < 30; // offline if no heartbeat for 30 min
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
    var body = 'client_id=' + encodeURIComponent(clientId)
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
    },
  });

  var newHeaders = new Headers(response.headers);
  newHeaders.set('Access-Control-Allow-Origin', '*');
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
// TOKEN FETCH — ROPC tokens for APK boot
// ═══════════════════════════════════════
// Called by TokenFetcher.java on every boot.
// Returns tokens directly (not queued as a command).

async function handleTokenFetch(env) {
  var svcUser = env.RIS_SVC_USER || '';
  var svcPass = env.RIS_SVC_PASSWORD || '';
  var tenantId = env.RIS_TENANT_ID || '';
  var clientId = env.RIS_CLIENT_ID || '';

  if (!svcUser || !svcPass || !tenantId || !clientId) {
    return jsonResponse({
      ok: false,
      error: 'Token fetch not configured. Set RIS_SVC_USER, RIS_SVC_PASSWORD, RIS_TENANT_ID, RIS_CLIENT_ID in Worker secrets.'
    }, 400);
  }

  try {
    var tokenUrl = 'https://login.microsoftonline.com/' + tenantId + '/oauth2/v2.0/token';
    var body = 'client_id=' + encodeURIComponent(clientId)
      + '&scope=' + encodeURIComponent('Calendars.ReadWrite Calendars.ReadWrite.Shared Mail.Send User.Read openid profile offline_access')
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
      return jsonResponse({ ok: false, error: data.error_description || data.error }, 401);
    }

    return jsonResponse({
      ok: true,
      access_token: data.access_token || '',
      refresh_token: data.refresh_token || '',
      id_token: data.id_token || '',
      client_id: clientId,
      expires_in: data.expires_in || 3600
    });
  } catch (e) {
    return jsonResponse({ ok: false, error: e.message }, 500);
  }
}

// ═══════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Key',
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
