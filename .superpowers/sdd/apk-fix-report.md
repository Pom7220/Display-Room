# APK Bug Fix Report — Final Code Review Fixes

**Date:** 2026-07-01

## Fix C1 — Watchdog creates infinite Chrome relaunch loop

**File:** `ForegroundWatchService.java`

**Problem:** `checkAndRestore()` only accepted `ownPackage` as a valid foreground state. When `launchKioskActivity()` falls back to Chrome (WebView activity fails), Chrome becomes foreground and the watchdog destroys and restarts it every 5 minutes indefinitely.

**Fix:** Added `!CHROME_PACKAGE.equals(topPackage)` to the condition so the watchdog treats Chrome as an accepted foreground state and does not trigger recovery when Chrome is on top.

---

## Fix I1 — FLAG_ACTIVITY_CLEAR_TASK in watchdog recovery destroys a live WebView

**Files:** `BootReceiver.java`, `ForegroundWatchService.java`

**Problem:** `launchKioskActivity()` uses `FLAG_ACTIVITY_CLEAR_TASK` which tears down and rebuilds the full activity stack. Correct for initial boot, but destructive when the watchdog fires for a transient foreground theft — it unnecessarily kills a live, working WebView.

**Fix:**
- Added `bringKioskToFront(Context context)` to `BootReceiver.java` — uses only `FLAG_ACTIVITY_NEW_TASK` (no `CLEAR_TASK`), so the existing activity is brought to front without destruction.
- Updated `ForegroundWatchService.checkAndRestore()` to call `BootReceiver.bringKioskToFront()` instead of `BootReceiver.launchKioskActivity()`.
- Updated the `CHROME_PACKAGE` field comment to reflect it is actively used (no longer an "unused" constant).

---

## Fix I3 — TokenFetcher.getErrorStream() NPE on no-body non-200 responses

**File:** `TokenFetcher.java`

**Problem:** On non-200 HTTP responses, `conn.getErrorStream()` can return `null` when the server sends no error body (e.g., 401, 503 with no body). Passing `null` directly to `new InputStreamReader()` throws a NullPointerException.

**Fix:** Added a null-check for `getErrorStream()`. If the stream is null, the result is returned immediately with `ok = false` and `error = "HTTP <code>"`, avoiding the NPE.

---

## Commit

Commit hash: _(see git log)_
