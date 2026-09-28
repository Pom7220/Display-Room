# Per-Room Tablet Keys (Phase 2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each of the twelve rooms its own tablet key, so a key lifted from one device opens only that room and any single tablet can be revoked without touching the other eleven.

**Architecture:** The Worker gains a `RIS_TABLET_KEYS` secret holding a JSON map of room email to key, parsed once per isolate and cached at module level — no KV reads, no per-request cost. `authorizeRoomRequest` already receives `room`, so the check becomes `keys[room] === tabletKey`. The phase-1 shared key stays accepted during migration so each tablet can be converted and rolled back independently. Delivery needs no new mechanism: APK 5.112 already reads `tablet_key` from SharedPreferences.

**Tech Stack:** Cloudflare Worker (`cloudflare-worker.js`, CRLF), `wrangler` for secrets, ADB over TCP for prefs.

**Spec:** `docs/superpowers/specs/2026-09-24-tablet-key-rotation-design.md` — Phase 2.

**No APK change and no OTA.** This is a Worker change plus twelve prefs writes.

---

## Global Constraints

- **`cloudflare-worker.js` uses CRLF line endings.** `\n`-based edits match nothing and fail silently. Use the Edit tool.
- **Pushing `cloudflare-worker.js` to `main` auto-deploys it.** No staging environment; the code must be safe to deploy the moment it is pushed.
- **No KV reads or writes may be added to the auth path.** Budget is ~500/1000 per day.
- **Generated keys must match `[A-Za-z0-9]` only.** The APK escapes the key correctly as of 5.113, but a key containing `'`, `\`, `&` or `<` still risks the prefs XML and the URL parameter. Constrain at generation; do not rely on downstream escaping.
- **No key value may be written to disk, a commit message, a report file, or this conversation.** Keys live in the Cloudflare secret, in each tablet's prefs, and in the operator's password manager. Build them in a PowerShell variable and pipe to `wrangler`.
- **A malformed `RIS_TABLET_KEYS` must not break auth.** Parsing is wrapped so a bad value degrades to shared-key-only, never to a Worker exception — an unparseable secret would otherwise 500 every calendar request fleet-wide.
- ADB is at `C:/TEMP/platform-tools/adb.exe`. Git Bash needs `MSYS_NO_PATHCONV=1` and quoted Windows paths. **Latte (10.0.54.109, Android 10) needs `su 0 <cmd>`, one adb call per step** — `su -c` fails and `&&` chaining does not work there.
- **Force-stop the app before editing prefs, and re-read the file after restarting.** Android rewrites the whole XML from a cached in-memory map on any `commit()`, so a runtime write silently reverts the change while the tablet keeps working.
- **Never write the provisioning template over a live prefs file.** Pull, edit one line, push back. Tablets hold `enforce_one_app`, `screen_rotated`, `shortcut_requested`, `test_sleep_enabled` and the `escalation_*` counters.
- All times BKK. Tablets heartbeat every 30 min and cold-reboot at 06:00 on weekdays.

---

## Room reference

| Room | IP | Email | UID (verify fresh) |
|---|---|---|---|
| Doppio | 10.0.54.101 | risdoppio@central.co.th | u0_a49 |
| Cappuccino | 10.0.54.102 | riscappuccino@central.co.th | u0_a48 |
| Americano | 10.0.54.103 | risamericano@central.co.th | u0_a48 |
| Lungo | 10.0.54.104 | rislungo@central.co.th | u0_a50 |
| Ristretto | 10.0.54.105 | risristretto@central.co.th | u0_a48 |
| Macchiato | 10.0.54.106 | rismacchiato@central.co.th | u0_a49 |
| Viennese | 10.0.54.107 | risviennese@central.co.th | u0_a51 |
| Decaffinato | 10.0.54.108 | **risdecaffeinato**@central.co.th | u0_a55 |
| Latte | 10.0.54.109 | rislatte@central.co.th | u0_a241 |
| Mocha | 10.0.54.110 | rismocha@central.co.th | u0_a51 |
| Affogato | 10.0.54.111 | risaffogato@central.co.th | u0_a52 |
| Espresso | 10.0.54.112 | risespresso@central.co.th | u0_a48 |

UIDs are from 2026-09-24 and are recorded to catch surprises, not to be trusted — **read each one fresh**. Note Decaffinato's email spelling does not match its room name; copy it, do not derive it.

---

## Task 1: Worker accepts a per-room key or the shared key

**Files:**
- Modify: `cloudflare-worker.js` — `authorizeRoomRequest` (the tablet-key branch), plus a new module-level key-map parser

**Interfaces:**
- Consumes: `env.RIS_TABLET_KEYS` (new, may be unset), `env.RIS_TABLET_KEY_NEW` (existing shared key)
- Produces: verdicts `tablet:match:room` and `tablet:match:shared` replacing `tablet:match:new`

**Why this is inert on deploy:** with `RIS_TABLET_KEYS` unset the map is empty, every tablet still presents the shared key, and every tablet still matches.

- [ ] **Step 1: Read the current function**

```bash
sed -n '/^async function authorizeRoomRequest/,/^}/p' cloudflare-worker.js
```
Confirm it matches the text quoted in Step 3. If it differs, stop and report — the file has changed since planning.

- [ ] **Step 2: Add the key-map parser above `authorizeRoomRequest`**

```js
// Per-room tablet keys, as a JSON object of room email -> key, held in one secret.
// Parsed once per isolate: secrets cannot change without a redeploy, and a redeploy
// makes a new isolate, so the cache can never go stale.
// A malformed secret degrades to "no per-room keys" rather than throwing — an
// exception here would 500 every authenticated request on the fleet.
var _tabletKeys = null;
function getTabletKeys(env) {
  if (_tabletKeys) return _tabletKeys;
  var raw = (env && env.RIS_TABLET_KEYS) || '';
  if (!raw) { _tabletKeys = {}; return _tabletKeys; }
  try {
    var parsed = JSON.parse(raw);
    _tabletKeys = (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) ? parsed : {};
  } catch (e) {
    _tabletKeys = {};
  }
  return _tabletKeys;
}
```

- [ ] **Step 3: Replace the tablet-key branch**

Current:
```js
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

New:
```js
  var roomKeys = getTabletKeys(env);
  var roomKey = (room && Object.prototype.hasOwnProperty.call(roomKeys, room)) ? roomKeys[room] : '';

  if (tabletKey) {
    if (!expectedOld && !expectedNew && !roomKey) {
      return { ok: !strict, via: 'tablet', verdict: 'tablet:secret-not-configured' };
    }
    // Per-room key first: once a room has its own key, a key belonging to a DIFFERENT
    // room must not authenticate for it. That is the whole point of this phase.
    if (roomKey && tabletKey === roomKey) {
      return { ok: true, via: 'tablet', verdict: 'tablet:match:room' };
    }
    if (expectedNew && tabletKey === expectedNew) {
      return { ok: true, via: 'tablet', verdict: 'tablet:match:shared' };
    }
    if (expectedOld && tabletKey === expectedOld) {
      return { ok: true, via: 'tablet', verdict: 'tablet:match:old' };
    }
    return { ok: !strict, via: 'tablet', verdict: 'tablet:MISMATCH' };
  }
```

`hasOwnProperty` via `Object.prototype.call` matters: a room email is attacker-influenced input, and a bare `roomKeys[room]` would resolve inherited names such as `constructor` to a function, which would never equal the supplied string but is a footgun worth closing at the point of lookup.

`expectedOld` (`RIS_TABLET_KEY`) was deleted on 2026-09-25, so that branch is already dead — it is retained only because removing it is unrelated to this change.

- [ ] **Step 4: Confirm nothing else referenced the old verdict**

```bash
grep -rn "tablet:match:new\|tablet:match" --include=*.js --include=*.html . | grep -v node_modules | grep -v "\-VORUTCHAPON"
```
Expected: only the new strings in `cloudflare-worker.js`. A dashboard that parsed the old verdict would need updating; report it rather than guessing at the format.

- [ ] **Step 5: Check the file parses**

```bash
node --check cloudflare-worker.js
```
Expected: no output.

- [ ] **Step 6: Test the branch locally**

Terminal 1:
```bash
npx wrangler dev cloudflare-worker.js --port 8787 --var RIS_TABLET_KEY_NEW:SHAREDLOCALTEST --var 'RIS_TABLET_KEYS:{"rismacchiato@central.co.th":"MACCHIATOLOCALTEST","risviennese@central.co.th":"VIENNESELOCALTEST"}'
```

Terminal 2 — save as `$SCRATCH/test-perroom.sh` and run with bash:
```bash
#!/usr/bin/env bash
set -u
Q='startDateTime=2026-09-28T00:00:00Z&endDateTime=2026-09-28T23:59:59Z'
fail=0
check(){ # name room key expect_authpass(yes|no)
  body=$(curl -s -H "X-Tablet-Key: $3" "http://127.0.0.1:8787/api/calendar?room=$2&$Q")
  if [ "$4" = "no" ]; then
    case "$body" in *Unauthorized*) echo "PASS  $1";; *) echo "FAIL  $1 — expected rejection, got: ${body:0:60}"; fail=1;; esac
  else
    case "$body" in *Unauthorized*) echo "FAIL  $1 — expected pass, got Unauthorized"; fail=1;; *) echo "PASS  $1";; esac
  fi
}
M=rismacchiato%40central.co.th
V=risviennese%40central.co.th
D=risdoppio%40central.co.th
check "Macchiato key on Macchiato"        $M MACCHIATOLOCALTEST yes
check "Viennese key on Viennese"          $V VIENNESELOCALTEST  yes
check "CROSS-ROOM: Viennese key on Macchiato" $M VIENNESELOCALTEST no
check "CROSS-ROOM: Macchiato key on Viennese" $V MACCHIATOLOCALTEST no
check "shared key on Macchiato (fallback)" $M SHAREDLOCALTEST    yes
check "shared key on Doppio (no room key)" $D SHAREDLOCALTEST    yes
check "Macchiato key on Doppio"           $D MACCHIATOLOCALTEST no
check "bogus key"                          $M nonsense           no
exit $fail
```

Expected after the edit: all eight PASS.

**Run it before the edit too, and expect exactly this:** the two "own key on own room"
checks FAIL, and the other six pass. That is the discriminating signal.

Do **not** expect the CROSS-ROOM checks to fail beforehand. Before the edit there are no
per-room keys at all, so `MACCHIATOLOCALTEST` is just an unrecognised key and is rejected
for every room — the cross-room checks pass vacuously, for the wrong reason. They become
meaningful only once the positive checks pass, which is why both halves are needed:
the positive checks prove per-room keys are accepted, the cross-room checks prove they
are scoped.

Auth rejection is identified by the `Unauthorized` body rather than the status code: the
local Worker has no Graph credentials, so a request that passes the gate fails downstream
with its own distinct error, and both can surface as 401. The `X-Auth-Check` verdict
header cannot be used here — it is only set on the 200 success path, which is unreachable
without Graph credentials.

- [ ] **Step 7: Commit**

```bash
git add cloudflare-worker.js
git commit -m "security: accept per-room tablet keys, shared key as fallback

RIS_TABLET_KEYS maps room email to key, parsed once per isolate. A key
belonging to one room no longer authenticates for another. The phase-1 shared
key stays accepted so tablets can be converted and rolled back one at a time.

Inert until RIS_TABLET_KEYS is set. A malformed secret degrades to
shared-key-only rather than throwing.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

- [ ] **Step 8: Push and confirm the fleet is unaffected**

```bash
git push
```
After ~45s, with the shared key (operator supplies it):
```
GET /api/calendar?room=rismacchiato@central.co.th with X-Tablet-Key: <shared key>
```
Expected: `200` and `X-Auth-Check: tablet:match:shared;strict`.

If this returns 401, the deploy broke the fleet — `git revert HEAD && git push` immediately.

---

## Task 2: Generate the twelve keys and publish the secret

**Files:** none. Secrets only.

**Interfaces:**
- Consumes: the deployed Worker from Task 1
- Produces: `RIS_TABLET_KEYS` populated for all twelve rooms

**This task is the operator's.** Keys must never reach a report file, a commit, or the transcript. Everything below runs in one PowerShell window and keeps the values in memory only.

- [ ] **Step 1: Generate twelve keys into a hashtable, printing nothing**

```powershell
$rooms = 'risdoppio','riscappuccino','risamericano','rislungo','risristretto','rismacchiato','risviennese','risdecaffeinato','rislatte','rismocha','risaffogato','risespresso'
$K = @{}
foreach ($r in $rooms) { $K["$r@central.co.th"] = -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 28 | ForEach-Object {[char]$_}) }
"generated $($K.Count) keys, all length $(($K.Values | ForEach-Object { $_.Length } | Sort-Object -Unique) -join ',')"
```
Expected: `generated 12 keys, all length 28`. A second number in that list means a generation fault — start again.

- [ ] **Step 2: Confirm the keys are distinct and alphanumeric**

```powershell
"distinct=$(($K.Values | Sort-Object -Unique).Count) nonalnum=$(($K.Values | Where-Object { $_ -notmatch '^[A-Za-z0-9]+$' }).Count)"
```
Expected: `distinct=12 nonalnum=0`.

- [ ] **Step 3: Publish the secret**

```powershell
($K | ConvertTo-Json -Compress) | npx wrangler secret put RIS_TABLET_KEYS
```
Expected: `✨ Success!`. The Cloudflare API has timed out on this project before — re-run if so, it is idempotent.

Run from the repository directory, or `npx` will not find wrangler.

- [ ] **Step 4: Verify every room accepts its own key and rejects another's**

```powershell
$Q = 'startDateTime=2026-09-28T00:00:00Z&endDateTime=2026-09-28T23:59:59Z'
$other = 'risdoppio@central.co.th'
foreach ($r in $K.Keys) {
  $own   = curl.exe -s -o NUL -w '%{http_code}' -H "X-Tablet-Key: $($K[$r])" "https://ris-display.ris-display.workers.dev/api/calendar?room=$([uri]::EscapeDataString($r))&$Q"
  $cross = curl.exe -s -o NUL -w '%{http_code}' -H "X-Tablet-Key: $($K[$other])" "https://ris-display.ris-display.workers.dev/api/calendar?room=$([uri]::EscapeDataString($r))&$Q"
  "{0,-32} own={1} otherRoomsKey={2}" -f $r, $own, $cross
}
```
Expected: `own=200` for all twelve, and `otherRoomsKey=401` for all except Doppio itself (where the "other" key is its own, so 200).

A single `own=401` means that room's entry is wrong in the secret — fix before touching any tablet. **This is the gate for Task 3.**

- [ ] **Step 5: Save the keys**

Export the hashtable into the password manager, one entry per room. After Task 4 these are the only credentials the fleet has, and Cloudflare cannot read a secret back.

**Keep this PowerShell window open** — Task 3 uses `$K` for every tablet.

---

## Task 3: Convert the tablets

**Files:** none. Device prefs only.

**Interfaces:**
- Consumes: `$K` from Task 2, and the verification gate in Task 2 Step 4
- Produces: each tablet holding its own room's key

Order: **Macchiato and Viennese first**, verify, then the remaining ten. Each tablet is independently reversible — restore the shared key in its prefs and restart — for as long as Task 4 has not run.

- [ ] **Step 1: Per tablet — force-stop, pull, edit, push back, restart**

For each room, in Git Bash for the adb work and PowerShell for the edit. `IP` and `UID` come from the room table; **read the UID fresh**:

```bash
cd /c/TEMP/platform-tools
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell dumpsys package th.co.central.ris.bootlauncher | grep -m1 userId
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'am force-stop th.co.central.ris.bootlauncher'"
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cp /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml /sdcard/prefs-in.xml && chmod 644 /sdcard/prefs-in.xml'"
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 pull /sdcard/prefs-in.xml "C:/TEMP/prefs-$IP.xml"
```

For **Latte only** (10.0.54.109), use `su 0 <cmd>` with one adb call per step — no `-c`, no `&&`.

Replace the existing `tablet_key` line in PowerShell, using that room's key from `$K`:
```powershell
$p = "C:\TEMP\prefs-10.0.54.1NN.xml"
$room = 'risXXXX@central.co.th'
$c = Get-Content $p | Where-Object { $_ -notmatch 'tablet_key' }
($c[0..($c.Count-2)] + "    <string name=""tablet_key"">$($K[$room])</string>" + $c[-1]) | Set-Content $p -Encoding ascii
```

Note this **filters out the existing line** before inserting — unlike phase 1, these files already have a `tablet_key`. Verify masked before pushing:
```powershell
(Get-Content $p) -replace '(<string name="tablet_key">).*(</string>)', '$1REDACTED$2'
```

Push back, restore ownership, restart, and confirm the entry survived startup:
```bash
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 push "C:/TEMP/prefs-$IP.xml" /sdcard/prefs-out.xml
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cp /sdcard/prefs-out.xml /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chown $UID:$UID /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chmod 660 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && rm /sdcard/prefs-in.xml /sdcard/prefs-out.xml'"
MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'am start -n th.co.central.ris.bootlauncher/.KioskWebViewActivity'"
```
Then delete `C:/TEMP/prefs-$IP.xml` — it holds a live key.

Use `chown UID:UID` with the group. `chown UID` alone leaves the group unchanged.

- [ ] **Step 2: Prove each converted tablet is on ITS OWN key**

This is the check phase 1 could not make, and it does not require knowing the value:

```bash
cd /c/TEMP/platform-tools
Q='startDateTime=2026-09-28T00:00:00Z&endDateTime=2026-09-28T23:59:59Z'
# $IP and $ROOM for the tablet just converted; $OTHERROOM is any different room
TK=$(MSYS_NO_PATHCONV=1 ./adb.exe -s $IP:5555 shell "su -c 'cat /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'" | tr -d '\r' | grep -o '<string name="tablet_key">[^<]*' | sed 's/.*>//')
echo -n "own room (want 200):   "; curl -s -o /dev/null -w '%{http_code}\n' -H "X-Tablet-Key: $TK" "https://ris-display.ris-display.workers.dev/api/calendar?room=$ROOM&$Q"
echo -n "other room (want 401): "; curl -s -o /dev/null -w '%{http_code}\n' -H "X-Tablet-Key: $TK" "https://ris-display.ris-display.workers.dev/api/calendar?room=$OTHERROOM&$Q"
```

`own=200, other=401` is the proof that the key is room-scoped. `other=200` means that tablet is still on the shared key — the prefs write did not take.

- [ ] **Step 3: Verify the pilot pair before continuing**

On Macchiato and Viennese: displays rendering meetings, fresh heartbeats, and the Step 2 result on both. Then convert the remaining ten.

- [ ] **Step 4: Full fleet sweep**

Repeat Step 2 for all twelve. Every one must be `own=200, other=401`. Then confirm the dashboard shows all twelve healthy with no new incidents.

- [ ] **Step 5: Soak overnight**

All twelve must come through the 06:00 cold reboot and the 07:30 wake with `tablet_key` intact and displays serving.

---

## Task 4: Withdraw the shared key

**Files:** none. Secrets only.

**This is the step that delivers the phase.** Until `RIS_TABLET_KEY_NEW` is deleted, a key lifted from any tablet still opens all twelve rooms — exactly the weakness this phase exists to remove.

**No rollback after this.** A tablet whose per-room key is wrong will fail, and the fix is a prefs write over ADB.

- [ ] **Step 1: Gate — all twelve room-scoped, verified across a cold reboot**

Re-run Task 3 Step 2 for all twelve after an overnight. Every one `own=200, other=401`. An unreachable tablet blocks this step; resolve it rather than proceeding on eleven.

- [ ] **Step 2: Confirm the fleet is healthy right now**

Fresh heartbeats, meetings rendering, no active incidents. Withdrawing the shared key while a tablet is already unhealthy conflates two failures.

- [ ] **Step 3: Delete the shared secret**

```powershell
npx wrangler secret delete RIS_TABLET_KEY_NEW
```

- [ ] **Step 4: Confirm the shared key is dead**

With the shared key (operator supplies):
```
GET /api/calendar?room=rismacchiato@central.co.th
```
Expected: `401`. A `200` means the delete did not take.

- [ ] **Step 5: Confirm per-room keys still work**

Re-run the Task 2 Step 4 loop. All twelve `own=200`, cross-room `401`.

- [ ] **Step 6: Watch one heartbeat cycle**

All twelve fresh within 35 minutes.

- [ ] **Step 7: Verify next morning, then record**

All twelve through the 06:00 reboot. Then add a knowledge-base entry: the date, that the shared key is retired, that each room has its own key in `RIS_TABLET_KEYS` and its prefs, and that revoking one room is now a single edit to the secret plus that tablet's prefs.

**Do not record any key value.**

---

## Rollback

Before Task 4, per tablet: restore the shared key in that tablet's prefs and restart. The shared key is still accepted, so the tablet works immediately. No Worker change, no APK, no fleet-wide action.

Task 1 alone is revertable with `git revert` plus a push.

After Task 4 there is no rollback, which is why it gates on all twelve verified across a reboot rather than on elapsed time.

---

## Out of scope

- **Gating `GET /api/command` and `POST /api/heartbeat`.** Still unauthenticated; they now carry only ordinary commands. Needs the APK to send `X-Tablet-Key`, since `postJsonFire` sets only `Content-Type`. Next APK release.
- **Unauthenticated KV writers** (`/api/incident`, `/api/alarm`, `/api/heartbeat`) — knowledge base Finding 2. Budget exhaustion, so lost monitoring rather than lost service. Edge rate limiting was the preferred direction; undecided.
- **Emptying the retired constant in `KioskWebViewActivity`.** It still holds `RIS-TABLET-KEY2026`, which is dead everywhere. Harmless, and removing it needs an APK release.
- **ROPC → client credentials** for `getServiceToken()`.
