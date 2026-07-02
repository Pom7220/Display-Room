# RIS Room Display

Meeting room kiosk display and mobile dashboard for the RIS floor at Central Silom Tower, Bangkok. Manages 12 coffee-themed room mailboxes via Microsoft Graph API, deployed on LG 10SM3TB tablets (Android 4.4.2) via WebView APK and modern devices via browser.

**Live URLs:**
- Kiosk (via Cloudflare): `https://ris-display.ris-display.workers.dev/`
- Kiosk (direct GitHub Pages): `https://pom7220.github.io/Display-Room/`
- Dashboard: `https://pom7220.github.io/Display-Room/dashboard.html`

**Current versions:** index v3.10.70 · dashboard v2.0 · ris-shared v1.4 · msal-v1 v1.3 · boot-launcher v5.0

---

## Architecture

```
LG Tablet (Android 4.4.2)            iPhone/Surface (modern browser)
WebView APK v5.0                               │
        │                                      │
        │ loads Worker URL                     │
        │ ?tabletkey=...&webview=1             │
        ▼                                      ▼
  Cloudflare Worker              GitHub Pages (direct)
  - Serves index.html/JS         (Let's Encrypt cert)
  - ROPC auth proxy to Graph             │
  - Heartbeat & command API              │
  - DigiCert cert (trusted by            │
    Android 4.4.2 natively)              │
        │                                │
        └──── proxies static ───────►  GitHub Pages
                                               │
                                     ┌─────────┴────────┐
                                     ▼                   ▼
                               index.html           dashboard.html
                                     │                   │
                                     └─────────┬─────────┘
                                               ▼
                                         ris-shared.js
                                               │
                                               ▼
                                    Microsoft Graph API
                                    (room calendar data)
```

**Auth — two paths:**

| Path | Device | Method |
|---|---|---|
| **Tablet (WebView APK)** | LG 10SM3TB | Worker ROPC proxy — APK passes `X-Tablet-Key` header; Worker fetches Graph token via service account (no MSAL on tablet) |
| **Browser / Dashboard** | iPhone, Surface, PC | OAuth2 implicit flow via `msal-v1.min.js` — tokens renewed silently via hidden iframe |

---

## File Specifications

### index.html (v3.10.70) — Kiosk Display

**Language:** ES5 only. Runs on Chromium 30 (WebView APK) and Chrome 42 (browser). No `const`/`let`, arrows, template literals, `fetch()`, or CSS variables.

**Screens:**
1. **Config screen** — Room email/name, tablet key, auto-release timer, booking toggle, heartbeat interval, debug/kiosk toggles
2. **Auth overlay** — MSAL sign-in (browser mode only)
3. **Main app** — Header (room name, clock, weather), status strip (available/busy/soon/pending), current meeting card (title, organiser, time, progress bar, check-in/end/extend buttons), meeting list (today/tomorrow/more+ tabs), Book Room button

**Key functions:**

| Function | Purpose |
|---|---|
| `initMsal()` | Creates MSAL v1 instance, handles redirect callback (browser mode only) |
| `fetchCal()` | Fetches room calendar via Worker proxy (tablet) or Graph API direct (browser) — XHR, every 5 minutes |
| `updateCurrentCard()` | Renders current meeting or "Free to Book"; handles all-day bookings |
| `renderMeetList()` | Today/tomorrow meeting list |
| `enterKiosk()` | Fullscreen — skips tap overlay if `webview=1` param or `ris_from_reload` sessionStorage flag set |
| `openModal()` / `confirmBooking()` | Booking flow with time picker, duration chips, Graph API POST |
| `doCheckin()` / `endMeeting()` / `extendMeeting()` | Meeting actions with overlap protection |
| `sendHeartbeat()` | Posts tablet status to Worker `/api/heartbeat` every 20 minutes |
| `pollCommand()` | Polls Worker `/api/poll` every 2 minutes for remote commands (reload, reauth) |
| `fetchWeather()` | XHR fetch from open-meteo.com — static Bangkok fallback if unavailable |

**Boot sequence (tablet/WebView):**
```
APK loads Worker URL ?tabletkey=RIS-TABLET-KEY2026&webview=1&room=...
→ cfg.tabletKey set → proxy mode (no MSAL)
→ sessionStorage ris_from_reload='1' → enterKiosk() skips tap overlay
→ fetchCal() → XHR to Worker /api/calendar with X-Tablet-Key header
→ startMonitoring() → heartbeat + command polling begins
```

**Boot sequence (browser mode):**
```
loadCfg() → initMsal() → handleRedirectCallback → launch()
→ fetchCal() + intervals → enterKiosk() (auto-fullscreen attempt)
→ acquireTokenSilent every 45 min (iframe renewal)
```

---

### dashboard.html (v2.0) — Mobile Dashboard

**Language:** ES6 (modern browsers only — not used on LG tablets).

**Layout:** Two-zone grid (Lobby 1–6, Office 7–12), 3-column tiles with status dots and badges, detail card with book/request button, schedule popup, remote command panel (reload all, reauth).

**Key functions:**

| Function | Purpose |
|---|---|
| `fetchAllRooms()` | Parallel fetch of all 12 room calendars, 60-second refresh |
| `getStatusProps(room)` | Maps `deriveRoomStatus()` to UI (dot colour, badge, info text) |
| `showSchedule()` | Schedule popup — splits all-day (blue banner) from timed meetings |
| `confirmBooking()` | Resource booking with conflict check before POST |
| `/api/status` | Shows last heartbeat time and online status for each tablet |

---

### ris-shared.js (v1.4) — Shared Logic

**Language:** ES5 only. Loaded by both `index.html` and `dashboard.html`. All network calls use XHR — no `fetch()`.

**Exports:**

| Symbol | Purpose |
|---|---|
| `RIS_ROOMS` | Array of 12 room definitions (num, name, email, seats, zone, approval) |
| `gDate(o)` | Parses Graph API dateTime objects — handles UTC, Z-suffix, bare local time (Chrome 42 safe) |
| `isAllDay(m)` | True if meeting duration ≥ 86,399,000ms (23h 59m 59s) |
| `mapEvent(ev)` | Maps Graph API event → internal object (id, title, organiser, start, end, joinUrl, isOnline, showAs, isPending) |
| `filterMeetings(evArr)` | Maps, filters cancelled, sorts by start |
| `deriveRoomStatus(meetings)` | Returns `{status, cur, nxt, freeUntil, allDayCur}`. Status: avail/busy/soon/pending |
| `fetchCalendarForRoom(email, tok, start, end, tabletKey)` | XHR Promise — uses Worker proxy if `tabletKey` present, else direct Graph API |
| `sharedFmtDur(mins)` | "3h 45m" formatter |
| `sharedFt(d)` | "HH:MM" formatter |

---

### cloudflare-worker.js — Cloudflare Worker

**Roles:**
1. Transparent proxy to GitHub Pages through DigiCert certificate (trusted by Android 4.4.2)
2. ROPC auth proxy — fetches Microsoft Graph tokens for tablets via service account
3. REST API: `/api/calendar`, `/api/book`, `/api/heartbeat`, `/api/poll`, `/api/status`

All tablet requests authenticated via `X-Tablet-Key: RIS-TABLET-KEY2026` header. Worker validates key against `RIS_TABLET_KEY` secret before proxying to Graph API.

---

### msal-v1.min.js (v1.3) — Authentication Library

**Browser mode only** — not loaded or used by WebView APK (tablets use Worker ROPC proxy instead).

**Key design:**
- OAuth2 implicit flow with Microsoft Identity Platform v2.0
- Tokens stored in localStorage with expiry tracking
- `acquireTokenSilent()` — checks cached token first, then hidden iframe renewal
- Hidden iframe: navigates to Azure AD with `prompt=none`, listens for postMessage, 15-second timeout
- `_processRedirectIfRequired()` — detects iframe context, posts hash to parent instead of processing locally

**Token lifecycle:**
```
Sign in → token stored (~60 min expiry)
  → 45-min keepalive → acquireTokenSilent → cache hit → OK
  → ~55 min → expired → iframe renewal (invisible)
    → Azure session cookie alive → new token → next 60 min covered
  → If iframe fails (session expired) → loginRedirect
```

Silent renewal works for 24 hours (default Azure session) or 90 days (IT-configured persistent browser session).

---

### sw.js — Service Worker

**Browser/PWA mode only.** Not used by WebView APK (`LOAD_NO_CACHE` setting bypasses it entirely).

**Strategy:** Network-first. Fetches from network; caches on success; serves cache if offline.

**Critical rules:**
- No `skipWaiting()` — prevents mid-session disruption
- No `clients.claim()` — avoids taking over live sessions
- Bump `CACHE_VERSION` on every deploy to trigger old cache deletion

---

### manifest.json — PWA Manifest

Enables "Add to Home Screen" standalone mode on modern devices. `display: fullscreen` for browser kiosk mode. Not relevant for WebView APK (APK manages its own fullscreen).

---

### boot-launcher/ (v5.0) — Android APK

**Package:** `th.co.central.ris.bootlauncher`

**Components:**

| File | Purpose |
|---|---|
| `KioskWebViewActivity.java` | Fullscreen WebView — loads Worker URL with `tabletkey` + `webview=1`. Hides system nav bar (immersive sticky). Portrait orientation. Blocks back button. |
| `BootReceiver.java` | Listens for `BOOT_COMPLETED` — waits 90s for WiFi, launches `KioskWebViewActivity` |
| `MainActivity.java` | Setup screen — room email/name selection, saved to SharedPreferences |
| `ForegroundWatchService.java` | Watchdog — checks every 5 min if WebView is foreground; relaunches if not |
| `KioskAccessibilityService.java` | Accessibility service for auto-tap if needed |

**Boot flow:**
```
Tablet powers on → BootReceiver fires
→ 90s delay (WiFi settle)
→ KioskWebViewActivity launches
→ Loads Worker URL ?tabletkey=RIS-TABLET-KEY2026&webview=1&room=...
→ No SSL warning (DigiCert, trusted natively by Android 4.4.2)
→ index.html loads → proxy mode → fetchCal → display shown
→ No tap required (webview=1 param suppresses overlay)
```

---

## Azure AD Configuration

| Setting | Value |
|---|---|
| Tenant ID | `817e531d-191b-4cf5-8812-f0061d89b53d` |
| Client ID | `80648895-4acf-4ac5-b4a3-c5bf6bc98983` |
| App name | RIS OAuth Meeting Room Kiosk |
| Auth method | Implicit flow (browser) + ROPC via Cloudflare Worker (tablets) |
| Service account | `rismeetingroomsystem@central.co.th` (no MFA) |
| Delegated scopes | `Calendars.ReadWrite`, `Calendars.ReadWrite.Shared`, `Mail.Send`, `User.Read` |
| Redirect URIs | `https://pom7220.github.io/Display-Room/index.html`, `https://pom7220.github.io/Display-Room/dashboard.html`, `https://ris-display.ris-display.workers.dev/index.html` |

---

## Cloudflare Worker Secrets

Set in Cloudflare dashboard → Workers → `ris-display` → Settings → Variables:

| Secret | Purpose |
|---|---|
| `RIS_SVC_USER` | Service account email (`rismeetingroomsystem@central.co.th`) |
| `RIS_SVC_PASSWORD` | Service account password |
| `RIS_TENANT_ID` | Azure AD tenant ID |
| `RIS_CLIENT_ID` | Azure AD app client ID |
| `RIS_CLIENT_SECRET` | Azure AD app client secret |
| `RIS_TABLET_KEY` | Tablet auth key (`RIS-TABLET-KEY2026`) |
| `RIS_ADMIN_KEY` | Admin operations key — for dashboard remote commands |

**Free tier:** 100,000 requests/day. Expected at 12 tablets: ~13,000/day.

---

## 12 Rooms

| # | Name | Email | Seats | Zone | Approval |
|---|---|---|---|---|---|
| 1 | Espresso | risespresso@central.co.th | 8–12 | Lobby | No |
| 2 | Doppio | risdoppio@central.co.th | 6–8 | Lobby | No |
| 3 | Cappuccino | riscappuccino@central.co.th | 6 | Lobby | No |
| 4 | Americano | risamericano@central.co.th | 6 | Lobby | No |
| 5 | Lungo | rislungo@central.co.th | 4 | Lobby | No |
| 6 | Ristretto | risristretto@central.co.th | 4 | Lobby | No |
| 7 | Macchiato | rismacchiato@central.co.th | 5–8 | Office | **Yes** |
| 8 | Viennese | risviennese@central.co.th | 6 | Office | No |
| 9 | Decaffinato | risdecaffeinato@central.co.th | 6 | Office | No |
| 10 | Latte | rislatte@central.co.th | 6 | Office | No |
| 11 | Mocha | rismocha@central.co.th | 6 | Office | No |
| 12 | Affogato | risaffogato@central.co.th | 6 | Office | No |

---

## Calendar Logic

- **All-day events** — included in `cur` (room IS booked). Excluded from `nxt` (no "coming soon"). Excluded from meeting list (shown at card level)
- **`showAs:'free'`** — kept (room calendars return free via delegated access)
- **`showAs:'tentative'`** = `isPending` = pending approval. Excluded from kiosk cur/nxt; shown in dashboard
- **`isCancelled`** — filtered out entirely

**Booking:** `POST /users/{roomEmail}/events` with room as `type:'resource'` attendee. Requester added as `type:'required'` → receives invitation email. Exchange auto-accepts/declines on availability. Macchiato triggers approval email to room owner.

---

## ES5 / Chromium 30 Constraints

All code in `index.html` and `ris-shared.js` must be ES5 only:

- **No:** `const`/`let`, arrow functions, template literals, `async`/`await`, optional chaining, `Set`/`Map`, `for...of`, `NodeList.forEach`, `.closest()`
- **No:** `fetch()` — use XHR for all network calls
- **No CSS:** `var(--custom)`, `inset:`, CSS Grid
- **Touch scrolling:** `-webkit-overflow-scrolling: touch`
- **Canvas:** Round floats to 4 dp before `addColorStop()` — Chromium 30 rejects high-precision floats

Pre-flight before every deploy:
```
grep -c "=>\|const \|let \|async \|\`" index.html ris-shared.js
# Must be 0
grep -c "fetch(" index.html ris-shared.js
# Must be 0 (sendMail is the only exception — unreachable in WebView/tabletKey mode)
```

---

## Deploy Checklist

### index.html / ris-shared.js
1. Edit files
2. Bump `APP_VERSION.patch` and `APP_VERSION.date` in `index.html`
3. If `ris-shared.js` changed, bump `?v=` on `<script src="ris-shared.js?v=14">` in `index.html`
4. Run ES5 pre-flight check
5. Push to `main` — GitHub Pages deploys automatically
6. Tablets auto-update within 5 minutes

### cloudflare-worker.js
1. Edit `cloudflare-worker.js`
2. Cloudflare dashboard → Workers → `ris-display` → Edit → paste → Deploy

### Boot Launcher APK
1. Edit files under `boot-launcher/`
2. Bump `versionCode` + `versionName` in `boot-launcher/app/build.gradle`
3. Push to `main` → GitHub Actions builds APK and commits `ris-boot-launcher.apk` to repo root automatically
4. Download `ris-boot-launcher.apk` directly from repo root (or Actions → Artifacts as fallback)
5. Install on tablet: sideload via File Manager or `adb install -r ris-boot-launcher.apk`

APK signing password: `a0000`

---

## Corporate Handover

**GitHub (`Pom7220/Display-Room`):**
- Transfer repo to corporate GitHub org: Settings → Danger Zone → Transfer Ownership
- GitHub Pages and Actions follow automatically
- Revoke personal fine-grained token; generate new one under corporate account

**Cloudflare (`ris-display` Worker):**
- Add corporate admin to Cloudflare account, OR recreate Worker under corporate account
- Worker code: paste `cloudflare-worker.js` from repo
- Re-enter all 7 secrets manually (Cloudflare does not export secret values)

**Azure AD:** Already under `central.co.th` tenant — no transfer needed.

---

## Version History

| Date | Version | Changes |
|---|---|---|
| 2026-05-26 | v1.0 | Initial release — MSAL v3, basic calendar display |
| 2026-05-27 | v1.1–1.2 | ES6→ES5 conversion, custom MSAL v1 |
| 2026-06-08 | v3.6.1 | doReauth token expiry fix |
| 2026-06-10 | v3.10.9 | Chrome 42 prompt detection |
| 2026-06-11 | v3.10.14 | JWT loginHint decode, auto-fullscreen, session keepalive |
| 2026-06-12 | v3.10.15 | All-day booking fix, msal-v1 v1.3 iframe renewal, dashboard v2.0, Cloudflare Worker, boot-launcher v1.2 |
| 2026-07-01 | v3.10.55–59 | Button layout, tap-free reload, sessionStorage kiosk flag |
| 2026-07-01 | v3.10.66 | WebView APK v5.0 — XHR conversion, tabletKey proxy, heartbeat monitoring, Worker ROPC client_secret fix |
| 2026-07-02 | v3.10.67 | Canvas alpha rounding for Chromium 30 |
| 2026-07-02 | v3.10.68–69 | Book Room button position fix |
| 2026-07-02 | v3.10.70 | Reduced Worker polling — fetchCal 5min, pollCommand 2min |
