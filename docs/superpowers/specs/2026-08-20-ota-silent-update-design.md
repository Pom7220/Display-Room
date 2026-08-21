# OTA Silent APK Update Design

**Date:** 2026-08-20
**Status:** Approved

---

## Goal

Enable fully silent, over-the-air APK updates on rooted LG Android 4.4 tablets — automatically at 06:00 on weekdays, and on-demand via the admin dashboard. Eliminates the need to physically visit each tablet to apply updates.

---

## Context & Constraints

- All 6 in-office LG tablets are rooted (Superuser app visible on each device)
- Latte is Android 10; Macchiato, Viennese, Decaffinato, Affogato, Mocha are Android 4.4.2
- `UpdateChecker.java` already exists: handles version check (`/api/version`), APK download, and dialog-based install intent
- Admin commands are delivered via `/api/command` polling (same mechanism as `enable_test_sleep`)
- FortiGate SSL inspection blocks direct HTTPS on some tablets — all network calls must go through the Cloudflare Worker
- Silent install requires `su -c "pm install -r <path>"` — only works on rooted devices
- Tablets not granted Superuser permission must fall back to the existing manual flow gracefully
- APK updates must not occur on weekends (no one on-site to recover failures)
- All times are Bangkok (UTC+7)

---

## Two Update Triggers

### 1. Automatic — 06:00 Weekday Restart

At 06:00 ACTION_RESTART (weekdays only), `ScheduleReceiver` calls `UpdateChecker.silentInstall(context)` before launching StandbyActivity. If a newer APK is available, it downloads and installs silently via root. The normal 07:30 ACTION_WAKE then brings the tablet back on the new version.

**Timeline:**
```
06:00  ACTION_RESTART fires
       → UpdateChecker.silentInstall() checks /api/version
       → if remoteCode > localCode: download APK → su pm install -r
       → app process killed by installer
       → BootReceiver fires → 06:00 = off-hours → StandbyActivity
07:30  ACTION_WAKE → KioskWebViewActivity launches on new version
08:00+ Business hours — new version running cleanly
```

If no update available → skips silently, proceeds to StandbyActivity as normal.

### 2. Immediate — Admin Dashboard "Update Now"

Admin presses "Update Now" in the dashboard with target selection (A/B or ALL). Worker stores a `perform_update` command in KV. Tablets pick it up via the next `/api/command` poll (~90 seconds). `KioskWebViewActivity` downloads and silently installs. App relaunches to kiosk immediately after install.

**Target options:**
- **A/B** — Macchiato and Viennese only (rooms 7 and 8)
- **ALL** — all 6 in-office tablets

---

## Architecture

### `UpdateChecker.java` — new `silentInstall()` method

Add a new static method alongside the existing `check()`:

```java
public static void silentInstall(final Context context, final Runnable onNoUpdate, final Runnable onFailure)
```

**Flow:**
1. Install Conscrypt (same as existing static block — already installed, harmless to retry)
2. GET `/api/version` → parse `versionCode`, `apkUrl`
3. Compare with local `versionCode` — if not newer, call `onNoUpdate` and return
4. Download APK to `getApkFile(context)` (reuse existing helper)
5. Try `su -c "pm install -r <apkPath>"` via `Runtime.getRuntime().exec()`
   - Wait up to 60 seconds for the process to exit
   - Exit code 0 = success → app process will be killed by installer
   - Non-zero or timeout = root not available → call `onFailure`
6. On `onFailure`: fall back to existing `UpdateChecker.check(activity)` dialog flow if an `Activity` context is available; otherwise log and do nothing

**Important:** `silentInstall()` accepts a `Context` (not `Activity`) so it can be called from `ScheduleReceiver` (no UI context). The fallback dialog requires an `Activity` — only available in the dashboard-triggered path.

---

### `ScheduleReceiver.java` — call silentInstall at 06:00

In the `ACTION_RESTART` weekday branch, before `launchStandby()`:

```java
// Existing weekday check
if (!restartWeekend) {
    logAlarmEvent(context, "restart");
    sendSleepHeartbeat(context);
    // NEW: attempt silent OTA before standby
    UpdateChecker.silentInstall(context,
        new Runnable() { @Override public void run() { launchStandby(context); } },  // no update
        new Runnable() { @Override public void run() { launchStandby(context); } }   // root failed
    );
    // launchStandby now called inside callbacks only
}
```

If install succeeds, the process is killed by the package manager — `launchStandby` is never reached, and BootReceiver handles the restart. Both callbacks (no update / root failed) call `launchStandby` so normal standby always follows.

---

### `KioskWebViewActivity.java` — handle `perform_update` command

In the existing command poll handler, add alongside `enable_test_sleep`:

```java
} else if ("perform_update".equals(command)) {
    UpdateChecker.silentInstall(this,
        null,    // no update available — do nothing
        null     // root failed — do nothing (manual update remains available)
    );
}
```

On successful root install, the app process is killed and relaunches automatically. On failure or no update, the tablet continues running normally.

---

### `cloudflare-worker.js` — `perform_update` command

**New KV keys:**
- `cmd:perform_update:all` — value: `"1"`, TTL: 5 minutes
- `cmd:perform_update:ab` — value: `"1"`, TTL: 5 minutes

**`/api/command` handler** — extend existing logic:

```
if room is Macchiato or Viennese:
    check KV cmd:perform_update:ab → return { command: "perform_update" }
check KV cmd:perform_update:all → return { command: "perform_update" }
```

Command is consumed on first delivery per tablet (delete KV key after serving, or use TTL expiry — TTL preferred to avoid partial delivery on retry).

**New admin endpoint `POST /api/admin/update`:**
```json
{ "target": "all" | "ab" }
```
- Requires `X-Admin-Key` header
- Writes `cmd:perform_update:all` or `cmd:perform_update:ab` to KV with 5-minute TTL
- Returns `{ "ok": true, "target": "all" | "ab" }`

---

### `index.html` dashboard — "Update Now" button

Add to the admin panel alongside the existing 🌙 Test Sleep button:

**"⬆ Update Now"** button → opens a small inline selector:
- **A/B** (Macchiato + Viennese)
- **ALL** (all 6 in-office tablets)

On selection → POST to `/api/admin/update` with chosen target → show confirmation toast "Update command sent to [target]".

Visual treatment matches the existing Test Sleep button style.

---

## Fallback Behaviour (Non-Rooted or SU Denied)

| Scenario | Automatic (06:00) | Dashboard trigger |
|----------|------------------|------------------|
| SU granted | Silent install ✓ | Silent install ✓ |
| SU denied / timeout | Skip, launchStandby normally | Do nothing, kiosk continues |
| No update available | Skip, launchStandby normally | Do nothing |

The existing manual room picker + dashboard APK trigger flow is **unchanged**. Non-rooted tablets continue to work exactly as before.

---

## APK Download Location

Reuses `UpdateChecker.getApkFile(context)`:
- Android 10+ (Latte): `getExternalFilesDir(null)/ris-kiosk-update.apk`
- Android 4.4 (LG tablets): `Environment.DIRECTORY_DOWNLOADS/ris-kiosk-update.apk`

The `su pm install -r` command uses the absolute path of this file.

---

## Error Handling

| Failure point | Behaviour |
|---------------|-----------|
| `/api/version` unreachable | Catch exception → `onFailure` callback |
| APK download fails | Catch exception → `onFailure` callback |
| `su` not found | IOException → `onFailure` callback |
| `su` times out (>60s) | Kill process → `onFailure` callback |
| `pm install` returns non-zero | `onFailure` callback |
| Install succeeds but app doesn't restart | BootReceiver handles on next boot |

All failures are silent at the 06:00 path (no UI). The dashboard path may optionally report failure via a follow-up heartbeat field (`updateStatus: "failed"`).

---

## Files Changed

| File | Change |
|------|--------|
| `UpdateChecker.java` | Add `silentInstall(context, onNoUpdate, onFailure)` method |
| `ScheduleReceiver.java` | Call `silentInstall()` in ACTION_RESTART weekday branch |
| `KioskWebViewActivity.java` | Handle `perform_update` command in command poll |
| `cloudflare-worker.js` | Add `POST /api/admin/update` endpoint + `perform_update` command delivery |
| `index.html` | Add "Update Now" button with A/B / ALL selector in admin panel |

`StandbyActivity.java`, `BootReceiver.java`, `MainActivity.java`, `AndroidManifest.xml` — no changes required.

---

## Version Bump

Each OTA-delivered APK must have a higher `versionCode` than the previous — this is already enforced by the existing `versionCode` comparison in UpdateChecker. The CI pipeline bumps `versionCode` on every push (existing behaviour).
