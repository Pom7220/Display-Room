# Cloudflare Worker Bug Fix Report

## Fix C2 — `/api/token` excluded from KV gate

**File:** `cloudflare-worker.js`, line 44

**Problem:** All `/api/*` routes were blocked with HTTP 500 when `env.RIS_KV` was not bound. The `/api/token` route only uses ROPC secrets (no KV reads/writes), so tablets on deployments without KV could not boot and acquire tokens.

**Change:**
```javascript
// Before
if (!env.RIS_KV) {
  return jsonResponse({ error: 'KV not configured' }, 500);
}

// After
if (!env.RIS_KV && path !== '/api/token') {
  return jsonResponse({ error: 'KV not configured' }, 500);
}
```

---

## Fix I2 — `/api/token` credential auth enforced unconditionally

**File:** `cloudflare-worker.js`, lines 122–129

**Problem:** `/api/token` used `checkAdminKey()`, which returns `true` when `RIS_ADMIN_KEY` is not set (open-access backward-compat mode). This exposed live Azure AD access tokens, refresh tokens, and ID tokens to unauthenticated callers on any deployment without the admin key configured.

**Change:**
```javascript
// Before
if (path === '/api/token' && method === 'POST') {
  if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
  return handleTokenFetch(env);
}

// After
if (path === '/api/token' && method === 'POST') {
  var adminKey = env.RIS_ADMIN_KEY || '';
  var providedKey = request.headers.get('X-Admin-Key') || '';
  if (!adminKey || providedKey !== adminKey) {
    return jsonResponse({ error: 'Unauthorized' }, 401);
  }
  return handleTokenFetch(env);
}
```

Inline check fails closed: if `RIS_ADMIN_KEY` is not configured, returns 401. Correct key must be both present and matching.

---

*Applied 2026-07-01. All other routes unchanged.*
