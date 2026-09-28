# Remove the Remote Token-Injection Path — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop putting live Microsoft Graph credentials into a delivery channel that two unauthenticated endpoints will hand to anyone who asks.

**Architecture:** Delete rather than authenticate. `re_auth_remote` fetches a Graph access and refresh token via ROPC and stores them in KV under `cmd:<room>`. Both `GET /api/command` and `POST /api/heartbeat` return that value without any credential. The tokens are also useless: every tablet now runs in Worker-proxy mode, where the injected MSAL tokens are never read. Removing the feature closes the exposure without touching the APK.

**Tech Stack:** Cloudflare Worker (`cloudflare-worker.js`, CRLF), ES5 browser JS (`index.html`), modern JS (`dashboard.html`).

**Source:** `.claude/agents/knowledge-base.md` — "2026-09-26 — Weekend security validation", Finding 1.

---

## Global Constraints

- **`cloudflare-worker.js` uses CRLF line endings.** `\n`-based edits match nothing and fail silently. Use the Edit tool.
- **`index.html` is ES5-only** (Chromium 30 on Android 4.4): no `const`, `let`, arrow functions, template literals.
- **Pushing `cloudflare-worker.js` to `main` auto-deploys it.** No staging environment.
- **`dashboard.html` and `index.html` must be syntax-checked before push.** A literal newline inside a single-quoted string once took the whole dashboard down; the checker in Task 3 Step 1 exists for that.
- **Bump `index.html`'s `APP_VERSION.patch` and `index-version.json` together** on any `index.html` change. Current: `v3.10.228`.
- **Do not touch the `re_auth` command** (distinct from `re_auth_remote`). It is already inert in proxy mode and is out of scope.
- **Do not gate `GET /api/command` or `POST /api/heartbeat` in this change.** Once the tokens are gone, what remains in that channel is ordinary commands — a separate, lower-severity decision that needs an APK release.
- All times BKK. Tablets heartbeat every 30 min; command TTL is 1800s.

---

## File Structure

| File | Change |
|---|---|
| `cloudflare-worker.js` | remove `re_auth_remote` from `validCommands`, its dispatch branch, `handleRemoteReauth()`, and the `/api/test-reauth` route |
| `index.html` | remove the `inject_tokens` handler branch and the `re_auth_remote` throttle; correct a stale comment; version bump |
| `index-version.json` | version bump |
| `dashboard.html` | remove `sendReauth()` and its dispatch line |

No new files. No APK change — the exposure is entirely Worker- and web-side.

---

## Ordering

**Worker first, then the clients.** The Worker change stops new `inject_tokens` commands being created; the client changes then remove a handler that can no longer be triggered. Doing it the other way round would leave a window where tokens are still minted but nothing consumes them.

A command already queued when the Worker ships will still be delivered until its 1800s TTL expires. After Task 3 the tablet logs `Unknown command: inject_tokens` and ignores it. Harmless.

---

## Task 1: Remove the server-side token minting

**Files:**
- Modify: `cloudflare-worker.js` — `validCommands` (~line 574), the `re_auth_remote` dispatch (~579-582), `handleRemoteReauth()` (~603-675), the `/api/test-reauth` route (~173-208)

**Interfaces:**
- Removes: the `re_auth_remote` command value, and `GET /api/test-reauth`
- After this task `POST /api/command` with `re_auth_remote` returns 400 `Invalid command`, listing the remaining valid commands

- [ ] **Step 1: Capture the "before" behaviour**

```bash
curl -s -o /dev/null -w '%{http_code}\n' 'https://ris-display.ris-display.workers.dev/api/test-reauth'
```
Expected: `401` (admin-gated today). Record it — after this task the same call must return `404`, which is how you prove the route is gone rather than merely still protected.

- [ ] **Step 2: Remove `re_auth_remote` from the valid command list**

At `cloudflare-worker.js:574`, change:
```js
    var validCommands = ['reload', 'clear_tokens', 'clear_config', 'force_fullscreen', 're_auth', 're_auth_remote', 'fetchcal', 'auto_tap', 'set_tablet_key', 'enable_test_sleep', 'perform_update'];
```
to:
```js
    // re_auth_remote removed 2026-09-28: it minted a Graph access+refresh token into
    // cmd:<room>, which both GET /api/command and POST /api/heartbeat return without any
    // credential. Tablets run in Worker-proxy mode and never read injected MSAL tokens.
    var validCommands = ['reload', 'clear_tokens', 'clear_config', 'force_fullscreen', 're_auth', 'fetchcal', 'auto_tap', 'set_tablet_key', 'enable_test_sleep', 'perform_update'];
```

Keep `re_auth` — it is a different command and is out of scope.

- [ ] **Step 3: Remove the dispatch branch**

Delete these lines (~579-582):
```js
    // Special handling for re_auth_remote — fetch tokens server-side via ROPC
    if (data.command === 're_auth_remote') {
      return handleRemoteReauth(data.room, data.sentBy || 'admin', env);
    }
```

- [ ] **Step 4: Remove `handleRemoteReauth()` entirely**

Delete the whole function starting at the `// REMOTE RE-AUTH — ROPC flow server-side` banner comment through the closing brace of `handleRemoteReauth` (~603-675), including the banner. Read the region first and delete exactly that span — the next function is `handleCommandGet`, which must remain.

- [ ] **Step 5: Remove the `/api/test-reauth` route**

Delete the block from `// GET /api/test-reauth — test ROPC credentials without sending to tablet` through the closing `}` of that route handler (~173-208). The next route is `POST /api/reports/generate`, which must remain.

- [ ] **Step 6: Confirm nothing still references the removed names**

```bash
grep -n "re_auth_remote\|handleRemoteReauth\|test-reauth" cloudflare-worker.js
```
Expected: only the explanatory comment added in Step 2. Any other hit is a dangling reference — a call to a deleted function would throw at runtime on a live Worker.

- [ ] **Step 7: Check the file still parses**

```bash
node --check cloudflare-worker.js
```
Expected: no output. A syntax error here would take down every endpoint, so do not skip it.

- [ ] **Step 8: Commit and push**

```bash
git add cloudflare-worker.js
git commit -m "security: remove remote token injection (re_auth_remote)

It minted a Graph access+refresh token into cmd:<room>. Both GET /api/command
and POST /api/heartbeat return that value with no credential, so anyone knowing
a room email could collect tenant credentials with Calendars.ReadWrite and
Mail.Send -- and consume the command so the tablet never saw it.

The tokens were also unused: every tablet runs in Worker-proxy mode, where
injected MSAL tokens are never read. Deleting the feature closes the exposure
without an APK change.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

- [ ] **Step 9: Verify the deploy**

Wait ~45s for `deploy-worker.yml`, then:
```bash
curl -s -o /dev/null -w 'test-reauth: %{http_code}\n' 'https://ris-display.ris-display.workers.dev/api/test-reauth'
```
Expected: `404` (was `401`).

Then confirm the command is rejected, and that ordinary commands still work. This needs the admin key, so it is the operator's step:
```
POST /api/command with X-Admin-Key and body {"room":"rismacchiato@central.co.th","command":"re_auth_remote"}
```
Expected: `400` with `Invalid command. Valid: ...` and no `re_auth_remote` in the list.

- [ ] **Step 10: Confirm the fleet is unaffected**

All twelve rooms still serving and heartbeating on the dashboard. Nothing in this task touches the paths tablets use, so any change here is a regression.

---

## Task 2: Remove the dashboard trigger

**Files:**
- Modify: `dashboard.html` — the `re_auth_remote` dispatch (~line 1655) and `sendReauth()` (~1672-1693)

**Interfaces:**
- Consumes: the Worker no longer accepting `re_auth_remote` (Task 1)
- Removes: `sendReauth()`. It has **no UI caller** — the admin card's Fix button uses `reload` or `auto_tap` (`dashboard.html:1397-1400`), so this is reachable only from the browser console.

- [ ] **Step 1: Confirm there is genuinely no UI caller**

```bash
grep -n "re_auth_remote\|sendReauth" dashboard.html
```
Expected: exactly two hits — the dispatch line and the function. If an `onclick` or button also appears, stop and report: removing it would change the admin UI, which this task does not cover.

- [ ] **Step 2: Remove the dispatch line**

At `dashboard.html:1655`, delete:
```js
  if (command === 're_auth_remote') { return sendReauth(room); }
```

- [ ] **Step 3: Remove `sendReauth()`**

Delete the whole function (~1672-1693), from `async function sendReauth(room) {` through its closing brace.

- [ ] **Step 4: Confirm the names are gone**

```bash
grep -c "re_auth_remote\|sendReauth" dashboard.html
```
Expected: `0`.

- [ ] **Step 5: Syntax-check every inline script**

```bash
node -e "
var fs=require('fs');
var src=fs.readFileSync('dashboard.html','utf8');
var re=/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi, m, i=0, bad=0;
while((m=re.exec(src))){
  i++;
  var line=src.slice(0,m.index).split('\n').length;
  try{ new Function(m[1]); }
  catch(e){ bad++; console.log('SYNTAX ERROR in script #'+i+' near line '+line+': '+e.message); }
}
console.log('checked '+i+' inline scripts, '+bad+' with errors');
if(bad) process.exit(1);
"
```
Expected: `0 with errors`. A syntax error here blanks the whole dashboard — this has happened before.

- [ ] **Step 6: Commit and push**

```bash
git add dashboard.html
git commit -m "security: remove dashboard re_auth_remote trigger

Follows the Worker-side removal. sendReauth had no UI caller -- the admin Fix
button sends reload or auto_tap -- so this removes console-only dead code.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

- [ ] **Step 7: Verify the dashboard still loads**

Open it and confirm: the clock renders, all twelve rooms list, the admin panel opens with the key, and the Fix buttons are present. A blank page or a stuck `--:--` clock means a syntax error reached production.

---

## Task 3: Remove the tablet-side handler

**Files:**
- Modify: `index.html` — the `re_auth_remote` throttle (~1969-1972), the stale comment (~2024), the `inject_tokens` branch (~2031-2055), `APP_VERSION` (~871)
- Modify: `index-version.json`

**Interfaces:**
- Consumes: the Worker no longer minting `inject_tokens` (Task 1)
- Produces: web `v3.10.229`

A command queued before Task 1 shipped may still arrive within its 1800s TTL. After this change the tablet logs `Unknown command: inject_tokens` and ignores it, which is the desired outcome.

- [ ] **Step 1: Remove the `re_auth_remote` throttle**

Around `index.html:1969`, delete:
```js
  // Prevent re_auth_remote within 60s of a reload (reload causes state change)
  if(action==='re_auth_remote'&&_lastCmdAction==='reload'&&now-_lastCmdTime<60000){
    dbg('re_auth_remote ignored — reload happened '+Math.round((now-_lastCmdTime)/1000)+'s ago, wait for page to settle');
    return;
  }
```

**Leave `_lastCmdAction` and `_lastCmdTime` alone.** Already checked: they are declared at
`index.html:1957-1958`, used by the generic 30-second duplicate-command guard at line 1965,
and assigned at 1974-1975. Only the four lines above are removed. Deleting the variables
would break command dedup for every command.

- [ ] **Step 2: Correct the stale comment**

At ~`index.html:2024`:
```js
      // Worker proxy flow — admin should use re_auth_remote (ROPC) instead
```
becomes:
```js
      // Worker proxy flow — no MSAL token to refresh. A tablet that has lost its
      // key needs a prefs write over ADB (runbook step 8d), not a remote re-auth.
```

- [ ] **Step 3: Remove the `inject_tokens` branch**

Delete the entire `else if(action==='inject_tokens'){ ... }` block (~2031-2055), from that line through its closing brace. The next branch is `else if(action==='set_tablet_key'){`, which must remain, and the `else if` chain must stay intact — read the region before and after to confirm you have not broken it.

- [ ] **Step 4: Confirm the names are gone**

```bash
grep -n "inject_tokens\|re_auth_remote" index.html
```
Expected: no hits.

- [ ] **Step 5: Bump the version**

`index.html` `APP_VERSION`:
```js
  patch: 229,
  date: '2026-09-28',
```

Add above the `// v3.10.228` history line:
```
// v3.10.229 2026-09-28 - Remove inject_tokens handler and the re_auth_remote throttle. The Worker no longer mints Graph tokens into the command channel (both GET /api/command and POST /api/heartbeat returned it uncredentialed); tablets run in proxy mode and never read injected MSAL tokens
```

`index-version.json`:
```json
{"version":"v3.10.229"}
```

- [ ] **Step 6: Syntax-check every inline script**

```bash
node -e "
var fs=require('fs');
var src=fs.readFileSync('index.html','utf8');
var re=/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi, m, i=0, bad=0;
while((m=re.exec(src))){
  i++;
  var line=src.slice(0,m.index).split('\n').length;
  try{ new Function(m[1]); }
  catch(e){ bad++; console.log('SYNTAX ERROR in script #'+i+' near line '+line+': '+e.message); }
}
console.log('checked '+i+' inline scripts, '+bad+' with errors');
if(bad) process.exit(1);
"
```
Expected: `4 inline scripts, 0 with errors`.

- [ ] **Step 7: Check for ES6 that Chromium 30 cannot parse**

```bash
grep -nE "=>|\bconst \b|\blet \b|\`" index.html | grep -v "^ *//" | head
```
Review any hits in the region you edited. Pre-existing hits elsewhere are not this task's problem; new ones are, and would white-screen every tablet.

- [ ] **Step 8: Commit and push**

```bash
git add index.html index-version.json
git commit -m "security: remove inject_tokens handler (v3.10.229)

Completes the removal of the remote token-injection path. Tablets run in
Worker-proxy mode and never read injected MSAL tokens, so this handler only
ever wrote credentials into localStorage that nothing consumed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

- [ ] **Step 9: Verify the Worker serves it**

Poll until it flips (GitHub Pages takes a few minutes):
```bash
curl -s "https://ris-display.ris-display.workers.dev/index-version.json?nocache=$(date +%s)"
```
Expected: `{"version":"v3.10.229"}`.

- [ ] **Step 10: Verify on a tablet**

Wait for one tablet to pick up `v3.10.229`, or force it with **Reload** from the dashboard. Then confirm on the dashboard that the room still renders meetings, still heartbeats, and reports the new web version.

Check one command still works end to end — send `fetchcal` to that room and confirm the calendar refreshes. The command channel is the thing this change touched most, so proving an ordinary command still arrives is the real regression test.

---

## Rollback

Every task is reversible with `git revert` plus a push; the Worker redeploys automatically and the web app is picked up on the next update check. Nothing here changes device state, so no tablet visit is needed to undo it.

---

## Out of scope

- **Gating `GET /api/command` and `POST /api/heartbeat`.** Once the tokens are gone, the channel carries only ordinary commands. Someone could still consume one so an admin action silently does not happen — annoying, not dangerous. Gating needs the APK to send `X-Tablet-Key`, since `postJsonFire` (`KioskWebViewActivity.java:595`) sets only `Content-Type` and the OkHttp callers set no headers at all. Fold into the next APK release.
- **`POST /api/incident`, `/api/alarm`, `/api/heartbeat` as unauthenticated KV writers** (knowledge base Finding 2). Impact is budget exhaustion — lost monitoring, not lost service, since `/api/calendar`, `/api/book` and `/api/event` do not need KV. Edge rate limiting was the preferred direction; undecided.
- **The `re_auth` command and `doReauth()`.** Inert in proxy mode, but removing them is a separate cleanup.
- **ROPC → client credentials.** Two call sites remain after this change: `getServiceToken()` and, indirectly, the service-account password in Worker secrets.
