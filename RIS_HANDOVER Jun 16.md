# RIS Room Display — Technical Handover

**Last updated:** 2026-06-13 | **Current versions:** index v3.10.15, dashboard v2.0, ris-shared v1.2, msal-v1 v1.3, sw ris-v3.10.15, boot-launcher v1.2

---

## 1. Project Overview

Web-based meeting room kiosk + mobile dashboard for Central Group RIS floor, Central Silom Tower Bangkok. 12 coffee-named rooms displayed on LG 10SM3TB wall-mounted tablets and accessible via mobile dashboard.

**Target hardware:**
- LG 10SM3TB tablets — Android 4.4.2, Chrome 42 (kiosk display)
- Lenovo tablet — modern Android (test device)
- iPhone / Surface Pro — staff mobile (dashboard)

---

## 2. URLs and Hosting

| URL | Purpose | Certificate |
|---|---|---|
| `https://ris-display.ris-display.workers.dev/` | **Production kiosk** (LG tablets) | DigiCert via Cloudflare — trusted by Android 4.4.2 |
| `https://pom7220.github.io/Display-Room/` | Source hosting / modern devices | Let's Encrypt (ISRG Root X1) — NOT trusted by Android 4.4.2 |
| `https://pom7220.github.io/Display-Room/dashboard.html` | Staff dashboard | Let's Encrypt |

The Cloudflare Worker is a transparent proxy. Files are hosted on GitHub Pages; the Worker adds a trusted certificate chain for old Android devices.

---

## 3. File Specifications

### index.html (v3.10.15) — Kiosk Display

**Language:** ES5 only (Chrome 42 constraint). No const/let, arrows, template literals, async/await, optional chaining, CSS variables.

**Screens:**
1. **Config screen** — Tenant ID, Client ID, room name/email (pre-filled), auto-release timer, booking toggle, approval toggle, owner email, additional rooms for quick-switching, debug/kiosk toggles, SSL cert helper
2. **Auth overlay** — MSAL sign-in button, error display
3. **Main app** — Header (room name, clock, weather), status strip (available/busy/soon/pending), current meeting card (title, organiser, time, progress bar, check-in/end/extend buttons), meeting list (today/tomorrow/more+ tabs), book room modal

**Key functions:**
- `initMsal()` — Creates MSAL v1 instance, handles redirect callback, detects Chrome 42 for prompt strategy
- `getToken()` — Cached token with 2-minute validity window, falls back to `doReauth()`
- `doReauth()` — Clears MSAL cache, extracts loginHint from JWT, redirects with correct prompt per browser
- `getEmailFromJWT(token)` — Decodes access token JWT to get UPN/email (workaround for `account.username` being undefined on Chrome 42)
- `fetchCal()` — Fetches room calendar via Graph API every 90 seconds
- `updateCurrentCard()` — Renders current meeting or "Free to Book" state; handles all-day bookings ("Booked all day")
- `renderMeetList()` — Today/tomorrow meeting list with `isAllDay` filter
- `enterKiosk()` — Fullscreen management: PWA standalone auto-fullscreen, browser mode auto-fullscreen attempt (800ms delay), tap overlay fallback
- `doFullscreen()` — Cross-browser fullscreen API (webkit prefix for Chrome 42)
- `openModal()` / `confirmBooking()` — Booking flow with start time picker, duration chips, resource booking via Graph API
- `doCheckin()` / `endMeeting()` / `extendMeeting()` — Meeting actions with overlap protection

**Boot sequence:**
1. Iframe guard: if `window !== window.top`, only init MSAL for silent renewal postMessage — skip app
2. Normal: `loadCfg()` → `initMsal()` → `handleRedirectCallback` → `launch()` → `fetchCal()` + intervals

**Auto-relaunch:** 5 minutes inactivity → `location.href = baseURL + ?nocache=timestamp`

**Session keepalive:** `acquireTokenSilent` every 45 minutes. With v1.3 msal-v1.min.js, this triggers iframe renewal when token is near expiry — completely invisible.

---

### dashboard.html (v2.0) — Mobile Dashboard

**Language:** ES6 (modern browsers only — intentional, dashboard not used on LG tablets)

**Layout:** Two-zone grid (Lobby 1–6, In-office 7–12), 3-column tiles with status dots and badges, detail card with book/request button, schedule popup.

**Key functions:**
- `initMsal()` — MSAL v1 init with `_redirectInProgress` guard to prevent AADSTS50196 loop
- `getToken()` — Same guard, `prompt:'none'` for background reauth, `prompt:'select_account'` for manual sign-in tap
- `getEmailFromJWT(token)` — Same JWT decode as index.html
- `fetchAllRooms()` — Parallel fetch of all 12 room calendars, 60-second refresh
- `getStatusProps(room)` — Maps `deriveRoomStatus()` result to UI (dot color, badge, info text); handles `allDayCur` → "Booked all day"
- `showSchedule()` — Schedule popup splits all-day events (blue "All day" banner) from timed meetings
- `confirmBooking()` — Resource booking with conflict check before POST

**Boot sequence:** Same iframe guard as index.html.

---

### ris-shared.js (v1.2) — Shared Logic

**Language:** ES5 only. Loaded by both index.html and dashboard.html.

**Exported functions:**
- `RIS_ROOMS` — Array of 12 room definitions (num, name, email, seats, zone, approval)
- `gDate(o)` — Parses Graph API dateTime objects; handles UTC timezone, Z-suffix, and bare local time (manual component parsing for Chrome 42 cross-browser safety)
- `isAllDay(m)` — Returns true if meeting duration ≥ 86,399,000ms (23h 59m 59s)
- `mapEvent(ev)` — Maps Graph API event to internal meeting object (id, title, organizer, start, end, attendees, joinUrl, isOnline, showAs, isCancelled, isPending)
- `filterMeetings(evArr)` — Maps, filters cancelled, sorts by start time
- `deriveRoomStatus(meetings)` — Returns `{status, cur, nxt, pendingCur, freeUntil, allDayCur}`. Status: avail/busy/soon/pending. All-day events included in `cur` (room IS booked). Pending (tentative) excluded from cur/nxt on kiosk, shown separately
- `fetchCalendarForRoom(email, tok, startISO, endISO)` — Promise-based Graph API fetch with filterMeetings
- `sharedFmtDur(mins)` — "3h 45m" formatter
- `sharedFt(d)` — "HH:MM" formatter with padStart fallback

---

### msal-v1.min.js (v1.3) — Authentication Library

**Language:** ES5 only. Custom-built replacement for MSAL v3 (which requires ES6 and crashes Chrome 42).

**Key design:**
- OAuth2 **implicit flow** with Microsoft Identity Platform v2.0 endpoint
- Tokens stored in localStorage with expiry tracking
- `loginRedirect()` honours `request.prompt` and `request.loginHint` (v1.2 fix — previously hardcoded `select_account`)
- `acquireTokenSilent()` first checks cached token, then attempts **hidden iframe renewal** (v1.3 — the core silent renewal mechanism)
- `_processRedirectIfRequired()` detects iframe context (`window !== window.top`) and posts hash to parent via `postMessage` instead of processing locally
- `_renewViaIframe()` creates a hidden 0×0 iframe, navigates to Azure AD with `prompt=none`, listens for postMessage response, parses token from hash. Deduplication via `_iframeRenewalInProgress` flag. 15-second timeout.
- `clearCache()` / `getAllAccounts()` — compatibility with index.html `doReauth()`

**Token lifecycle:**
```
Sign in → token stored (expires ~60 min)
  → 45-min keepalive → acquireTokenSilent → token still valid → OK
  → ~55 min → acquireTokenSilent → expired → iframe renewal
    → hidden iframe loads Azure AD with prompt=none
    → Azure session cookie alive → new token returned invisibly
    → token stored → next 60 min covered
  → If iframe fails (session expired) → doReauth → loginRedirect
```

**Silent renewal works for:** 24 hours (default Azure session) or 90 days (with IT-configured persistent browser session).

---

### sw.js (ris-v3.10.15) — Service Worker

**Strategy:** Network-first. Fetches from network; if successful, caches response. If offline, serves from cache.

**Critical design decisions:**
- **No `skipWaiting()`** — Prevents mid-session disruption (new SW waits for page close/reopen)
- **No `clients.claim()`** — Avoids taking over live sessions
- `CACHE_VERSION` must be bumped on every deploy — triggers old cache deletion on next reload

---

### manifest.json — PWA Manifest

`display: fullscreen` enables standalone PWA mode when launched from home screen icon. `orientation: portrait` locks to portrait for wall-mounted tablets.

---

### boot-launcher/ (v1.2) — Android APK

**Package:** `th.co.central.ris.bootlauncher`

**Components:**
- `BootReceiver.java` — BroadcastReceiver for `BOOT_COMPLETED` and `QUICKBOOT_POWERON`. Spawns thread, waits 10 seconds (WiFi settle), launches Chrome to Cloudflare Worker URL with cache-bust parameter
- `MainActivity.java` — Minimal launcher activity, shows toast "installed", closes immediately

**Build:** GitHub Actions (`.github/workflows/build-apk.yml`) — JDK 11, Gradle 7.2, AGP 7.0.4, compileSdkVersion 29, minSdkVersion 19. APK signing password: `a0000`

**Daily boot flow:**
```
8:00 AM reboot → BootReceiver fires
→ 10s delay → Chrome opens ris-display.ris-display.workers.dev
→ No SSL warning (Cloudflare DigiCert, trusted natively)
→ Token from localStorage → iframe renewal if near expiry
→ Room display shows → auto-fullscreen attempt after 800ms
```

---

## 4. Key Technical Decisions

### Calendar status logic
- All-day events: included in `cur` (room IS booked all day). Excluded from `nxt` (don't trigger "coming soon"). Excluded from meeting list (shown at card level instead)
- `showAs:'free'` kept (room calendars return free via delegated access — filtering would drop everything)
- `showAs:'tentative'` = `isPending` = pending approval. Excluded from kiosk cur/nxt, shown in dashboard
- `isCancelled` filtered out entirely

### Booking approach
- `POST /users/{roomEmail}/events` with room as `type:'resource'` attendee
- Requester added as `type:'required'` attendee → receives invitation email
- Exchange auto-accepts/declines based on availability
- Macchiato: approval flow sends email to room owner, shows pending badge

### Chrome 42 auth workarounds
- `account.username` returns `undefined` → use `getEmailFromJWT()` to decode JWT claims directly
- `prompt:'none'` was ignored by old msal-v1.min.js → fixed in v1.2 (honours request.prompt)
- Third-party cookie blocking → iframe renewal uses full-page redirect within iframe, first-party cookies work
- Chrome ≤45 detection: `prompt:'login'` + `loginHint` (fallback if iframe renewal fails)

### Cloudflare Worker
- Transparent proxy: all requests forwarded to `pom7220.github.io/Display-Room/` with same path
- Adds DigiCert certificate chain (trusted by Android 4.4.2 — eliminates SSL warning)
- Free tier: 100,000 requests/day
- Worker location: Cloudflare dashboard → Compute → Workers → `ris-display`

---

## 5. Backlog

1. **IT admin: Persistent browser session** — Azure AD Conditional Access → set on `rismeetingroomsystem@central.co.th`. Extends silent renewal from 24 hours to 90 days. Without this, tablets require sign-in after every daily reboot because Azure session cookies are lost on Chrome close. This is the final piece for fully unattended operation.
2. **Screen lock removal on LG tablets** — Cert installation forced a PIN. Need: Settings → Security → Clear credentials → then Screen lock → None
3. **Fullscreen on boot** — Auto-fullscreen in browser mode added (800ms delay). Needs verification on LG after next reboot — does Chrome 42 accept `requestFullscreen` from programmatic navigation?
4. **Dashboard via Cloudflare** — Register `https://ris-display.ris-display.workers.dev/dashboard.html` as additional redirect URI if dashboard will be used from old devices
5. **Clean up `apk/` folder** — Remove from repo (superseded by `boot-launcher/`)

---

## 6. LG Tablet Maintenance Notes

### Chrome update nag
Chrome 42 is the last version supporting Android 4.4. Chrome shows an "Update available" banner that does nothing (Play Store has no compatible newer version). To suppress:
- **Settings → Apps → Google Play Store → Disable** — stops update checks entirely. Kiosk tablets don't need Play Store.
- Optionally: **Settings → Apps → Google Play services → Notifications → uncheck**
- In fullscreen kiosk mode the banner is hidden — only visible during the brief seconds between Chrome opening and fullscreen activating.

### Login prompt after daily reboot
The Azure AD session cookie is lost when Chrome closes during the tablet's scheduled shutdown. Without the Persistent browser session policy (backlog item #1), someone must sign in once after each reboot. With the policy set, the cookie persists across reboots and iframe renewal handles token refresh silently.

### Chrome 42 cannot be updated
Android 4.4.2 (API 19) is end-of-life. Chrome 42 is the last compatible version. This is why all code must remain ES5 and why the custom `msal-v1.min.js` exists instead of the official MSAL library.

---

## 6. Version History

| Date | Version | Changes |
|---|---|---|
| 2026-05-26 | v1.0 | Initial release — MSAL v3, basic calendar display |
| 2026-05-27 | v1.1–1.2 | ES6→ES5 conversion, custom MSAL v1 |
| 2026-06-08 | v3.6.1 | doReauth token expiry fix |
| 2026-06-10 | v3.10.9 | Chrome 42 prompt detection (prompt:login + loginHint) |
| 2026-06-11 | v3.10.14 | JWT loginHint decode, auto-fullscreen layers, session keepalive |
| 2026-06-12 | v3.10.15 | **All-day booking fix** (shows IN USE), **msal-v1 v1.3** (iframe silent renewal), **dashboard v2.0** (auth loop fix + all-day fix), **Cloudflare Worker** proxy, boot launcher v1.2, auto-fullscreen in browser mode |
