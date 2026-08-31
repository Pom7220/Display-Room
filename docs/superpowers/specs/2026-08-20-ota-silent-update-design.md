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

---

## Production Findings & Amendments

This section documents deviations from the original design discovered in production (v5.68–v5.75).

### False-positive `6_su_ok` on LG Android 4.4 (fixed v5.73)

**Finding:** On LG Android 4.4, `su` exits with code 0 regardless of whether `pm install` succeeded or failed. The original design used process exit code to determine success (`Exit code 0 = success`). In production, this caused the install step to report success even when `pm install` returned a failure (e.g. `INSTALL_FAILED_INVALID_APK`).

**Fix (v5.73):** `silentInstall()` now reads `pm install` stdout and looks for the literal strings `"Success"` or `"Failure [REASON]"`. Only a stdout line containing `"Success"` is treated as a successful install. This applies to the Android 4.4 path only; Android 10 exit codes are reliable.

**Design amendment:** Replace the error-handling table entry `pm install returns non-zero → onFailure callback` with: `pm install stdout does not contain "Success" → onFailure callback` (on Android 4.4; exit code check retained for Android 10+).

---

### `RestartReceiver` blocked by Android 10 background launch restrictions (fixed v5.75)

**Finding:** On Latte (Android 10), `startActivity()` called directly from `RestartReceiver.onReceive()` is silently blocked by the Android 10 background activity launch restriction. After a silent OTA install, the `MY_PACKAGE_REPLACED` broadcast fires the receiver, but the kiosk does not relaunch because the app process was killed (it is now in the "background" from the OS perspective) and direct `startActivity()` is not permitted from a background receiver on a killed app.

**Fix (v5.75):** `RestartReceiver` now issues a full-screen notification using `NotificationManager` with `setFullScreenIntent()` pointing to `KioskWebViewActivity`. This is the same mechanism used by alarm clocks and call UIs to present full-screen on Android 10+. It bypasses the background launch restriction. The same code path works correctly on Android 4.4 (which does not have the restriction but handles the notification normally).

**Design amendment:** The original `Files Changed` table listed `AndroidManifest.xml` as "no changes required." This was incorrect. `AndroidManifest.xml` was updated in v5.72 to register `RestartReceiver` with `android.intent.action.MY_PACKAGE_REPLACED`, and updated again in v5.75 to add the `POST_NOTIFICATIONS` permission and the notification channel used by the full-screen intent.

---

### Bootstrap problem: broken silentInstall cannot self-fix

**Finding:** v5.68 introduced `silentInstall()` with a `cp` step in the `su` command chain that broke execution on Android 4.4. Because the installed APK itself contained the broken code, the 06:00 OTA attempt silently failed — and no subsequent OTA could succeed until the broken code was replaced. This is the "bootstrap problem": a bug in the OTA mechanism prevents the OTA mechanism from delivering its own fix.

**Resolution:** A manual sideload via the room picker admin page or `adb install -r ris-boot-launcher.apk` is required to break the bootstrap deadlock. v5.72 removed the `cp` step and restored the working `su -c "pm install -r <path>"` approach.

**Design implication:** Any change to `silentInstall()` itself must be treated as high-risk. A broken `silentInstall` cannot be fixed OTA — it requires a physical visit to each affected tablet. Test on a single A/B tablet before deploying to ALL.

---

### Network unavailability at 06:00 (FortiGate)

**Finding:** The FortiGate firewall at the office location does not pass HTTPS traffic before approximately 08:00–08:15 BKK. The 06:00 ACTION_RESTART alarm fires locally and correctly, but any network call during the OTA check (`/api/version` or APK download) will fail with a connection error until the network is available.

**Behaviour:** `silentInstall()` catches the network exception and calls `onFailure`, which proceeds to `launchStandby()` as normal. The OTA attempt is skipped silently. No retry at 06:00 — the next opportunity is the following weekday at 06:00 (or an admin-triggered update from the dashboard once the network is up).

**Cloudflare observability note:** Alarm events that fire at 06:00 and 07:30 do not appear in Cloudflare Worker logs or KV records when FortiGate is blocking traffic. The alarms fire and execute locally — the absence of a Cloudflare log entry does not mean the alarm did not fire.
