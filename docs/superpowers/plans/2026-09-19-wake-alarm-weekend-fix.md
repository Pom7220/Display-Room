# Wake Alarm Weekend Fix + Online Duration Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix LG tablets waking on weekends due to a stale watchdog gate, move WAKE alarm back to 07:30 BKK, and display "Online — Xh Ym" on the admin dashboard instead of "Online — ok".

**Architecture:** Two independent changes: (1) APK fix in ScheduleReceiver.java — two one-line edits + version bump, deployed via CI; (2) Dashboard fix in dashboard.html + index.html version bump, deployed via git push to GitHub Pages.

**Tech Stack:** Android Java (API 19+), Cloudflare Worker (ES5 JS), GitHub Actions CI for APK build/deploy.

## Global Constraints

- All Java must compile against `compileSdkVersion 30`, `minSdkVersion 19`
- APK versionCode must increment by 1: `607 → 608`; versionName `"5.107" → "5.108"`
- Dashboard is `dashboard.html` — vanilla ES5 JS, no build step
- `index.html` patch must increment: `208 → 209`; `index-version.json` must update to `"v3.10.209"`
- Commit message for APK bump must include `[skip ci]` on the APK binary commit only; the source change commit triggers CI normally
- Every git push goes to `main` branch

---

### Task 1: APK — Fix WAKE alarm time and weekend gate in checkAndHeal

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java`

**Context:** Two bugs, both in ScheduleReceiver.java:
1. `schedule()` and the `ACTION_WAKE` handler both call `setExactAlarm(context, ACTION_WAKE, 2, 7, 0)` — this sets WAKE at 07:00 BKK. Must change to `7, 30` (07:30 BKK) in both places.
2. `checkAndHeal()` has a time gate `if (timeBKK < 730 || timeBKK >= 2030) return;` but no weekend check. At 07:30 BKK on Saturday, the gate passes and the watchdog relaunches tablets from standby. Add `if (isWeekend()) return;` immediately after the time gate.

`isWeekend()` is already defined at line 155 in the same file:
```java
private static boolean isWeekend() {
    int day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
    return day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY;
}
```

- [ ] **Step 1: Change WAKE alarm from 07:00 to 07:30 in `schedule()`**

In `schedule()` (around line 108), change:
```java
setExactAlarm(context, ACTION_WAKE,    2,  7,  0);
```
to:
```java
setExactAlarm(context, ACTION_WAKE,    2,  7, 30);
```

- [ ] **Step 2: Change WAKE alarm reschedule from 07:00 to 07:30 in the ACTION_WAKE handler**

In the `ACTION_WAKE` handler (around line 76), change:
```java
setExactAlarm(context, ACTION_WAKE, 2, 7, 0);
```
to:
```java
setExactAlarm(context, ACTION_WAKE, 2, 7, 30);
```

- [ ] **Step 3: Add weekend check to `checkAndHeal()`**

In `checkAndHeal()` (around line 164-170), the current code is:
```java
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) return;

java.util.Calendar bkk = java.util.Calendar.getInstance(
    java.util.TimeZone.getTimeZone("Asia/Bangkok"));
int timeBKK = bkk.get(java.util.Calendar.HOUR_OF_DAY) * 100
            + bkk.get(java.util.Calendar.MINUTE);
if (timeBKK < 730 || timeBKK >= 2030) return; // standby hours — nothing to heal
```

Change to:
```java
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) return;

java.util.Calendar bkk = java.util.Calendar.getInstance(
    java.util.TimeZone.getTimeZone("Asia/Bangkok"));
int timeBKK = bkk.get(java.util.Calendar.HOUR_OF_DAY) * 100
            + bkk.get(java.util.Calendar.MINUTE);
if (timeBKK < 730 || timeBKK >= 2030) return; // standby hours — nothing to heal
if (isWeekend()) return; // tablets stay in standby on weekends
```

- [ ] **Step 4: Verify no other references to `7, 0` for WAKE exist**

Run:
```bash
grep -n "ACTION_WAKE\|7, 0\|7,  0" "boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java"
```
Expected: all `ACTION_WAKE` references show `7, 30`. No remaining `7,  0` for WAKE.

- [ ] **Step 5: Commit**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/ScheduleReceiver.java
git commit -m "fix: move WAKE alarm to 07:30 BKK and add weekend gate to checkAndHeal

- setExactAlarm WAKE: 7,0 → 7,30 in schedule() and ACTION_WAKE reschedule
- checkAndHeal: add isWeekend() guard — prevents watchdog relaunching tablets
  from standby on Sat/Sun (timeBKK=730 gate was left over from old 07:30 WAKE time)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2: APK — Bump version to 5.108 and trigger CI build

**Files:**
- Modify: `boot-launcher/app/build.gradle`

**Context:** CI watches commits to `main` that touch `boot-launcher/` (excluding `[skip ci]` commits) and builds + deploys the APK automatically. Bumping `build.gradle` triggers the CI build. Do NOT manually build or copy the APK binary — CI handles it.

- [ ] **Step 1: Bump versionCode and versionName**

In `boot-launcher/app/build.gradle`, change:
```groovy
versionCode 607
versionName "5.107"
```
to:
```groovy
versionCode 608
versionName "5.108"
```

- [ ] **Step 2: Commit and push to trigger CI**

```bash
git add boot-launcher/app/build.gradle
git commit -m "chore: bump APK version to 5.108

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
git push
```

Expected: GitHub Actions CI picks up the push and builds APK 5.108. CI will commit the built APK binary with `[skip ci]` automatically.

---

### Task 3: Dashboard — Show "Online — Xh Ym" instead of "Online — ok"

**Files:**
- Modify: `dashboard.html` (around line 1137–1141)

**Context:** The current status text for online rooms is:
```javascript
const statusText = !r ? 'No heartbeat' :
  (!isOnline  ? 'Offline (' + r.lastSeenMinutes + 'm ago)' :
   isSleep    ? '🌙 Standby' :
   r.status === 'needs_tap' ? '👆 Waiting for tap' :
   'Online — ' + r.status);
```

`r.uptime` = minutes since page load at the time of the last heartbeat (sent by JS as `Math.round((Date.now()-_bootTime)/60000)`).
`r.lastSeenMinutes` = minutes since last heartbeat (computed by Worker from `record.timestamp`).

Total current uptime estimate = `r.uptime + r.lastSeenMinutes`.

Replace the final branch with a formatted duration string.

- [ ] **Step 1: Add uptime formatting before the statusText const**

Insert these lines immediately before `const statusText = ...` (around line 1137):

```javascript
var _upMins = r ? ((r.uptime || 0) + (r.lastSeenMinutes || 0)) : 0;
var _upHrs  = Math.floor(_upMins / 60);
var _upRem  = _upMins % 60;
var _upStr  = _upHrs > 0 ? _upHrs + 'h ' + _upRem + 'm' : _upRem + 'm';
```

- [ ] **Step 2: Replace the final branch of statusText**

Change:
```javascript
   'Online — ' + r.status);
```
to:
```javascript
   'Online — ' + _upStr);
```

- [ ] **Step 3: Verify the full statusText block looks correct**

The result should be:
```javascript
var _upMins = r ? ((r.uptime || 0) + (r.lastSeenMinutes || 0)) : 0;
var _upHrs  = Math.floor(_upMins / 60);
var _upRem  = _upMins % 60;
var _upStr  = _upHrs > 0 ? _upHrs + 'h ' + _upRem + 'm' : _upRem + 'm';
const statusText = !r ? 'No heartbeat' :
  (!isOnline  ? 'Offline (' + r.lastSeenMinutes + 'm ago)' :
   isSleep    ? '🌙 Standby' :
   r.status === 'needs_tap' ? '👆 Waiting for tap' :
   'Online — ' + _upStr);
```

- [ ] **Step 4: Commit**

```bash
git add dashboard.html
git commit -m "feat: show 'Online — Xh Ym' uptime on dashboard room cards

Replace 'Online — ok' with formatted uptime (heartbeat uptime + minutes since
last heartbeat). Example: 'Online — 1h 23m'.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 4: Bump index.html patch and index-version.json, then push all

**Files:**
- Modify: `index.html` (APP_VERSION patch field, around line 818–825)
- Modify: `index-version.json`

**Context:** Every push to main must bump the patch version in both files together. Current version is `patch: 208` / `"v3.10.208"`. Bump to `209`.

- [ ] **Step 1: Bump patch in index.html**

Find the APP_VERSION block (around line 818–825). Change `patch: 208` to `patch: 209`. The `date` field stays `'2026-09-19'` if already set; if it shows `'2026-09-18'`, update to `'2026-09-19'`.

- [ ] **Step 2: Bump index-version.json**

Change:
```json
{"version":"v3.10.208"}
```
to:
```json
{"version":"v3.10.209"}
```

- [ ] **Step 3: Commit and push**

```bash
git add index.html index-version.json
git commit -m "chore: bump APP_VERSION patch to 209 and date to 2026-09-19

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
git push
```

Expected: all four commits (Tasks 1–4, some already pushed in Task 2) land on `main`. GitHub Pages deploys dashboard.html and index.html. CI builds APK 5.108 from Task 2's push.
