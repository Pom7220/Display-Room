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
      // Require KV binding
      if (!env.RIS_KV) {
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

    var validCommands = ['reload', 'clear_tokens', 'clear_config', 'force_fullscreen', 're_auth'];
    if (validCommands.indexOf(data.command) === -1) {
      return jsonResponse({ error: 'Invalid command. Valid: ' + validCommands.join(', ') }, 400);
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
