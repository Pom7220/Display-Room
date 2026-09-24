# Tablet Key Rotation — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the publicly-exposed shared tablet key `RIS-TABLET-KEY2026` with a new shared key that is never committed to the repository, without taking any of the twelve kiosk tablets offline.

**Architecture:** The Worker is changed to accept two keys at once, which makes the cutover staged instead of big-bang. The new key is delivered to each tablet through SharedPreferences rather than a compiled-in Java constant, so it never enters the repo or the CI-built APK. The old constant stays in Java as a fallback, meaning the APK change is behaviourally inert until a tablet's prefs are written — which makes the actual key switch per-tablet and individually reversible.

**Tech Stack:** Cloudflare Workers (`cloudflare-worker.js`), Android Java (API 19+, `KioskWebViewActivity.java`), Gradle 7.2 / JDK 11, `wrangler` for local Worker testing, ADB over TCP for prefs delivery.

**Spec:** `docs/superpowers/specs/2026-09-24-tablet-key-rotation-design.md` (Phase 1, steps 1.1–1.5). Step 1.6 is out of scope.

---

## Global Constraints

- **`cloudflare-worker.js` uses CRLF line endings.** String edits written with `\n` match nothing and fail silently. Use the Edit tool, never a `\n`-based node/sed replacement.
- **`index.html` is ES5-only** (Chromium 30 on Android 4.4): no `const`, `let`, arrow functions, or template literals. This plan does not modify `index.html`, but any incidental edit must obey this.
- **Java target is API 19** (Android 4.4). No lambdas, no `var`, no Java 8 stream APIs.
- **The new key value must never appear** in any file under version control, in a commit message, in the plan or spec, or in any report file. It lives only in Cloudflare secrets and on-device prefs.
- **Pushing `cloudflare-worker.js` to `main` auto-deploys it** (`.github/workflows/deploy-worker.yml`). There is no staging environment. Code must be safe to deploy at the moment it is pushed.
- **Pushing `boot-launcher/**` triggers a CI APK build** (`.github/workflows/build-apk.yml`) which commits the built APK to the repo root. Assume anything in the APK is public.
- **No KV reads or writes may be added to the request auth path.** The daily write budget is ~501/1000 already.
- **All times are BKK (UTC+7).** Tablets heartbeat every 30 minutes and cold-reboot at 06:00 on weekdays. OTA command TTL is 30 minutes.
- **The A/B pair is Macchiato (10.0.54.106) and Viennese (10.0.54.107)**, hardcoded at `cloudflare-worker.js:694`.
- **Current version is `versionName "5.111"` / `versionCode 611`.** This plan ships `5.112` / `612`.
- ADB is at `C:/TEMP/platform-tools/adb.exe`. Under Git Bash, every adb call needs `MSYS_NO_PATHCONV=1` and Windows paths must be quoted (`"C:/TEMP/..."`).

---

## File Structure

| File | Responsibility in this change |
|---|---|
| `cloudflare-worker.js` | `authorizeRoomRequest` accepts either the old or new shared key; verdict string reports which matched |
| `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java` | Resolves the tablet key from prefs with the Java constant as fallback; both injection sites use the same resolved value |
| `boot-launcher/app/build.gradle` | Version bump `5.111`→`5.112`, code `611`→`612` |
| `apk-version.json` | Advertises `5.112` to the OTA updater |
| `docs/ROLLOUT-RUNBOOK.md` | Step 8 prefs XML gains `tablet_key`; new troubleshooting row |

No new files. No `index.html` or `dashboard.html` changes — migration tracking already exists at `dashboard.html:1324`, which flags any tablet not on the latest APK version.

---

## Testing approach

There is no test framework in this repo (`package.json` carries only `wrangler`). Testing is therefore:

- **Task 1:** `wrangler dev` with local vars, exercised by `curl`. This is a genuine automated check and must pass before deploying.
- **Tasks 3–7:** live verification against real tablets, using the criteria table from the spec. Each check states the exact command and the expected output.

Verification commands use `curl -s -o /dev/null -w '%{http_code}'` so the response body — which could contain calendar data — is never printed into a report or transcript.

---

## Task 1: Worker accepts two shared keys

**Files:**
- Modify: `cloudflare-worker.js:2100-2122` (`authorizeRoomRequest`)

**Interfaces:**
- Consumes: `env.RIS_TABLET_KEY` (existing secret), `env.RIS_TABLET_KEY_NEW` (new secret, may be unset)
- Produces: verdict strings `tablet:match:old` and `tablet:match:new` in place of `tablet:match`; surfaced in the `X-Auth-Check` response header at `cloudflare-worker.js:1808`

**Why this ships alone and first:** with `RIS_TABLET_KEY_NEW` unset, this change is observably inert — every tablet presents the old key and every tablet still matches. Deploying it changes nothing, and it is the precondition for every later task.

- [ ] **Step 1: Read the current function to confirm the exact text**

Run:
```bash
sed -n '2099,2122p' "cloudflare-worker.js"
```
Expected: the `authorizeRoomRequest` function, with `var expected = (env && env.RIS_TABLET_KEY) || '';` on line 2103 and the three `if (tabletKey)` branches on 2106–2110.

If the text differs from what this task quotes below, stop and report — the file has changed since planning.

- [ ] **Step 2: Write the local test harness**

Set a shell variable for the scratchpad, used by later steps:

```bash
SCRATCH="C:/Users/k_rat/AppData/Local/Temp/claude/D--OneDrive---Central-Group-Claude-AI-project-Room-Display/3d89ae88-09bb-4180-a33e-49a131630ec8/scratchpad"
```

Create `$SCRATCH/test-dualkey.sh`:

```bash
#!/usr/bin/env bash
# Exercises authorizeRoomRequest's tablet-key branch against a local wrangler dev.
# Usage: bash test-dualkey.sh   (expects wrangler dev on :8787)
set -u
BASE='http://127.0.0.1:8787/api/calendar?room=rismacchiato%40central.co.th&startDateTime=2026-09-24T00:00:00Z&endDateTime=2026-09-24T23:59:59Z'
fail=0

check() { # name expected_code key
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Tablet-Key: $3" "$BASE")
  if [ "$code" = "$2" ]; then
    echo "PASS  $1 (got $code)"
  else
    echo "FAIL  $1 (expected $2, got $code)"; fail=1
  fi
}

check "old key accepted"  200 "OLDKEY-LOCALTEST"
check "new key accepted"  200 "NEWKEY-LOCALTEST"
check "bogus key rejected" 401 "definitely-not-a-key"

code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE")
if [ "$code" = "401" ]; then echo "PASS  no credential rejected (got 401)"
else echo "FAIL  no credential rejected (expected 401, got $code)"; fail=1; fi

exit $fail
```

Note: a 200 here means the auth gate passed. The Graph call behind it will fail without real service credentials, but `handleCalendar` returns the Graph error as a 200-with-error-body, so the status code still isolates the auth decision. If your local run returns 500 for the accepted cases, the gate passed and Graph failed — treat 500 as a pass for the two "accepted" checks and note it, but 401 is always a gate failure.

- [ ] **Step 3: Run the test against the unmodified Worker to watch it fail**

Terminal 1:
```bash
npx wrangler dev cloudflare-worker.js --port 8787 --var RIS_TABLET_KEY:OLDKEY-LOCALTEST --var RIS_TABLET_KEY_NEW:NEWKEY-LOCALTEST
```

Terminal 2:
```bash
bash "$SCRATCH/test-dualkey.sh"
```

Expected: `FAIL  new key accepted (expected 200, got 401)`. The other three pass. This confirms the test actually discriminates.

- [ ] **Step 4: Make the change**

Replace lines 2102–2110 of `cloudflare-worker.js`. Current text:

```js
  var tabletKey = request.headers.get('X-Tablet-Key') || '';
  var expected = (env && env.RIS_TABLET_KEY) || '';
  var auth = request.headers.get('Authorization') || '';

  if (tabletKey) {
    if (!expected) return { ok: !strict, via: 'tablet', verdict: 'tablet:secret-not-configured' };
    if (tabletKey === expected) return { ok: true, via: 'tablet', verdict: 'tablet:match' };
    return { ok: !strict, via: 'tablet', verdict: 'tablet:MISMATCH' };
  }
```

New text:

```js
  var tabletKey = request.headers.get('X-Tablet-Key') || '';
  var expectedOld = (env && env.RIS_TABLET_KEY) || '';
  var expectedNew = (env && env.RIS_TABLET_KEY_NEW) || '';
  var auth = request.headers.get('Authorization') || '';

  // Two keys are accepted during rotation so the fleet migrates tablet by
  // tablet instead of all at once. The verdict distinguishes which matched, so
  // migration progress is observable before the old secret is withdrawn.
  // Either secret may be unset: unset means "not a valid key", never "match".
  if (tabletKey) {
    if (!expectedOld && !expectedNew) {
      return { ok: !strict, via: 'tablet', verdict: 'tablet:secret-not-configured' };
    }
    if (expectedOld && tabletKey === expectedOld) {
      return { ok: true, via: 'tablet', verdict: 'tablet:match:old' };
    }
    if (expectedNew && tabletKey === expectedNew) {
      return { ok: true, via: 'tablet', verdict: 'tablet:match:new' };
    }
    return { ok: !strict, via: 'tablet', verdict: 'tablet:MISMATCH' };
  }
```

The `expectedOld &&` / `expectedNew &&` guards matter: without them, a request sending an empty `X-Tablet-Key` against an unset secret would compare `'' === ''` and authenticate. The outer `if (tabletKey)` makes that unreachable today, but the guard is what keeps it unreachable if that outer condition is ever loosened.

**Use the Edit tool.** The file is CRLF; a `\n`-based replacement will silently match nothing.

- [ ] **Step 5: Run the test again**

```bash
bash "$SCRATCH/test-dualkey.sh"
```
Expected: all four PASS.

- [ ] **Step 6: Confirm no other reference to the old verdict string**

```bash
grep -rn "tablet:match" --include=*.js --include=*.html . | grep -v node_modules
```
Expected: only the two new occurrences in `cloudflare-worker.js`. If `dashboard.html` matches, it parses the verdict and must be updated — report this rather than guessing at the format.

- [ ] **Step 7: Commit**

```bash
git add cloudflare-worker.js
git commit -m "security: accept old and new tablet key during rotation

Inert until RIS_TABLET_KEY_NEW is set. Verdict reports which key matched so
migration is observable before the old secret is withdrawn.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

- [ ] **Step 8: Push and confirm the deploy succeeded**

```bash
git push
```
Then confirm the fleet is unaffected — the old key must still work in production:
```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-Tablet-Key: RIS-TABLET-KEY2026' \
  'https://ris-display.ris-display.workers.dev/api/calendar?room=rismacchiato%40central.co.th&startDateTime=2026-09-24T00:00:00Z&endDateTime=2026-09-24T23:59:59Z'
```
Expected: `200`. If `401`, the deploy broke the fleet — revert immediately with `git revert HEAD && git push`.

---

## Task 2: APK reads the tablet key from prefs, falling back to the constant

**Files:**
- Modify: `boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java` (`loadDisplay()` ~line 110, `interceptNavigation()` ~line 250)
- Modify: `boot-launcher/app/build.gradle:11-12`
- Modify: `apk-version.json`
- Modify: `docs/ROLLOUT-RUNBOOK.md` (Step 8 prefs XML, troubleshooting table)

**Interfaces:**
- Consumes: `SharedPreferences` named `ris_kiosk_prefs` (constant `PREFS_NAME`, already used for `room_email` / `room_name`)
- Produces: prefs string key `tablet_key`, read by both injection sites. Absent → the existing `TABLET_KEY` constant is used, so behaviour is unchanged.

**Why the constant stays:** it holds the *old* key, which is already public and becomes worthless when Task 7 deletes the old secret. Keeping it means this APK is inert on a tablet whose prefs have not been written — which is what makes the key switch per-tablet and reversible.

**Why the new key is not compiled in:** CI commits the built APK to the repo root on every `boot-launcher/**` push. A new key in Java would be public within minutes of the build.

- [ ] **Step 1: Confirm the two injection sites**

```bash
grep -n "TABLET_KEY" boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java
```
Expected exactly three lines: the declaration (~47), the URL param in `loadDisplay()` (~117), and the JS injection in `interceptNavigation()` (~258).

If there are more than three, every one of them must be converted — a missed site means the two paths disagree about the key, which presents as intermittent 401s only after an external-link navigation. Report any extras before proceeding.

- [ ] **Step 2: Add the resolver method**

Insert immediately before `private void loadDisplay()` in `KioskWebViewActivity.java`:

```java
    /**
     * The tablet's API key. Read from prefs so it can be rotated per device
     * without a build; falls back to the compiled-in constant so a tablet whose
     * prefs have not been written keeps working during a rotation.
     * Both injection sites must use this — loadDisplay() puts it in the URL and
     * interceptNavigation() writes it into localStorage, and they must agree.
     */
    private String resolveTabletKey() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String k = prefs.getString("tablet_key", "");
        return (k != null && k.length() > 0) ? k : TABLET_KEY;
    }
```

The explicit length check matters: `getString` with a default of `""` returns `""` for an entry that exists but is empty, and an empty key would produce a 401 rather than falling back.

- [ ] **Step 3: Use it in `loadDisplay()`**

Change:
```java
        url.append("&tabletkey=").append(Uri.encode(TABLET_KEY));
```
to:
```java
        url.append("&tabletkey=").append(Uri.encode(resolveTabletKey()));
```

- [ ] **Step 4: Use it in `interceptNavigation()`**

Change:
```java
            "c.tabletKey='" + TABLET_KEY + "';" +
```
to:
```java
            "c.tabletKey='" + resolveTabletKey() + "';" +
```

- [ ] **Step 5: Verify the constant is now referenced only by the resolver**

```bash
grep -n "TABLET_KEY" boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java
```
Expected exactly two lines: the declaration and the return inside `resolveTabletKey()`. Any other occurrence is a missed injection site.

- [ ] **Step 6: Bump the version**

In `boot-launcher/app/build.gradle`, change:
```gradle
        versionCode 611
        versionName "5.111"
```
to:
```gradle
        versionCode 612
        versionName "5.112"
```

In `apk-version.json`, change the whole file to:
```json
{"versionCode":612,"versionName":"5.112","apkUrl":"https://ris-display.ris-display.workers.dev/api/pkg"}
```

Both must change together — `versionCode` drives Android's install-upgrade check, `versionName` drives the dashboard's outdated-APK badge at `dashboard.html:1324`.

- [ ] **Step 7: Update the runbook**

In `docs/ROLLOUT-RUNBOOK.md`, Step 8a, change the prefs XML to:
```powershell
$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="room_email">ris<roomname>@central.co.th</string>
    <string name="room_name"><RoomName></string>
    <string name="tablet_key"><TABLET_KEY></string>
</map>
"@
```

And add below the existing "Replace `<roomname>`…" line:

> Replace `<TABLET_KEY>` with the current tablet key. **Do not read it from this
> repository** — the value in git history is the retired key. Get the live value
> from the Cloudflare secret `RIS_TABLET_KEY`, or from the person who set it.
> Omitting `tablet_key` entirely is safe on APK 5.112+ only while the retired
> key is still accepted; after retirement the tablet will fail to load its
> calendar.

Add to the troubleshooting table:

| Calendar never loads, display shows an error, but heartbeat is fine | `tablet_key` missing or wrong in prefs | Re-run Step 8 with the live key from the Cloudflare secret, then restart the app |

- [ ] **Step 8: Commit and let CI build**

```bash
git add boot-launcher/app/src/main/java/th/co/central/ris/bootlauncher/KioskWebViewActivity.java boot-launcher/app/build.gradle apk-version.json docs/ROLLOUT-RUNBOOK.md
git commit -m "feat(apk): read tablet key from prefs, constant as fallback (5.112)

Lets the shared key be rotated per device without compiling it into the APK,
which CI publishes to a public repo. Inert until prefs carry tablet_key.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

- [ ] **Step 9: Confirm CI produced the APK**

```bash
gh run list --workflow=build-apk.yml --limit 1
```
Expected: the most recent run `completed  success`. Then:
```bash
git pull
grep -c . apk-version.json && ls -la ris-boot-launcher.apk
```
Expected: `apk-version.json` reads `5.112`, and `ris-boot-launcher.apk` has a modification time from the last few minutes.

If CI failed, stop. Do not proceed to OTA.

---

## Task 3: OTA the inert APK to the A/B pair and prove it changed nothing

**Files:** none modified. This is a deployment and verification task.

**Interfaces:**
- Consumes: APK 5.112 from Task 2, dual-key Worker from Task 1
- Produces: Macchiato and Viennese running 5.112 with no prefs entry, still authenticating on the old key

- [ ] **Step 1: Trigger the A/B OTA**

From the dashboard admin panel, use the A/B update control. It writes `cmd:perform_update:ab`, which `cloudflare-worker.js:694-696` delivers only to `rismacchiato@central.co.th` and `risviennese@central.co.th`.

The command TTL is 30 minutes and tablets heartbeat every 30 minutes, so allow up to 35 minutes for both to pick it up. Trigger during 07:00–20:30 BKK on a weekday, when tablets are awake.

- [ ] **Step 2: Confirm both tablets report 5.112**

On the dashboard, both rooms must show APK `5.112` with no `⚠️` badge. The badge logic is `dashboard.html:1324` — it compares against the highest version reported across the fleet, so the other ten will now show `⚠️ APK 5.111`, which is expected and correct at this point.

- [ ] **Step 3: Confirm the key path is unchanged**

The tablets have no `tablet_key` in prefs, so they must still be using the old constant. Verify from the device rather than inferring:

```bash
cd /c/TEMP/platform-tools
MSYS_NO_PATHCONV=1 ./adb.exe connect 10.0.54.106:5555
MSYS_NO_PATHCONV=1 ./adb.exe -s 10.0.54.106:5555 shell "su -c 'cat /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'"
```
Expected: `room_email` and `room_name` present, **no** `tablet_key` entry.

- [ ] **Step 4: Confirm both displays are functional**

On each of Macchiato and Viennese, physically or via the dashboard:
- the room's meetings render (not an error card)
- the dashboard shows a heartbeat within the last 35 minutes

This is the inertness check. If either tablet degraded, the prefs-read change is faulty — roll back by OTA'ing the previous APK rather than continuing.

- [ ] **Step 5: Record the result**

No commit. Note in the execution ledger: both A/B tablets on 5.112, no prefs entry, still serving. Proceed only if both are clean.

---

## Task 4: OTA the inert APK to the remaining ten

**Files:** none modified.

**Interfaces:**
- Consumes: verified-inert APK 5.112 from Task 3
- Produces: all twelve tablets on 5.112, all still authenticating on the old key

- [ ] **Step 1: Trigger the fleet OTA**

From the dashboard admin panel, use the "Update all" control (writes `cmd:perform_update:all`, `cloudflare-worker.js:712`). Same timing constraint: weekday, 07:00–20:30 BKK, allow 35 minutes.

- [ ] **Step 2: Confirm all twelve report 5.112**

Dashboard: no room shows an `⚠️ APK` badge. The outdated count at `dashboard.html:1251` must read zero.

Any tablet still on 5.111 missed the 30-minute command window — re-trigger "Update all" while it is awake. Do not PoE-cycle it; see the runbook troubleshooting table.

- [ ] **Step 3: Confirm the fleet is still serving**

Every room shows a heartbeat within the last 35 minutes and renders meetings. All twelve are still on the old key at this point, so nothing should have changed.

- [ ] **Step 4: Record the result**

No commit. Ledger: all twelve on 5.112, inert, old key still in use fleet-wide.

---

## Task 5: Activate the new key on the A/B pair

**Files:** none modified. Secrets and device prefs only.

**Interfaces:**
- Consumes: dual-key Worker (Task 1), APK 5.112 fleet-wide (Task 4)
- Produces: Macchiato and Viennese authenticating with verdict `tablet:match:new`

**The new key value must not be written into any file in the repo, any commit message, or any report. Generate it, set it as a Cloudflare secret, and type it directly into the prefs XML on a scratch path.**

- [ ] **Step 1: Generate the new key**

```bash
openssl rand -base64 24 | tr -d '/+=' | cut -c1-28
```
Use the printed value. Do not echo it into a file that is under version control.

- [ ] **Step 2: Set the Cloudflare secret**

```bash
npx wrangler secret put RIS_TABLET_KEY_NEW
```
Paste the value when prompted. Do not pass it as a command-line argument — it would land in shell history.

- [ ] **Step 3: Confirm the Worker accepts it and still accepts the old one**

```bash
Q='room=rismacchiato%40central.co.th&startDateTime=2026-09-24T00:00:00Z&endDateTime=2026-09-24T23:59:59Z'
U="https://ris-display.ris-display.workers.dev/api/calendar?$Q"
curl -s -o /dev/null -D - -H 'X-Tablet-Key: RIS-TABLET-KEY2026' "$U" | grep -i 'x-auth-check\|HTTP/'
```
Expected: `200` and `X-Auth-Check: tablet:match:old;strict`.

Then repeat with the new key typed inline. Expected: `200` and `tablet:match:new;strict`.

If the new key returns `tablet:MISMATCH`, the secret did not save or was mistyped — re-run Step 2. Do not proceed.

- [ ] **Step 4: Write prefs on Macchiato — edit in place, app stopped**

**Do not write a fresh prefs file from a template.** The runbook's Step 8 template is for *fresh provisioning*. A live tablet's `ris_kiosk_prefs.xml` also holds runtime state the app itself writes — confirmed on Macchiato: `enforce_one_app`, `escalation_restart_count`, `escalation_first_restart_ms`, and on some tablets `screen_rotated` and watchdog counters. Replacing the file wholesale resets the escalation state machine and screen rotation on every tablet you touch.

**Stop the app before touching the file.** `KioskWebViewActivity` and `ForegroundWatchService` both write prefs at runtime, in one process with one cached in-memory map. Android rewrites the *entire* XML on every `commit()`. If a write lands after your copy, it rewrites the file from the stale map and your `tablet_key` silently disappears — and because the old key is still accepted, every functional check below would still pass. That is an invisible non-migration.

Record the UID first — ownership must be restored, and it differs per tablet:
```
C:\TEMP\platform-tools\adb.exe -s 10.0.54.106:5555 shell dumpsys package th.co.central.ris.bootlauncher | findstr userId
```

Then, in Git Bash:
```bash
cd /c/TEMP/platform-tools
IP=10.0.54.106
UID_SUFFIX=u0_aNN   # from the userId above: userId=10049 -> u0_a49

# 1. Stop the app FIRST so nothing rewrites prefs underneath us
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'am force-stop th.co.central.ris.bootlauncher'"

# 2. Pull the existing file, preserving everything already in it
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cp /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml /sdcard/prefs-in.xml && chmod 644 /sdcard/prefs-in.xml'"
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 pull /sdcard/prefs-in.xml "$SCRATCH/prefs-$IP.xml"

# 3. Confirm it has no tablet_key yet, and note what else is in there
grep -c tablet_key "$SCRATCH/prefs-$IP.xml"    # expect 0
cat "$SCRATCH/prefs-$IP.xml"
```

Now insert the entry by hand, immediately before `</map>`, typing the new key directly into the file:
```xml
    <string name="tablet_key">THE_NEW_KEY</string>
```

Leave every other line untouched. Then:
```bash
# 4. Verify before pushing: one tablet_key, and the original entries still present
grep -c tablet_key "$SCRATCH/prefs-$IP.xml"    # expect 1
grep -c room_email "$SCRATCH/prefs-$IP.xml"    # expect 1

# 5. Push back and restore ownership
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 push "$SCRATCH/prefs-$IP.xml" /sdcard/prefs-out.xml
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cp /sdcard/prefs-out.xml /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chown $UID_SUFFIX /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chmod 660 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && rm /sdcard/prefs-in.xml /sdcard/prefs-out.xml'"

# 6. Remove the scratch copy — it contains the live key
rm "$SCRATCH/prefs-$IP.xml"
```

Getting the UID wrong makes the prefs file unreadable to the app, which presents as "Tap anywhere to continue" on the display — see the runbook troubleshooting table.

- [ ] **Step 5: Repeat for Viennese**

Identical to Step 4 with `10.0.54.107`, `risviennese@central.co.th`, `Viennese`, and that tablet's own UID. **Re-read the UID** — it is not necessarily the same as Macchiato's.

- [ ] **Step 6: Start the app on both and confirm the entry survived**

The app was already stopped in Step 4, so this only starts it:
```bash
cd /c/TEMP/platform-tools
for IP in 10.0.54.106 10.0.54.107; do
  MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'am start -n th.co.central.ris.bootlauncher/.KioskWebViewActivity'"
done
```

Then re-read the file from the device and confirm `tablet_key` is still there after the app has started and written its own prefs at least once:
```bash
for IP in 10.0.54.106 10.0.54.107; do
  N=$(MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cat /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'" | grep -c tablet_key)
  echo "$IP tablet_key=$N"
done
```
Expected `tablet_key=1` on both. A `0` means a runtime prefs write clobbered the entry — redo Step 4 and check the app really was stopped.

Prefs are read in `loadDisplay()`, which runs at activity start, so the app must be restarted for a key change to take effect.

- [ ] **Step 7: Run the full verification criteria on both tablets**

All of these must pass on **both** Macchiato and Viennese:

| # | Check | How | Expected |
|---|---|---|---|
| 1 | Calendar authenticates on the new key | Dashboard shows the room's meetings rendering on the tablet | meetings visible, no error card |
| 2 | Instant booking | Book a short slot from the tablet screen | event appears in Exchange and on the dashboard |
| 3 | **+30 min extend persists** | Extend a meeting, then wait for the next `fetchCal()` (up to 60s) | the extension survives the refresh — a failed PATCH reverts silently |
| 4 | **End early / auto-release** | End a meeting early on the tablet, then check the debug overlay | no `Auto-release delete HTTP 4xx` line. This path logs failures only to the overlay (`index.html:2651`) and looks fine on screen when broken |
| 5 | Heartbeat continues | Dashboard | fresh heartbeat within 35 min |
| 6 | **The tablet is actually on the new key** | The `tablet_key=1` check from Step 6 | `1` on both |

Check 4 is the one most likely to be skipped and the most likely to hide a defect. Do not mark this task complete without it.

Check 6 is not redundant. **Checks 1–5 pass identically whether the tablet migrated or silently fell back to the old key**, because the old key is still accepted at this point. The prefs entry is the only per-tablet evidence of migration; the `X-Auth-Check: tablet:match:new` header is checked once from curl in Step 3 and says nothing about any individual tablet.

- [ ] **Step 8: The reboot test**

```bash
MSYS_NO_PATHCONV=1 ./adb.exe -s 10.0.54.106:5555 shell su -c "reboot"
MSYS_NO_PATHCONV=1 ./adb.exe -s 10.0.54.107:5555 shell su -c "reboot"
```

Wait ~5 minutes, then re-run checks 1 and 5 from Step 7.

**This is the decisive test.** A tablet can pass every check above on a `localStorage` value while the shipped APK still resolves the old constant — the URL param is only re-applied at launch. Only a cold reboot proves the installed artifact is what is authenticating. This project has repeatedly shipped defects by verifying the thing that was made rather than the thing that ships; this step exists specifically to break that pattern.

- [ ] **Step 9: Soak overnight, then record**

Leave both tablets until the next morning. Check the dashboard for new incidents on either room.

**Rollback if anything fails:** force-stop the app, pull the prefs file, delete the `tablet_key` line, push it back with the same `chown`/`chmod`, start the app. Same ordering discipline as Step 4 — if the app is running, a runtime prefs write will restore the entry from the cached map and the rollback will appear not to work. The tablet returns to the old key, which is still accepted. No APK change needed.

`localStorage` is not a rollback hazard: `index.html:3798-3800` overwrites `cfg.tabletKey` from the URL parameter on every page load, and `loadDisplay()` rebuilds that URL at every activity start.

No commit. Ledger: A/B pair on the new key, all criteria passed including reboot and overnight.

---

## Task 6: Activate the new key on the remaining ten

**Files:** none modified.

**Interfaces:**
- Consumes: the new key (Cloudflare secret `RIS_TABLET_KEY_NEW`), verified procedure from Task 5
- Produces: all twelve tablets on the new key

- [ ] **Step 1: Write prefs on each of the remaining ten**

Rooms and IPs:

| Room | IP | Email |
|---|---|---|
| Doppio | 10.0.54.101 | risdoppio@central.co.th |
| Cappuccino | 10.0.54.102 | riscappuccino@central.co.th |
| Americano | 10.0.54.103 | risamericano@central.co.th |
| Lungo | 10.0.54.104 | rislungo@central.co.th |
| Ristretto | 10.0.54.105 | risristretto@central.co.th |
| Decaffinato | 10.0.54.108 | risdecaffeinato@central.co.th |
| Latte | 10.0.54.109 | rislatte@central.co.th |
| Mocha | 10.0.54.110 | rismocha@central.co.th |
| Affogato | 10.0.54.111 | risaffogato@central.co.th |
| Espresso | 10.0.54.112 | risespresso@central.co.th |

For each: follow Task 5 Step 4 exactly — **force-stop first, pull, insert the line, push back, chown, start** — substituting that room's IP and **its own UID read fresh from that device**. UIDs differ per tablet; reusing Macchiato's will make the prefs unreadable.

Do not write a fresh prefs file from the runbook template on any of these. Each tablet's file holds its own runtime state (`enforce_one_app`, the `escalation_*` counters, `screen_rotated` where set), and replacing it resets that state.

The room email and display name are already in each file — you are only inserting one line, so the table below is for identifying the tablet, not for retyping its values.

Note `risdecaffeinato@central.co.th` — the email spelling does not match the room name "Decaffinato". Copy it from the table, do not derive it.

**Latte (10.0.54.109) is Android 10, not 4.4.** Its `su` does not accept `-c` in the same way for all commands. If the `su -c 'cp … && chown …'` form fails there, run the steps individually and report what worked — do not improvise a different ownership model.

- [ ] **Step 2: Start the app on each and confirm the entry survived**

Each tablet was force-stopped as part of its Step 1, so this starts it and then re-reads the file:

```bash
cd /c/TEMP/platform-tools
for IP in 10.0.54.101 10.0.54.102 10.0.54.103 10.0.54.104 10.0.54.105 10.0.54.108 10.0.54.109 10.0.54.110 10.0.54.111 10.0.54.112; do
  MSYS_NO_PATHCONV=1 ./adb.exe connect $IP:5555 >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'am start -n th.co.central.ris.bootlauncher/.KioskWebViewActivity'" >/dev/null
  N=$(MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cat /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'" | grep -c tablet_key)
  echo "$IP tablet_key=$N"
done
```
Expected `tablet_key=1` on all ten. A `0` means a runtime prefs write clobbered the entry — redo that tablet's Step 1 with the app confirmed stopped.

Run this in **Git Bash, not PowerShell** — bash `for` loops fail silently in PowerShell, and this project has lost an overnight capture to exactly that mistake.

- [ ] **Step 3: Confirm all twelve are serving**

Dashboard: all twelve rooms render meetings and show a heartbeat within 35 minutes. No new incidents.

- [ ] **Step 4: Spot-check the write paths on two of the ten**

Pick any two rooms from Task 6's table and run checks 2, 3 and 4 from Task 5 Step 7 (booking, extend, end-early). The read path being fine does not prove the write handlers authenticate — they are separate call sites (`handleBook`, `handleEventPatch`, `handleEventDelete`).

- [ ] **Step 5: Soak overnight**

All twelve must survive the 06:00 cold reboot and show a clean morning: fresh heartbeats, no new incidents, meetings rendering.

No commit. Ledger: all twelve on the new key, verified across a cold reboot.

---

## Task 7: Withdraw the old key

**Files:** none modified.

**Interfaces:**
- Consumes: all twelve confirmed on the new key (Task 6)
- Produces: `RIS-TABLET-KEY2026` no longer authenticates anywhere

**This task is the rotation.** Everything before it is preparation. Stopping short of this leaves the exposed key fully valid, having spent a fleet-wide APK rollout for nothing.

**There is no rollback after this step.** That is why the gate is "all twelve confirmed across a cold reboot", not elapsed time.

- [ ] **Step 1: Gate check — confirm no tablet still depends on the old key**

For each of the twelve rooms, confirm the prefs entry exists:

```bash
cd /c/TEMP/platform-tools
for IP in 10.0.54.101 10.0.54.102 10.0.54.103 10.0.54.104 10.0.54.105 10.0.54.106 10.0.54.107 10.0.54.108 10.0.54.109 10.0.54.110 10.0.54.111 10.0.54.112; do
  MSYS_NO_PATHCONV=1 ./adb.exe connect $IP:5555 >/dev/null 2>&1
  N=$(MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cat /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'" 2>/dev/null | grep -c 'tablet_key')
  echo "$IP tablet_key=$N"
done
```
Expected: `tablet_key=1` on all twelve.

Any tablet reporting `0` or unreachable **blocks this task**. An unreachable tablet is not evidence of success — resolve it (see the runbook's ADB-offline row) and re-check. Do not proceed on eleven of twelve.

- [ ] **Step 2: Confirm from the dashboard that all twelve are healthy right now**

Fresh heartbeats, meetings rendering, no active incidents. Withdrawing the old key while a tablet is already unhealthy conflates two failures.

- [ ] **Step 3: Delete the old secret**

```bash
npx wrangler secret delete RIS_TABLET_KEY
```
Confirm when prompted.

- [ ] **Step 4: Confirm the old key is dead**

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-Tablet-Key: RIS-TABLET-KEY2026' \
  'https://ris-display.ris-display.workers.dev/api/calendar?room=rismacchiato%40central.co.th&startDateTime=2026-09-24T00:00:00Z&endDateTime=2026-09-24T23:59:59Z'
```
Expected: `401`.

If this returns `200`, the secret did not delete — the exposed key is still live and the rotation has not happened. Re-run Step 3.

- [ ] **Step 5: Confirm the new key still works**

Repeat the curl with the new key typed inline. Expected: `200` with `X-Auth-Check: tablet:match:new;strict`.

- [ ] **Step 6: Confirm the fleet is unaffected**

Within 35 minutes, all twelve rooms must still show fresh heartbeats and render meetings. Watch for at least one full heartbeat cycle before declaring success.

- [ ] **Step 7: Verify the next morning**

All twelve must survive the 06:00 cold reboot. This confirms nothing was silently depending on the old key through a path that only reasserts itself at boot.

- [ ] **Step 8: Record completion**

Add an entry to `.claude/agents/knowledge-base.md` recording: the date of rotation, that `RIS-TABLET-KEY2026` is retired and present only in git history, that the live key lives in the Cloudflare secret `RIS_TABLET_KEY_NEW` and in each tablet's `ris_kiosk_prefs.xml`, and that the Java constant in `KioskWebViewActivity.java` is the retired value kept only as a fallback.

**Do not record the new key value.**

```bash
git add .claude/agents/knowledge-base.md
git commit -m "docs(kb): record tablet key rotation completion

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

---

## Out of scope

- **Spec step 1.6** — purging the retired key from `README.md` (lines 108, 255, 289) and `RIS-IT-Admin-Guide.html:88`, and stopping the APK from being committed to the repo. Deliberately excluded. Safe to defer because Task 7 makes that value worthless, but it should be scheduled: leaving a retired key documented as live is a trap for the next person doing a rollout.
- **Phase 2** — twelve distinct per-room keys in a `RIS_TABLET_KEYS` JSON secret. Planned for after one full weekend has elapsed following Task 7. The delivery mechanism built in Task 2 is what Phase 2 needs; Phase 2 becomes only the Worker-side lookup change plus twelve different prefs values.
- **The `&tabletkey=` URL parameter.** The key still travels in a query string and can appear in WebView and proxy logs. Pre-existing, accepted in the spec's residual risks, unchanged by this work.

- **Unescaped interpolation into a JS string literal**, `KioskWebViewActivity.java` `interceptNavigation()`: `"c.tabletKey='" + resolveTabletKey() + "';"`. A key containing `'` or `\` makes the injected snippet a parse error. The surrounding `try{}catch(e){}` does not catch it — a syntax error fails at parse time — so `localStorage` is silently never written while the URL-parameter path still carries a valid key. Latent today: the compiled constant is safe, and the Task 5 Step 1 generator emits alphanumerics only.

  **This must be closed before phase 2**, where twelve keys are chosen and the chance of a hand-picked value containing a quote or backslash is real. Either escape the value before interpolation, or constrain generated keys to `[A-Za-z0-9-]` and assert it. `.trim()` does not help.
