# RIS Room Display

Meeting room kiosk display and mobile dashboard for the RIS floor at Central Silom Tower, Bangkok. Manages 12 coffee-themed room mailboxes via Microsoft Graph API, deployed on LG 10SM3TB tablets (Android 4.4.2) via WebView APK and modern devices via browser.

**Live URLs:**
- Kiosk (via Cloudflare): `https://ris-display.ris-display.workers.dev/`
- Dashboard: `https://ris-display.ris-display.workers.dev/dashboard.html`
- GitHub Pages (direct): `https://pom7220.github.io/Display-Room/` *(update after corporate repo transfer)*

**Current versions:** index v3.10.192 · boot-launcher APK v5.75 · cloudflare-worker (auto-deployed via GitHub Actions)

**Deployment status:** 6 of 12 tablets live (office zone — Macchiato, Viennese, Decaffinato, Latte, Mocha, Affogato). Macchiato, Viennese, Decaffinato, Mocha, and Affogato run Android 4.4.2 (LG 10SM3TB). Latte runs Android 10 (Lenovo-style tablet).

---

## Architecture

```
LG Tablet (Android 4.4.2)            iPhone/Surface (modern browser)
Boot Launcher APK v5.75                        │
        │                                      │
        │ loads Worker URL                     │
        │ ?tabletkey=...&webview=1             │
        ▼                                      ▼
  Cloudflare Worker              GitHub Pages (direct)
  - Serves index.html/JS         (Let's Encrypt cert)
  - ROPC auth proxy to Graph             │
  - Heartbeat & command API              │
  - Alarm event logging                  │
  - Weekly no-show email report          │
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

### index.html (v3.10.149) — Kiosk Display

**Language:** ES5 only. Runs on Chromium 30 (WebView APK) and Chrome 42 (browser). No `const`/`let`, arrows, template literals, `fetch()`, or CSS variables.

**Screens:**
1. **Config screen** — Room email/name, tablet key, auto-release timer, booking toggle, heartbeat interval, debug/kiosk toggles
2. **Auth overlay** — MSAL sign-in (browser mode only)
3. **Main app** — Header (room name, clock, weather), status strip (available/busy/soon/pending/no-show), current meeting card, meeting list, Book Room / Instant Booking button

**Key functions:**

| Function | Purpose |
|---|---|
| `initMsal()` | Creates MSAL v1 instance, handles redirect callback (browser mode only) |
| `fetchCal()` | Fetches room calendar via Worker proxy — XHR, every 2 minutes |
| `updateCurrentCard()` | Renders current meeting card; checks `ris_ended_early` localStorage for released-early state; handles auto-release, early check-in, end/extend, PENDING state |
| `renderMeetList()` | Today/tomorrow meeting list with upcoming indicator |
| `enterKiosk()` | Fullscreen — skips tap overlay if `webview=1` param or `ris_from_reload` sessionStorage flag |
| `openModal()` / `confirmBooking()` | Instant booking: email + title, conflict check, starts at next free slot |
| `doCheckin()` | Check-in — clears pending no-show incident on late check-in |
| `endMeetingEarly()` | Sets `ris_ended_early` localStorage, resets check-in, calls `updateCurrentCard()` — no dialog, no Exchange cancellation |
| `extendMeeting()` | Extends current meeting by 30 minutes |
| `sendHeartbeat()` | Posts tablet status to Worker `/api/heartbeat` every 20 min. Worker only writes to KV if status/version changed or >55 min elapsed (saves KV write quota) |
| `pollCommand()` | Polls Worker `/api/command` every 2 min for remote admin commands |
| `fetchWeather()` | XHR from open-meteo.com — static Bangkok fallback if unavailable |
| `setEndedEarly(id,room)` / `getEndedEarly()` / `clearEndedEarly()` | localStorage helpers for End Early state — keyed by meetingId + room email |
| `_trackQRTap()` / `_qrPruneCounts()` | Tracks QR button taps per day in `ris_qr_daily` localStorage; prunes entries >30 days old; avg computed over elapsed calendar days |

**End Early flow:**
```
User taps "End early" (ghost button below +30 min)
→ endMeetingEarly() → setEndedEarly(meetingId, room) → updateCurrentCard()
→ Card turns green: "RELEASED" tag + meeting details + "✓ Room released early · Available now"
→ Buttons hidden. State survives reload. Auto-clears at scheduled meeting end time.
→ Exchange booking NOT cancelled — room stays in Outlook calendar.
```

**No-Show flow:**
```
Meeting started → no check-in within releaseMin (default 10 min)
→ Amber card with blinking "NO SHOW" label (black text)
→ Countdown: "Check in NOW, otherwise released in MM:SS"
→ On expiry: meeting removed from local list, reportNoShow() fires
→ Check-in after no-show: resolves incident as 'late_checkin'
```

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

### dashboard.html — Mobile Dashboard

**Language:** ES6 (modern browsers only — not used on LG tablets).

**Layout:** Two-zone grid (Lobby 1–6, Office 7–12), tiles with status dot + badge, detail card with book/request button, schedule popup, ADMIN panel.

**Key functions:**

| Function | Purpose |
|---|---|
| `fetchAllRooms()` | Parallel fetch of all room calendars from MS Graph, 60-second auto-refresh |
| `getStatusProps(room)` | Maps `deriveRoomStatus()` result to UI — dot colour, badge text, info string |
| `showSchedule()` | Schedule popup — all-day meetings shown as blue banner, timed as list |
| `confirmBooking()` | Resource booking with conflict check before POST to Worker |
| `refreshAdmin()` | Fetches `/api/status` (heartbeat data), renders per-room health cards, smart action buttons |

**Admin panel features:**
- Per-tablet: online status, last seen time, version, APK version, launch mode, recent alarm log
- Remote commands: Reload, Force Fullscreen, Auto-tap, Reauth per room or bulk
- Smart actions: auto-detect offline, needs-fullscreen, needs-reload conditions
- Incident log: no-show events with resolution status
- QR stats: avg taps/day across rooms (30-day rolling window)
- Weekly report trigger: manual POST to `/api/noshow/send-report`

---

### ris-shared.js (v1.4) — Shared Logic

**Language:** ES5 only. Loaded by both `index.html` and `dashboard.html`.

| Symbol | Purpose |
|---|---|
| `RIS_ROOMS` | Array of 12 room definitions (num, name, email, seats, zone, approval) |
| `gDate(o)` | Parses Graph API dateTime — handles UTC, Z-suffix, bare local (Chrome 42 safe) |
| `isAllDay(m)` | True if meeting duration ≥ 86,399,000ms |
| `mapEvent(ev)` | Maps Graph event → internal object |
| `filterMeetings(evArr)` | Maps, filters cancelled, sorts by start |
| `deriveRoomStatus(meetings)` | Returns `{status, cur, nxt, freeUntil, allDayCur}`. Status: avail/busy/soon/pending |
| `fetchCalendarForRoom(...)` | XHR Promise — Worker proxy if tabletKey, else direct Graph |

---

### cloudflare-worker.js — Cloudflare Worker

**Auto-deployed** via GitHub Actions on every push to `main` that touches `cloudflare-worker.js` or `wrangler.toml`. No manual copy/paste needed.

**Roles:**
1. Transparent proxy to GitHub Pages through DigiCert certificate (trusted by Android 4.4.2)
2. ROPC auth proxy — fetches Microsoft Graph tokens for tablets via service account
3. Graph token caching — module-level `_cachedToken` / `_cachedTokenExpiry`, capped at 10 minutes, reduces login.microsoftonline.com subrequests
4. REST API — see routes below
5. Cron jobs — daily report at 20:00 BKK, weekly no-show email on Fridays at 18:00 BKK

**API Routes:**

| Route | Method | Purpose |
|---|---|---|
| `/api/calendar` | GET | Fetch room calendar via service account ROPC |
| `/api/book` | POST | Create calendar event (resource booking) |
| `/api/heartbeat` | POST | Tablet health report — conditional KV write (only if status changed or >55 min) |
| `/api/command` | GET | Tablet polls for pending admin command |
| `/api/command` | POST | Admin sends command to tablet |
| `/api/status` | GET | All room heartbeat records for dashboard admin panel |
| `/api/alarm` | POST | APK alarm events (standby/wake/restart) — stored in `alarm_log:{room}` KV key |
| `/api/incident` | POST | Report or resolve a no-show incident |
| `/api/incidents` | GET | Incident history with resolution tracking |
| `/api/noshow/send-report` | POST | Manual trigger for weekly no-show email |
| `/api/version` | GET | Latest APK version from `apk-version.json` |
| `/api/apk` | GET | APK binary download |

**KV design (optimised for free tier):**

| Key | TTL | Write frequency |
|---|---|---|
| `room:{email}` | 2 hours | ~1×/hour per room (conditional — only on status change or >55 min elapsed) |
| `cmd:{email}` | — | Written by admin, deleted after tablet reads |
| `alarm_log:{email}` | 7 days | 3×/day per room (standby/wake/restart alarms) |
| `incident:{id}` | 30 days | On each no-show event |
| `incidents_index` | — | On each no-show event |
| `report:{date}` | 90 days | Daily cron |

**KV usage estimate (12 tablets, free tier = 1,000 writes/day):**
- Heartbeat writes: ~288/day (12 rooms × 24 × 1/hr)
- Alarm writes: ~36/day
- Incidents: ~10/day
- **Total: ~334/day — well under 1,000 limit**

**Weekly no-show email:**
- Sent Fridays 18:00 BKK to `vorutchapon@central.co.th`
- Summary: total no-shows and late check-ins for the week
- Daily detail table: time, meeting title (28 char max), organizer (22 char max), room, status
- Excludes Instant Booking meetings, all-day events

---

### boot-launcher/ (v5.75) — Android APK

**Package:** `th.co.central.ris.bootlauncher`

**Components:**

| File | Purpose |
|---|---|
| `KioskWebViewActivity.java` | Fullscreen WebView — loads Worker URL with `tabletkey` + `webview=1`. Hides system nav bar. Portrait. Blocks back button. Registers alarm chain on every start. |
| `BootReceiver.java` | `BOOT_COMPLETED` → 90s WiFi settle → launches KioskWebViewActivity |
| `ScheduleReceiver.java` | Three daily alarms: standby 20:30, wake 07:30, restart 06:00. Each alarm reschedules itself +1 day. Logs event to Worker `/api/alarm`. Installs Conscrypt for TLS 1.2 on Android 4.4. |
| `MainActivity.java` | Setup screen — room email/name, saved to SharedPreferences |
| `UpdateChecker.java` | Polls Worker `/api/version` on boot — downloads and silently installs APK via `su -c "pm install -r"` if newer version found. Reads `pm install` stdout to confirm success on Android 4.4 (exit code alone is unreliable — `su` always exits 0 on LG). Falls back to manual install dialog if root unavailable. |
| `RestartReceiver.java` | Relaunches `KioskWebViewActivity` after silent OTA install via `MY_PACKAGE_REPLACED` broadcast. Uses a full-screen notification with `setFullScreenIntent()` on all Android versions to bypass Android 10 background activity launch restrictions. Registered in `AndroidManifest.xml`. |
| `ForegroundWatchService.java` | Watchdog — checks every 5 min if WebView is foreground; relaunches if not |

**Alarm chain (critical for daily restarts):**
```
KioskWebViewActivity.onCreate() → ScheduleReceiver.schedule()
→ Sets 3 exact alarms: standby (20:30), wake (07:30), restart (06:00)
→ Each alarm fires → logs to Worker → reschedules itself for next day
→ If chain breaks (alarm not received): open app manually to re-register via onCreate()
```

**Conscrypt / TLS 1.2 on Android 4.4:**
Android 4.4 system SSL cannot negotiate TLS 1.2 with Cloudflare. Conscrypt is installed at the start of every `logAlarmEvent()` background thread via:
```java
try { Security.insertProviderAt(Conscrypt.newProvider(), 1); } catch (Throwable ignored) {}
```
This covers alarms firing from cold process (no main app running).

**Boot flow:**
```
Tablet powers on → BootReceiver fires
→ 90s delay (WiFi settle)
→ KioskWebViewActivity launches → schedule() registers alarm chain
→ Loads Worker URL ?tabletkey=RIS-TABLET-KEY2026&webview=1&room=...
→ index.html loads → proxy mode → fetchCal → display shown
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

---

## Cloudflare Setup

**Worker name:** `ris-display`  
**KV namespace:** `RIS_KV` (ID: `cfa88e438ad9445087bf7fbcbe66dd21`)  
**Account ID:** stored in GitHub Secret `CLOUDFLARE_ACCOUNT_ID`

**Worker Secrets** (set in Cloudflare dashboard → Workers → `ris-display` → Settings → Variables):

| Secret | Purpose |
|---|---|
| `RIS_SVC_USER` | Service account email (`rismeetingroomsystem@central.co.th`) |
| `RIS_SVC_PASSWORD` | Service account password |
| `RIS_TENANT_ID` | Azure AD tenant ID |
| `RIS_CLIENT_ID` | Azure AD app client ID |
| `RIS_CLIENT_SECRET` | Azure AD app client secret |
| `RIS_TABLET_KEY` | Tablet auth key (`RIS-TABLET-KEY2026`) |
| `RIS_ADMIN_KEY` | Admin operations key — for dashboard remote commands |

**GitHub Secrets** (for CI/CD auto-deploy):

| Secret | Purpose |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare API token with Workers edit permissions |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare account ID |

---

## 12 Rooms

| # | Name | Email | Seats | Zone | Approval | Tablet |
|---|---|---|---|---|---|---|
| 1 | Espresso | risespresso@central.co.th | 8–12 | Lobby | No | Pending |
| 2 | Doppio | risdoppio@central.co.th | 6–8 | Lobby | No | Pending |
| 3 | Cappuccino | riscappuccino@central.co.th | 6 | Lobby | No | Pending |
| 4 | Americano | risamericano@central.co.th | 6 | Lobby | No | Pending |
| 5 | Lungo | rislungo@central.co.th | 4 | Lobby | No | Pending |
| 6 | Ristretto | risristretto@central.co.th | 4 | Lobby | No | Pending |
| 7 | Macchiato | rismacchiato@central.co.th | 5–8 | Office | **Yes** | ✅ Live |
| 8 | Viennese | risviennese@central.co.th | 6 | Office | No | ✅ Live |
| 9 | Decaffinato | risdecaffeinato@central.co.th | 6 | Office | No | ✅ Live |
| 10 | Latte | rislatte@central.co.th | 6 | Office | No | ✅ Live |
| 11 | Mocha | rismocha@central.co.th | 6 | Office | No | ✅ Live |
| 12 | Affogato | risaffogato@central.co.th | 6 | Office | No | ✅ Live |

---

## Calendar Logic

- **All-day events** — included in `cur` (room IS booked). Excluded from `nxt`. Excluded from meeting list.
- **`showAs:'free'`** — kept (room calendars return free via delegated access)
- **`showAs:'tentative'`** — non-approval rooms: treated as confirmed. Approval rooms only: shown as PENDING.
- **`isCancelled`** — filtered out entirely
- **Instant Booking** — when `organizerEmail === activeRoom.email` (room booked directly on room calendar or via tablet booking button), labeled "Instant Booking". Excluded from weekly no-show report.

---

## ES5 / Chromium 30 Constraints

All code in `index.html` and `ris-shared.js` must be ES5:

- **No:** `const`/`let`, arrow functions, template literals, `async`/`await`, optional chaining, `Set`/`Map`, `for...of`, `NodeList.forEach`, `.closest()`
- **No:** `fetch()` — use XHR for all network calls
- **No CSS:** `var(--custom)`, `inset:`, CSS Grid
- **Touch scrolling:** `-webkit-overflow-scrolling: touch`
- **Button text stacking:** use `<br>` inside buttons — `display:block` on `<span>` inside `<button>` does not work on Chromium 30

Pre-flight before every deploy:
```
grep -c "=>\|const \|let \|async \|\`" index.html ris-shared.js
# Must be 0
grep -c "fetch(" index.html ris-shared.js
# Must be 0
```

---

## Deploy Checklist

### index.html / ris-shared.js
1. Edit files
2. Bump `APP_VERSION.patch` and `APP_VERSION.date` in `index.html`
3. Update `index-version.json` to match
4. If `ris-shared.js` changed, bump `?v=` cache-bust on its `<script>` tag in `index.html`
5. Run ES5 pre-flight check
6. `git push` → GitHub Pages deploys automatically → tablets auto-update within 5 min

### cloudflare-worker.js
1. Edit `cloudflare-worker.js`
2. `git push` → **GitHub Actions auto-deploys to Cloudflare** (no manual paste needed)
3. Monitor Actions tab for deploy status

### Boot Launcher APK
1. Edit files under `boot-launcher/`
2. Bump `versionCode` + `versionName` in `boot-launcher/app/build.gradle`
3. Update `apk-version.json` to match
4. `git push` → GitHub Actions builds APK and commits `ris-boot-launcher.apk` to repo root
5. Download `ris-boot-launcher.apk` from repo root
6. Sideload on each tablet via File Manager or `adb install -r ris-boot-launcher.apk`

APK signing password: `a0000`

---

## Troubleshooting

### Tablet not restarting at 06:00
The alarm chain is self-scheduling — each alarm reschedules itself when it fires. If the chain breaks (e.g., tablet was off during alarm time), open the app manually on the tablet. `KioskWebViewActivity.onCreate()` calls `ScheduleReceiver.schedule()` which re-registers all three alarms.

**Confirm via ADMIN panel:** check the `alarm_log` for that room — a `wake` event should appear at ~07:30 (after the wake alarm fires). Missing `wake` at 07:30 means the restart at 06:00 did not complete. Note: `boot_detected` is a separate incident type logged by `index.html` on every page load — it is informational and auto-resolves; it is NOT the alarm log.

### Heartbeat shows offline but tablet is running
Worker marks tablet offline if KV record is >70 min old. With the conditional write optimisation, records are written ~once/hour. If the tablet status/version hasn't changed and it's been <55 min since last write, the record keeps the previous timestamp. If the tablet genuinely stopped sending heartbeats, record expires after 2 hours (TTL).

### "APK ?" badge on dashboard
Shown when `cfg.apkVersion` is empty. Happens if WebView reloaded without the APK URL params (`?apkVersion=...`). Resolves automatically at the next daily 06:00 restart when the APK re-injects the param.

### Nav bar visible over kiosk app on LG tablets

The system nav bar reappears because a system overlay (e.g. a Superuser prompt, notification shade) dismissed without restoring the window focus that immersive mode requires. On Android 4.4, `setSystemUiVisibility()` is silently ignored when the window does not have focus. The quickest manual fix is to tap Recent Apps and reselect the kiosk. v5.74 adds a 300ms `postDelayed` in `OnSystemUiVisibilityChangeListener` so the app self-heals once focus is restored.

### KV writes hit 1,000/day limit

Each `debugStep()` call in the OTA debug flow triggers one KV PUT write. Clicking the "OTA Debug" button repeatedly in the dashboard can exhaust the free-tier limit of 1,000 writes/day (resets 00:00 UTC = 07:00 BKK). Do not click OTA Debug unless actively diagnosing an issue. Normal heartbeat writes are conditional (only if status/version changed or >55 min since last write) and amount to roughly 72 writes/day for 6 tablets, well under the limit.

### Duplicate heartbeat commands
Commands are delivered via both heartbeat response (every 20 min) and command poll (every 2 min). A 30-second dedup window in `executeCommand()` prevents the same command running twice.

---

## Corporate Handover

**GitHub (`Pom7220/Display-Room`):**
- Transfer repo to corporate GitHub org: Settings → Danger Zone → Transfer Ownership
- GitHub Pages and Actions follow automatically
- Update 2 GitHub Secrets: `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` (new corporate values)
- Update KV namespace `id` in `wrangler.toml` (new corporate KV namespace)

**Cloudflare:**
- Create new Worker + KV namespace under corporate account
- Re-enter all 7 Worker Secrets manually (Cloudflare does not export secret values)
- CI/CD will auto-deploy on next push once GitHub Secrets are updated

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
| 2026-06-12 | v3.10.15 | All-day booking fix, msal-v1 v1.3, dashboard v2.0, Cloudflare Worker, boot-launcher v1.2 |
| 2026-07-01 | v3.10.55–59 | Button layout, tap-free reload, sessionStorage kiosk flag |
| 2026-07-01 | v3.10.66 | WebView APK v5.0 — XHR, tabletKey proxy, heartbeat, Worker ROPC fix |
| 2026-07-02 | v3.10.67–93 | Canvas alpha rounding, booking UX, weather fix, upcoming list, More+ |
| 2026-07-03 | v3.10.101–110 | Instant booking organizer, auto-release, early check-in, PENDING card |
| 2026-07-05 | v3.10.108–110 | PENDING card for approval rooms; tentative-as-confirmed |
| 2026-07-09 | APK v5.3x | Daily alarm chain: standby 20:30, wake 07:30, restart 06:00 via ScheduleReceiver |
| 2026-07-10 | — | FortiGate SSL inspection fix: Cloudflare IP whitelist rule for office network |
| 2026-07-13 | v3.10.140–143 | Weekly no-show email report with daily detail; Graph token caching (10 min cap) |
| 2026-07-13 | APK v5.41 | Alarm event logging to Worker `/api/alarm`; UpdateChecker Conscrypt fix |
| 2026-07-16 | APK v5.42 | Conscrypt in `logAlarmEvent()` — TLS 1.2 on cold process for Android 4.4 |
| 2026-07-16 | v3.10.144 | End Early UX: ghost button, green released card, `ris_ended_early` localStorage |
| 2026-07-16 | v3.10.145 | NO SHOW label black text for contrast on amber card |
| 2026-07-16 | v3.10.146 | +30 button `<br>` fix for Chromium 30; End early button contrast improved |
| 2026-07-16 | v3.10.147 | QR avg/day: elapsed calendar days denominator + 30-day rolling prune |
| 2026-07-16 | v3.10.149 | Worker: conditional KV write (save quota); CI/CD: auto-deploy via wrangler-action |
| 2026-08-17 | APK v5.57 | Weekend health check guard added; ACTION_RESTART weekend guard added; StandbyActivity Conscrypt fixed |
| 2026-08-19 | APK v5.58 | StandbyActivity overnight heartbeat fix — OkHttpClient now built after Conscrypt install |
| 2026-08-xx | APK v5.68 | OTA silentInstall introduced — broke on Android 4.4 due to `cp` step in `su` chain (bootstrap problem version) |
| 2026-08-xx | APK v5.71 | OTA dashboard "Update All" deployed |
| 2026-08-28 | APK v5.72 | `RestartReceiver` added — listens for `MY_PACKAGE_REPLACED` to relaunch kiosk after silent OTA install; registered in AndroidManifest.xml |
| 2026-08-29 | APK v5.73 | Capture `pm install` stdout to detect false-positive `6_su_ok` on LG Android 4.4 (`su` exits 0 regardless of install result; stdout now read for "Success"/"Failure") |
| 2026-08-31 | APK v5.74 | `postDelayed(300ms)` before re-applying immersive flags in `OnSystemUiVisibilityChangeListener`; fixes nav bar reappearing on Android 4.4 after system overlay dismisses without restoring window focus |
| 2026-08-31 | APK v5.75 | `RestartReceiver` uses full-screen notification with `setFullScreenIntent()` instead of `startActivity()` directly; bypasses Android 10 background activity launch restrictions; works on Android 4.4 too |
