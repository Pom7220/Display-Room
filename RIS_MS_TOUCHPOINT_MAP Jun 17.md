# RIS Room Display — Microsoft-Specific Touchpoint Map

**Purpose:** Reference document for future calendar provider migration. Maps every Microsoft-dependent code path so a developer can identify exactly what changes when switching from Microsoft 365 to another provider (Google Workspace, CalDAV, ONLYOFFICE, etc.).

**Key finding:** Only **6 out of 88 functions** (7%) in index.html are Microsoft-specific. The vast majority of the codebase (UI, rendering, kiosk logic, status derivation) is provider-agnostic and requires zero changes.

---

## Summary: What's Microsoft-specific vs provider-agnostic

```
PROVIDER-AGNOSTIC (no changes needed)          MICROSOFT-SPECIFIC (must change)
─────────────────────────────────               ────────────────────────────────
UI rendering & layout                           Auth library (msal-v1.min.js)
Clock, weather widget                           Auth flow (initMsal, getToken, doReauth)
Room status derivation                          Calendar fetch (Graph API URL + headers)
Meeting list & badge rendering                  Event field mapping (mapEvent)
Check-in, end, extend logic                     Booking (POST event + attendee model)
Fullscreen & kiosk mode                         Approval email (sendMail API)
Config screen (except auth fields)              Token format (JWT decode for loginHint)
Burn-in prevention                              OAuth scopes
Debug overlay                                   Tenant/Client IDs
Room switching                                  login.microsoftonline.com URLs
Auto-relaunch                                   
Service worker
PWA manifest
Boot launcher APK
```

---

## Layer 1: Auth Library — FULL REPLACEMENT

### File: `msal-v1.min.js` (366 lines)

**Verdict:** Replace entirely. This file IS the Microsoft auth implementation.

| Component | Microsoft (current) | Google equivalent | Generic OAuth2 |
|---|---|---|---|
| Library | Custom MSAL v1 wrapper | Google Identity Services (gsi) | Any OAuth2 library |
| Protocol | OAuth2 implicit flow | OAuth2 authorization code + PKCE | OAuth2 auth code + PKCE |
| Endpoint | `login.microsoftonline.com/oauth2/v2.0/authorize` | `accounts.google.com/o/oauth2/v2/auth` | Provider-specific |
| Token storage | localStorage (`msal.{clientId}.access_token`) | localStorage (custom) | localStorage (custom) |
| Silent renewal | Hidden iframe with `prompt=none` | Token refresh via `gsi.accounts.oauth2.revoke` | Refresh token rotation |
| Session cookie | `ESTSAUTH` / `ESTSAUTHPERSISTENT` | Google SID/SSID | Provider-specific |

**Migration action:** Write or adopt a new auth library for the target provider. The interface to match:
```javascript
// Functions the rest of the app calls — keep this interface stable
initAuth()                    // Initialize, handle redirect, set account
getToken()                    // Return valid access token (renew silently if needed)
doReauth()                    // Force re-authentication
getEmailFromToken(token)      // Extract user email from token
```

---

## Layer 2: Auth Flow — 6 FUNCTIONS TO REPLACE

### File: `index.html` — Microsoft-specific functions

| # | Function | Lines | What it does | What changes per provider |
|---|---|---|---|---|
| 1 | `initMsal()` | ~80 | Creates MSAL instance, authority URL, handles redirect callback, saves account | Library init, authority URL, callback handling |
| 2 | `getToken()` | ~30 | Calls `acquireTokenSilent`, caches in `_cachedToken` with 2-min validity | Token acquisition method changes |
| 3 | `doReauth()` | ~50 | Clears MSAL cache, extracts loginHint, calls `loginRedirect` with Chrome 42 prompt strategy | Cache clearing, redirect mechanism |
| 4 | `getEmailFromJWT(token)` | ~10 | Decodes JWT access token, reads `upn`/`unique_name`/`preferred_username` claims | JWT claim names differ per provider |
| 5 | `fetchCal()` | ~25 | Calls `fetchCalendarForRoom()` with token, date range | Only the token passing; API call is in ris-shared.js |
| 6 | `sendApproval()` | ~40 | Builds approval email HTML, calls `POST /me/sendMail` via Graph API | Email sending API changes entirely |

### File: `dashboard.html` — Microsoft-specific functions

| # | Function | What changes |
|---|---|---|
| 1 | `initMsal()` | Same as index.html — library init |
| 2 | `getToken()` | Same — token acquisition |
| 3 | `doSignIn()` | Login redirect call |
| 4 | `getEmailFromJWT(token)` | JWT claim names |
| 5 | `fetchRoom(room, tok)` | Only token passing; uses shared `fetchCalendarForRoom()` |
| 6 | `fetchAllRooms()` | Orchestrates fetchRoom for all 12 rooms |
| 7 | `confirmBooking()` | Booking API call + attendee model |
| 8 | `startDashboardKeepalive()` | Token renewal ping — mechanism changes |

---

## Layer 3: Calendar API — 1 FUNCTION + 1 URL

### File: `ris-shared.js` — `fetchCalendarForRoom()`

This is the **single most critical function** for provider migration. Everything flows through it.

```javascript
// CURRENT (Microsoft Graph API):
function fetchCalendarForRoom(email, tok, startISO, endISO) {
  var url = 'https://graph.microsoft.com/v1.0/users/'     // ← MS-specific endpoint
    + encodeURIComponent(email)
    + '/calendarView'                                       // ← MS-specific path
    + '?startDateTime=' + startISO                          // ← MS-specific param name
    + '&endDateTime=' + endISO                              // ← MS-specific param name
    + '&$select=subject,organizer,start,end,attendees,'     // ← MS-specific field names
    + 'onlineMeeting,isOnlineMeeting,showAs,isCancelled'
    + '&$orderby=start/dateTime&$top=60';                   // ← MS-specific OData syntax
  return fetch(url, {
    headers: { Authorization: 'Bearer ' + tok }             // ← Standard OAuth2 (same for all)
  })
  .then(function(r) { return r.json(); })
  .then(function(d) { return filterMeetings(d.value || []); });
}
```

**Google Calendar equivalent:**
```
GET https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events
  ?timeMin={startISO}&timeMax={endISO}&singleEvents=true&orderBy=startTime
```

**CalDAV equivalent:**
```
REPORT /calendars/{user}/{calendar}/ HTTP/1.1
Content-Type: application/xml
Body: <c:calendar-query> with <c:time-range start="..." end="..."/>
```

---

## Layer 4: Event Field Mapping — `mapEvent()`

### File: `ris-shared.js`

This function translates provider-specific event fields into the internal model used by all UI code. **Only this function needs to change per provider** — the internal model stays the same.

```
MICROSOFT GRAPH API FIELD          INTERNAL MODEL          GOOGLE CALENDAR FIELD
─────────────────────────          ──────────────          ─────────────────────
ev.subject                    →    title                ←  event.summary
ev.organizer.emailAddress.name →   organizer            ←  event.organizer.displayName
ev.start {dateTime, timeZone} →    start (Date)         ←  event.start.dateTime
ev.end {dateTime, timeZone}   →    end (Date)           ←  event.end.dateTime
ev.attendees[].length         →    attendees (count)    ←  event.attendees[].length
ev.onlineMeeting.joinUrl      →    joinUrl              ←  event.hangoutLink
ev.isOnlineMeeting            →    isOnline             ←  event.conferenceData != null
ev.showAs                     →    showAs               ←  event.transparency
ev.isCancelled                →    isCancelled          ←  event.status === 'cancelled'
ev.showAs === 'tentative'     →    isPending            ←  event.status === 'tentative'
ev.id                         →    id                   ←  event.id
```

**Key insight:** The internal model (`title`, `start`, `end`, `organizer`, etc.) is universal. Only the mapping FROM provider fields TO internal fields changes. All UI code downstream — `deriveRoomStatus()`, `updateCurrentCard()`, `renderMeetList()`, etc. — uses the internal model and needs zero changes.

---

## Layer 5: Booking — Event Creation

### File: `index.html` — booking body

```javascript
// CURRENT (Microsoft Graph API):
var body = {
  subject: title,                                    // ← 'subject' is MS term
  body: {contentType:'text', content:'...'},         // ← MS structure
  start: {dateTime: localISO(s), timeZone: tz},      // ← MS datetime format
  end: {dateTime: localISO(e), timeZone: tz},
  attendees: [
    {emailAddress:{address:room,name:name}, type:'resource'},   // ← MS room resource model
    {emailAddress:{address:email,name:name}, type:'required'}   // ← MS attendee model
  ],
  isOnlineMeeting: false
};
// POST to https://graph.microsoft.com/v1.0/users/{room}/events
```

**Google Calendar equivalent:**
```javascript
var body = {
  summary: title,
  description: '...',
  start: {dateTime: isoString, timeZone: tz},
  end: {dateTime: isoString, timeZone: tz},
  attendees: [{email: requesterEmail}]
};
// POST to https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events
```

**CalDAV equivalent:**
```
PUT /calendars/{user}/{calendar}/{uid}.ics
Content-Type: text/calendar
Body: BEGIN:VCALENDAR ... BEGIN:VEVENT ... END:VEVENT ... END:VCALENDAR
```

---

## Layer 6: Approval Email — `sendApproval()`

### File: `index.html`

Uses Microsoft Graph's `/me/sendMail` endpoint to send an HTML approval email. This is the most provider-specific feature — not all calendar systems have a built-in mail API.

```javascript
// CURRENT:
POST https://graph.microsoft.com/v1.0/me/sendMail
Body: {message: {subject, body:{contentType:'html',content}, toRecipients}, saveToSentItems:true}
```

**Migration options:**
- Google: Gmail API `POST /gmail/v1/users/me/messages/send`
- SMTP: Use a generic SMTP relay (provider-independent)
- Webhook: Send approval request to a Slack/Teams webhook
- Backend: Move email sending to a server-side function (recommended)

---

## Layer 7: Hardcoded Identifiers

### Locations of tenant/client/domain references (26 total across all files)

| Identifier | Where used | Count | Migration action |
|---|---|---|---|
| Tenant ID `817e531d-...` | index.html config defaults, URL param injection | 3 | Replace with new provider's tenant/project ID |
| Client ID `80648895-...` | index.html config defaults, URL param injection | 3 | Replace with new OAuth app ID |
| `@central.co.th` | ris-shared.js RIS_ROOMS, index.html config | 12 | Room identifiers — may change if new provider uses different addressing |
| `login.microsoftonline.com` | msal-v1.min.js, index.html initMsal | 4 | Replace with new provider's auth endpoint |
| `graph.microsoft.com` | ris-shared.js, index.html, dashboard.html | 4 | Replace with new provider's API endpoint |
| OAuth scopes | index.html, dashboard.html | 4 | `Calendars.ReadWrite` etc. → new provider's scope names |

---

## Migration Effort Estimate

| Task | Effort | Files changed |
|---|---|---|
| New auth library | 2-3 days | New file replacing msal-v1.min.js |
| Auth flow functions | 1-2 days | index.html (6 functions), dashboard.html (8 functions) |
| `fetchCalendarForRoom()` | 3-4 hours | ris-shared.js (1 function) |
| `mapEvent()` | 2-3 hours | ris-shared.js (1 function) |
| Booking body | 3-4 hours | index.html, dashboard.html |
| Approval email | 4-6 hours | index.html (or move to backend) |
| Hardcoded IDs | 1-2 hours | All files, find-and-replace |
| Testing | 2-3 days | All scenarios on LG tablet + modern devices |
| **Total** | **~2 weeks** | — |

### What stays exactly the same (zero changes)

- `deriveRoomStatus()` — status logic
- `isAllDay()`, `gDate()`, `filterMeetings()` — date/meeting utilities
- `updateCurrentCard()`, `renderMeetList()`, `renderBadges()` — all UI rendering
- `enterKiosk()`, `doFullscreen()` — kiosk/fullscreen mode
- `doCheckin()`, `endMeeting()`, `extendMeeting()` — meeting actions
- `openDaysPanel()`, `renderRoomChips()`, `switchRoom()` — navigation
- All CSS, HTML structure, weather widget, burn-in prevention, debug overlay
- `sw.js`, `manifest.json`, boot launcher APK
- `RIS_ROOMS` array (room definitions — names, seats, zones, approval flags)

---

## Recommended Migration Path

```
1. Write new auth library (replaces msal-v1.min.js)
   - Same interface: initAuth(), getToken(), doReauth()
   - Same localStorage caching pattern
   - Same iframe renewal pattern (if provider supports it)

2. Write new mapEvent() for the target provider
   - Maps provider fields → same internal model
   - All downstream UI code works unchanged

3. Write new fetchCalendarForRoom()
   - New API URL + headers
   - Returns same filterMeetings() output

4. Update booking body structure
   - New event creation format
   - Same user-facing flow (name, email, title, duration)

5. Update or replace sendApproval()
   - Consider moving to backend (SMTP relay) for provider independence

6. Find-replace hardcoded IDs
   - Tenant/Client → new provider equivalents
   - Auth endpoint URLs
   - API endpoint URLs

7. Test on LG tablets (if still in use) or modern devices
```

---

*Document version: 1.0 | Created: 2026-06-17 | Based on codebase v3.10.16*
