# RIS Room Display

Meeting room kiosk display and mobile dashboard for the RIS floor at Central Silom Tower, Bangkok. Manages 12 coffee-themed room mailboxes via Microsoft Graph API, deployed on LG 10SM3TB tablets (Android 4.4.2, Chrome 42) and modern devices.

**Live URLs:**
- Kiosk (via Cloudflare): `https://ris-display.ris-display.workers.dev/`
- Kiosk (direct): `https://pom7220.github.io/Display-Room/`
- Dashboard: `https://pom7220.github.io/Display-Room/dashboard.html`

---

## Architecture

```
LG Tablet (Chrome 42)          iPhone/Surface (modern browser)
        │                                │
        ▼                                ▼
  Cloudflare Worker              GitHub Pages (direct)
  (DigiCert cert, trusted        (Let's Encrypt cert)
   by Android 4.4 natively)              │
        │                                │
        └──── proxies to ───────►  GitHub Pages
                                         │
                                    ┌────┴────┐
                                    ▼         ▼
                              index.html  dashboard.html
                                    │         │
                                    └────┬────┘
                                         ▼
                                   ris-shared.js
                                         │
                                         ▼
                              Microsoft Graph API
                              (room calendar data)
```

**Auth flow:** OAuth2 implicit flow via custom `msal-v1.min.js` (ES5, Chrome 42 compatible). Tokens renewed silently via hidden iframe — no user interaction for 24+ hours.

---

## File Reference

| File | Version | Purpose |
|---|---|---|
| `index.html` | v3.10.15 | **Kiosk display** — ES5 only, runs on Chrome 42. Room status, meeting card, check-in, booking, 7-day schedule, weather, fullscreen kiosk lock with PIN |
| `dashboard.html` | v2.0 | **Mobile dashboard** — ES6, modern browsers. Bird's-eye view of all 12 rooms, tap to book, schedule popups |
| `ris-shared.js` | v1.2 | **Shared logic** — ES5 only. Room definitions (12 rooms), calendar parsing (`gDate`, `mapEvent`, `filterMeetings`), status derivation (`deriveRoomStatus`), Graph API fetcher. Single source of truth for both apps |
| `msal-v1.min.js` | v1.3 | **Auth library** — ES5, custom-built MSAL v1 wrapper. Implicit flow, hidden iframe silent token renewal, `prompt`/`loginHint` support. Replaces MSAL v3 which requires ES6 |
| `sw.js` | ris-v3.10.15 | **Service worker** — Network-first caching. No `skipWaiting`/`clients.claim` (prevents mid-session disruption). Bump `CACHE_VERSION` on every deploy |
| `manifest.json` | — | **PWA manifest** — `display: fullscreen`, portrait orientation. Enables "Add to Home Screen" standalone mode |
| `icon-192.png` | — | PWA icon 192×192 |
| `icon-512.png` | — | PWA icon 512×512 |
| `boot-launcher/` | v1.2 | **Android APK project** — Auto-launches Chrome on tablet reboot. Listens for `BOOT_COMPLETED`, waits 10s for WiFi, opens Cloudflare Worker URL |

---

## Azure AD Configuration

| Setting | Value |
|---|---|
| Tenant ID | `817e531d-191b-4cf5-8812-f0061d89b53d` |
| Client ID | `80648895-4acf-4ac5-b4a3-c5bf6bc98983` |
| App name | RIS OAuth Meeting Room Kiosk |
| Auth method | Implicit flow (Access tokens + ID tokens enabled) |
| Service account | `rismeetingroomsystem@central.co.th` (no MFA) |
| Delegated scopes | `Calendars.ReadWrite`, `Calendars.ReadWrite.Shared`, `Mail.Send`, `User.Read` |
| Redirect URIs (SPA) | `https://pom7220.github.io/Display-Room/index.html`, `https://pom7220.github.io/Display-Room/dashboard.html`, `https://ris-display.ris-display.workers.dev/index.html` |

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

## Chrome 42 / ES5 Constraints

All code in `index.html`, `ris-shared.js`, and `msal-v1.min.js` must be ES5 only:

- **No:** `const`/`let`, arrow functions (`=>`), template literals, `async`/`await`, optional chaining (`?.`), `Set`/`Map`, `for...of`, `NodeList.forEach`, `.closest()`
- **No CSS:** `var(--custom)`, `inset:`, CSS Grid
- **Touch scrolling:** Requires `-webkit-overflow-scrolling: touch`
- **String methods:** `startsWith`/`endsWith` require polyfills (included in index.html)

Pre-flight check before every deploy:
```
grep -c "=>\|const \|let \|async \|\`" index.html ris-shared.js msal-v1.min.js
# All must be 0
```

---

## Deploy Checklist

1. Edit files as needed
2. Bump `APP_VERSION.patch` and `APP_VERSION.date` in `index.html`
3. Bump `CACHE_VERSION` in `sw.js` to match (e.g., `ris-v3.10.16`)
4. Bump `?v=` cache-bust parameter on `<script src="ris-shared.js?v=12">` in both `index.html` and `dashboard.html` if `ris-shared.js` changed
5. Run the ES5 pre-flight check above
6. Push to GitHub — Pages deploys automatically
7. LG tablets auto-update within 5 minutes (inactivity relaunch fetches fresh files)

---

## Boot Launcher APK

Built via GitHub Actions (`.github/workflows/build-apk.yml`):

1. Push changes to `boot-launcher/` → Actions builds automatically
2. Download APK from Actions → Artifacts → `ris-boot-launcher`
3. Copy to USB → install on LG tablet from File Manager

APK signing password: `a0000`

---

## Cloudflare Worker

Proxy at `https://ris-display.ris-display.workers.dev/` — forwards all requests to GitHub Pages through Cloudflare's DigiCert certificate, which Android 4.4.2 trusts natively. Eliminates the SSL certificate warning on LG tablets.

Worker location: Cloudflare dashboard → Compute → Workers → `ris-display`

Free tier: 100,000 requests/day (more than sufficient for 12 tablets refreshing every 90 seconds).
