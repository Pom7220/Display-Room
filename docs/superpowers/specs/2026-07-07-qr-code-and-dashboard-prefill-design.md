# QR Code on Tablet + Dashboard Booking Pre-fill — Design Spec

**Date:** 2026-07-07  
**Status:** Approved  
**Scope:** Two independent UI features targeting `index.html` (kiosk) and `dashboard.html`

---

## Feature 1 — QR Code on Tablet Kiosk

### Purpose
Allow staff to scan a QR code on the room tablet to instantly open the dashboard on their phone and check all 12 room availabilities — without walking to each room.

### Trigger
A small QR icon button added in the bottom-right corner of the kiosk display, alongside the existing gear (⚙) icon. Same visual style and size — unobtrusive during normal operation.

### Overlay behaviour
- Tapping the QR icon opens a full-screen dark overlay (z-index above all content)
- Overlay contains:
  - QR code image (280×280px), centered
  - Label below: "Scan to view all rooms"
  - 30-second countdown progress bar beneath the label
  - No explicit close button — tap anywhere on overlay to dismiss early
- Auto-closes after 30 seconds; countdown restarts if overlay is reopened
- Overlay is dismissed by any tap anywhere on it (including on the QR image itself)

### QR code generation
- Use Google Charts QR API as a plain `<img>` tag — no JS library, ES5-safe, Chromium 30 compatible:
  ```
  https://chart.googleapis.com/chart?cht=qr&chs=280x280&chl=ENCODED_DASHBOARD_URL
  ```
- URL is constructed dynamically at render time:
  ```javascript
  var dashUrl = window.location.origin + window.location.pathname.replace('index.html','') + 'dashboard.html';
  ```
- This survives repo transfer (no hardcoded `pom7220.github.io`) and works via Cloudflare Worker URL

### Constraints
- ES5 only — no `const`/`let`, no arrow functions, no template literals
- Must not interfere with kiosk touch-lock (back button still blocked by APK)
- Overlay must close cleanly — no residual timer if overlay is dismissed early (use `clearTimeout`/`clearInterval`)

---

## Feature 2 — Dashboard Booking Pre-fill from Sign-in

### Purpose
When a user opens the booking modal on `dashboard.html`, the Name and Email fields are pre-populated from their signed-in Microsoft account — reducing friction and preventing typos.

### Data source
MSAL `userAgentApplication.getAccount()` returns:
- `account.name` — display name (e.g. "Vorutchapon K.")
- `account.userName` — UPN / work email (e.g. "vorutchapon@central.co.th")

### Behaviour
- On modal open: if an MSAL account is available, pre-fill Name = `account.name`, Email = `account.userName`
- Both fields remain fully editable — user can change them to book on behalf of a colleague
- If no account is available (not signed in, edge case): fields start empty as today
- Pre-fill runs once per modal open — does not override values if user has already typed

### Scope
- Change is in `dashboard.html` only — specifically in the function that opens the booking modal
- No changes to `index.html`, `ris-shared.js`, or Cloudflare Worker

---

## Files Changed

| File | Change |
|---|---|
| `index.html` | Add QR icon button + overlay HTML + CSS + JS (open/close/countdown) |
| `dashboard.html` | Pre-fill name + email from MSAL account on modal open |

## Out of Scope
- QR code on dashboard (not needed — dashboard IS the destination)
- Locking the pre-filled fields (user can edit)
- Generating QR code for individual room URLs
- Any changes to the Cloudflare Worker or ris-shared.js
