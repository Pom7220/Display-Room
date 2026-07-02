# RIS Room Display

Meeting room kiosk display and mobile dashboard for the RIS floor at Central Silom Tower, Bangkok. Manages 12 coffee-themed room mailboxes via Microsoft Graph API, deployed on LG 10SM3TB tablets (Android 4.4.2) via WebView APK and modern devices via browser.

**Live URLs:**
- Kiosk (via Cloudflare): `https://ris-display.ris-display.workers.dev/`
- Kiosk (direct GitHub Pages): `https://pom7220.github.io/Display-Room/`
- Dashboard: `https://pom7220.github.io/Display-Room/dashboard.html`

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
| **Browser / Dashboard** | iPhone, Surface, PC | OAuth2 implicit flow via `msal-v1.min.js` (ES5, Chrome 42 compatible) — tokens renewed silently via hidden iframe |

---

## File Reference

| File | Version | Purpose |
|---|---|---|
| `index.html` | v3.10.70 | **Kiosk display** — ES5 only, runs on Chromium 30 (WebView) and Chrome 42. Room status, meeting card, check-in, booking, 7-day schedule, weather, heartbeat monitoring, remote command polling |
| `dashboard.html` | v2.0 | **Mobile dashboard** — ES6, modern browsers. Bird's-eye view of all 12 rooms, tap to book, schedule popups, remote reload/command |
| `ris-shared.js` | v1.4 | **Shared logic** — ES5 only. Room definitions (12 rooms), calendar parsing, status derivation, Graph API fetcher (XHR-based, no `fetch()`) |
| `cloudflare-worker.js` | — | **Cloudflare Worker source** — deploy via Cloudflare dashboard. Handles ROPC auth proxy, calendar/booking API, heartbeat, command polling |
| `msal-v1.min.js` | v1.3 | **Auth library** — ES5, browser mode only. Implicit flow, silent token renewal via hidden iframe |
| `sw.js` | ris-v3.10.15 | **Service worker** — Network-first caching for browser/PWA mode. Not used by WebView APK (`LOAD_NO_CACHE`) |
| `manifest.json` | — | **PWA manifest** — for browser "Add to Home Screen" mode |
| `boot-launcher/` | v5.0 | **Android APK project** — Fullscreen WebView kiosk. Loads Worker URL with `tabletkey` + `webview=1` params. No Chrome, no MSAL. Hides system nav bar (immersive sticky). Portrait orientation. Auto-launches on boot. |

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
| Redirect URIs (SPA) | `https://pom7220.github.io/Display-Room/index.html`, `https://pom7220.github.io/Display-Room/dashboard.html`, `https://ris-display.ris-display.workers.dev/index.html` |

---

## Cloudflare Worker

Worker: `ris-display` at `https://ris-display.ris-display.workers.dev/`

**Roles:**
1. Serves `index.html` / `ris-shared.js` through DigiCert certificate (trusted by Android 4.4.2)
2. ROPC auth proxy — fetches Microsoft Graph tokens for tablets via service account
3. REST API for tablets: `/api/calendar`, `/api/book`, `/api/heartbeat`, `/api/poll`, `/api/status`

**Required secrets** (set in Cloudflare dashboard → Worker → Settings → Variables):

| Secret | Purpose |
|---|---|
| `RIS_SVC_USER` | Service account email (`rismeetingroomsystem@central.co.th`) |
| `RIS_SVC_PASSWORD` | Service account password |
| `RIS_TENANT_ID` | Azure AD tenant ID |
| `RIS_CLIENT_ID` | Azure AD app client ID |
| `RIS_CLIENT_SECRET` | Azure AD app client secret |
| `RIS_TABLET_KEY` | Tablet auth key (`RIS-TABLET-KEY2026`) — sent as `X-Tablet-Key` header |
| `RIS_ADMIN_KEY` | Admin operations key — for dashboard remote commands |

**Free tier:** 100,000 requests/day. Expected usage at 12 tablets: ~13,000/day (fetchCal every 5min, pollCommand every 2min, heartbeat every 20min).

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

## Chrome 42 / Chromium 30 / ES5 Constraints

All code in `index.html` and `ris-shared.js` must be ES5 only. WebView APK uses Chromium 30 (Android 4.4.2 system WebView) which has additional restrictions:

- **No:** `const`/`let`, arrow functions (`=>`), template literals, `async`/`await`, optional chaining (`?.`), `Set`/`Map`, `for...of`, `NodeList.forEach`, `.closest()`
- **No:** `fetch()` API — use XHR (`XMLHttpRequest`) for all network calls
- **No CSS:** `var(--custom)`, `inset:`, CSS Grid
- **Touch scrolling:** Requires `-webkit-overflow-scrolling: touch`
- **Canvas:** Round float values to 4 decimal places before passing to `addColorStop()` — Chromium 30 rejects high-precision floats

Pre-flight check before every deploy:
```
grep -c "=>\|const \|let \|async \|\`" index.html ris-shared.js
# Must be 0
grep -c "fetch(" index.html ris-shared.js
# Must be 0 (sendMail is the only exception — unreachable in WebView/tabletKey mode)
```

---

## Deploy Checklist

### index.html / ris-shared.js
1. Edit files as needed
2. Bump `APP_VERSION.patch` and `APP_VERSION.date` in `index.html`
3. If `ris-shared.js` changed, bump `?v=` cache-bust on `<script src="ris-shared.js?v=14">` in `index.html`
4. Run ES5 pre-flight check above
5. Push to `main` — GitHub Pages deploys automatically
6. Tablets auto-update within 5 minutes via `checkForNewVersion` polling

### cloudflare-worker.js
1. Edit `cloudflare-worker.js`
2. Go to Cloudflare dashboard → Workers → `ris-display` → Edit
3. Paste updated worker code → Deploy

### Boot Launcher APK
1. Edit files under `boot-launcher/`
2. Bump `versionCode` and `versionName` in `boot-launcher/app/build.gradle`
3. Push to `main` → GitHub Actions builds APK automatically
4. Download from Actions → Artifacts → `ris-boot-launcher`
5. Install on tablet via USB (`adb install -r app-debug.apk`) or sideload from File Manager

APK signing password: `a0000`

---

## Corporate Handover

To transfer ownership from personal account to corporate:

**GitHub:**
- Transfer repo `Pom7220/Display-Room` to corporate GitHub org (Settings → Danger Zone → Transfer)
- GitHub Pages and Actions follow automatically
- Revoke personal fine-grained token; generate new one under corporate account

**Cloudflare:**
- Add corporate admin to Cloudflare account, OR recreate Worker under corporate account
- Worker code: paste `cloudflare-worker.js` from repo
- Re-enter all 7 secrets manually (Cloudflare does not export secret values)

**Azure AD:** Already under `central.co.th` tenant — no transfer needed.
