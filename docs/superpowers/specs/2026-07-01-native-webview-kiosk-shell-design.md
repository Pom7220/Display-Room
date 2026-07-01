# Native WebView APK Kiosk Shell — Design Spec

**Date:** 2026-07-01
**Status:** Approved for implementation
**Project:** RIS Room Display — Central Group, Central Silom Tower

---

## Problem

After `location.reload()` (triggered by auto-update version check, admin reload command, or away-timer), Chrome 42 loses fullscreen. The web Fullscreen API requires a user gesture to re-enter fullscreen; a programmatic call from `setTimeout` is rejected by Chrome 42. Four versions (v3.10.56–59) have attempted web-layer fixes — none fully resolved it. The root cause is a Chrome 42 API limitation that cannot be overcome from JavaScript.

---

## Solution

Switch the kiosk container from Chrome (browser) to `KioskWebViewActivity` — an Android Activity already written in the boot-launcher APK that sets `FLAG_FULLSCREEN` on the Android window at the OS level. WebView page reloads never exit Android-level fullscreen because the OS window flag is unrelated to the web Fullscreen API.

---

## Architecture

**Before:**
```
Boot → BootReceiver → Chrome (ACTION_VIEW intent)
                    → Web Fullscreen API (doFullscreen via setTimeout)
                    → Breaks on location.reload()
```

**After:**
```
Boot → BootReceiver → KioskWebViewActivity (explicit Intent)
                    → TokenFetcher → POST /api/token → ROPC tokens
                    → WebView loads index.html?room=...#t=base64(tokens)
                    → Android FLAG_FULLSCREEN (reload-proof)
```

---

## Changes Required

### 1. `boot-launcher/app/src/main/AndroidManifest.xml`

Register `KioskWebViewActivity` as a declared activity:

```xml
<activity
    android:name=".KioskWebViewActivity"
    android:label="RIS Kiosk Display"
    android:screenOrientation="portrait"
    android:theme="@android:style/Theme.Holo.NoActionBar.Fullscreen"
    android:exported="false" />
```

### 2. `boot-launcher/app/src/main/java/.../BootReceiver.java`

The boot thread has two calls to fix:

```java
// BEFORE
launchChrome(context);           // at 90s
bringChromeToFront(context);     // at 180s (redundant bring-to-front)

// AFTER
launchKioskActivity(context);    // at 90s — new method, starts KioskWebViewActivity
// remove the 180s bringChromeToFront call — no longer needed
```

Add new method `launchKioskActivity()`:
```java
static void launchKioskActivity(Context context) {
    Intent intent = new Intent(context, KioskWebViewActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    context.startActivity(intent);
}
```

`KioskWebViewActivity` already handles room config (reads `room_email`, `room_name` from SharedPreferences) and token injection via `TokenFetcher`. No further changes to the activity itself.

### 3b. `boot-launcher/app/src/main/java/.../ForegroundWatchService.java`

The watchdog recovery action currently calls `BootReceiver.bringChromeToFront()`. After the switch, a crashed `KioskWebViewActivity` would be "recovered" by opening Chrome instead. Update the recovery call:

```java
// Replace:
BootReceiver.bringChromeToFront(getApplicationContext());
// With:
BootReceiver.launchKioskActivity(getApplicationContext());
```

The watchdog condition (line 73) already correctly allows the APK's own package — `KioskWebViewActivity` in foreground satisfies `ownPackage.equals(topPackage)` and will not trigger recovery.

### 4. `cloudflare-worker.js` — Add `POST /api/token`

`TokenFetcher.java` calls `POST /api/token` with `X-Admin-Key` on boot. This route is missing. Add it by extracting the ROPC logic already present in `handleRemoteReauth()` into a direct token-return endpoint:

```javascript
// POST /api/token — return ROPC tokens directly to APK on boot
if (path === '/api/token' && method === 'POST') {
  if (!checkAdminKey(request, env)) return jsonResponse({ error: 'Unauthorized' }, 401);
  return handleTokenFetch(env);
}
```

`handleTokenFetch(env)` is identical to the ROPC fetch in `handleRemoteReauth()` but returns tokens as JSON directly instead of queuing them as a command. Returns:
```json
{
  "ok": true,
  "access_token": "...",
  "refresh_token": "...",
  "id_token": "...",
  "client_id": "...",
  "expires_in": 3600
}
```

---

## What Does NOT Change

| Component | Status |
|---|---|
| `index.html` | No changes — `#t=base64` injection already implemented (line 2985) |
| `sw.js` | No changes |
| `ris-shared.js` | No changes |
| GitHub Pages hosting | No changes |
| Cloudflare Worker proxy | No changes — new `/api/token` route is additive |
| `KioskAccessibilityService` | Stays registered — still used for remote `force_fullscreen` admin commands |
| `ForegroundWatchService` | No changes — already watches own package (line 73) |
| 12-room configuration | No changes |

---

## Risk: Android 4.4 System WebView vs Chrome 42

The Android 4.4 system WebView (Chromium 33) is older than Chrome 42. Compatibility is expected to hold because:
- All JavaScript in `index.html` is ES5 with explicit polyfills for older browsers
- All CSS uses `-webkit-` prefixes throughout
- The `#t=base64` token injection path was designed specifically for this APK/WebView flow
- `KioskWebViewActivity` already has `onReceivedSslError → handler.proceed()` for cert handling

**Verify after first deploy:** MSAL silent iframe token renewal — confirm it completes successfully in the system WebView context. If it fails, `re_auth_remote` (ROPC via Worker command channel) is the fallback.

---

## Deployment

1. Build new APK via GitHub Actions
2. Sideload to all 12 tablets via USB (one-time operation)
3. Verify on one tablet before rolling to remaining 11

---

## Phase 2 — Off-Hours Updates (once stable)

Add `AlarmManager` in the APK to schedule `webView.reload()` at 02:00 local time daily. Zero web-layer changes required. The daily reload picks up any new version deployed to GitHub Pages within the 90-second version-check window.

---

## Success Criteria

- After `location.reload()` triggered by auto-update: display stays fullscreen, no tap required
- After admin `reload` command: display stays fullscreen, no tap required
- After away-timer reload (5 min inactivity): display stays fullscreen, no tap required
- Token injection on boot: no MSAL login screen shown
- MSAL silent renewal: tokens refresh invisibly as before
