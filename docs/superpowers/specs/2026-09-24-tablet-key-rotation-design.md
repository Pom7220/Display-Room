# Tablet Key Rotation — Design

**Date:** 2026-09-24
**Status:** Approved for planning
**Supersedes:** the "rotate the tablet key" line item in the 2026-09-24 security review (Tier 3, item 2)

---

## Problem

`TABLET_KEY = "RIS-TABLET-KEY2026"` is the only credential the twelve kiosk
tablets hold. It authenticates every call to `/api/calendar`, `/api/book` and
`/api/event` (PATCH and DELETE). It is:

- hardcoded at `boot-launcher/app/src/main/java/.../KioskWebViewActivity.java:47`
- committed to a **public** GitHub repository, in source and inside
  `ris-boot-launcher.apk` at the repo root
- printed in `README.md` (lines 108, 255, 289) and `RIS-IT-Admin-Guide.html:88`
- **shared by all twelve tablets** — the Worker compares against a single
  `env.RIS_TABLET_KEY` at `cloudflare-worker.js:2103`

Anyone on the internet can read the current value today with no access to the
building and no ADB.

### What rotation does and does not achieve

Rotation removes the value from public view and establishes a repeatable
procedure. It does **not** produce a key that an attacker holding a tablet
cannot read: Android keeps the installed APK at `/data/app/…/base.apk` for the
life of the install and runs the app out of it, so the constant is always
recoverable via `adb pull` or physical access. A secret compiled into a client
is not a secret.

The shared key is therefore the real defect, not its current value. A single
compromised tablet exposes all twelve rooms, and there is no way to revoke one
device. Rotation alone leaves that unchanged.

This design treats rotation as **phase 1 and the rehearsal**, and per-tablet
keys as **phase 2 and the actual goal**.

---

## Goals

1. Remove the live shared key from the public repository and published docs.
2. Establish a staged rotation that never takes the fleet offline.
3. Move to per-tablet keys, so compromise is scoped to one room and any single
   tablet can be revoked without touching the other eleven.

## Non-goals

- Making the repository private, or moving hosting. Independent decision.
- The ROPC → client-credentials migration. Independent.
- Eliminating extractability of a device-held credential. Not achievable for
  a client-side secret; scoping the blast radius is the achievable objective.

---

## Constraints

- **`index.html` is ES5-only** (Chromium 30 on Android 4.4). No `const`,
  arrow functions, or template literals.
- **`cloudflare-worker.js` has CRLF line endings.** `\n`-based string edits
  match nothing and fail silently. Use the Edit tool.
- **The key reaches the WebView three ways**, and layer 2 overwrites layer 3
  on every launch:

  | Layer | Location | Lifetime |
  |---|---|---|
  | 1 | Java constant, `KioskWebViewActivity.java:47` | permanent, in APK |
  | 2 | `&tabletkey=` URL param (`:117`) and `c.tabletKey='…'` JS (`:258`) | re-applied every launch |
  | 3 | `localStorage` cfg (`index.html:3799`) | overwritten by layer 2 |

  The `set_tablet_key` admin command only writes layer 3, so it is **temporary**
  — it survives until the next relaunch, and there is a 06:00 cold reboot daily.
  An APK release is therefore required for any permanent change.
- **CI commits the built APK to the repo root** on every `boot-launcher/**`
  push (`.github/workflows/build-apk.yml`). Anything compiled into the APK is
  public within minutes. This is why the new key is delivered by prefs.
- **Pushing `cloudflare-worker.js` to `main` auto-deploys it**
  (`.github/workflows/deploy-worker.yml`). There is no staging environment.
- **No KV reads or writes may be added to the auth path.** The daily budget is
  already a live concern (~501/1000 writes measured). Key material is carried
  in Worker secrets, which cost nothing per request.
- **Auto-release DELETE fails silently on screen** (`index.html:2651` logs only
  to the debug overlay). A broken key on that path is invisible to a user
  watching the display. Verification must exercise it explicitly.

---

## Phase 1 — Shared key rotation

### 1.1 Worker accepts two keys

Change `authorizeRoomRequest` (`cloudflare-worker.js:2100`) so `expected`
becomes a set built from `env.RIS_TABLET_KEY` and `env.RIS_TABLET_KEY_NEW`,
with a match against either returning `tablet:match`. Empty or unset secrets
are excluded from the set.

Deployed on its own this is **observably inert** — every tablet still presents
the old key, and every tablet still matches. That property is what makes the
rest of the rollout staged rather than big-bang, and it is why this ships
first and alone.

The verdict string should distinguish which key matched (`tablet:match:old`
vs `tablet:match:new`) so the admin panel can show migration progress and
step 1.5 has an evidence basis rather than an assumption.

### 1.2 New key delivered by prefs, not by the APK

**Revised 2026-09-24 during planning.** The original text put the new value in
the Java constant. That is unworkable: CI commits the built APK to the repo
root on every `boot-launcher/**` push, so a new constant would be public within
minutes of the build — rotating one publicly-readable key for another.

Instead, ship the phase-2 delivery mechanism now. `KioskWebViewActivity` reads
`tablet_key` from SharedPreferences, falling back to the existing constant,
which keeps the **old** key. The new value never enters the repository; it
lives only in a Cloudflare secret and in each tablet's prefs.

This decouples the two changes:

- **The APK is inert** without a prefs entry, so it can go fleet-wide safely
  after a pilot check. Rollout: A/B pair, verify unchanged, then all twelve.
- **The key switch is per-tablet and reversible** — remove the prefs entry and
  restart, and that tablet is back on the old key, which is still accepted.
  Rollout: A/B pair, verify against the full criteria including a cold reboot
  and an overnight soak, then the remaining ten.

A/B is only possible because of 1.1. With a single accepted secret the pilot
tablets and the remaining ten cannot hold different keys.

### 1.3 Verify — see "Rollout criteria" below

### 1.4 Remaining ten

Write prefs on the other ten using the runbook Step 8 procedure, which already
pushes a prefs XML and fixes ownership. Each tablet's UID must be read fresh;
they differ per device.

### 1.5 Remove the old secret

**This step is the rotation.** Until `RIS_TABLET_KEY` is deleted from Cloudflare,
the exposed value still authenticates and nothing has been achieved.

Gate: all twelve tablets confirmed to carry `tablet_key` in prefs, observed
across at least one cold reboot, with all twelve healthy on the dashboard.
Then delete the old secret and confirm a request carrying the old value
returns 401.

Migration tracking needs no new code: `dashboard.html:1324` already badges any
tablet not on the latest APK version, which covers the APK rollout, and the
prefs entry itself is readable per device over ADB.

This step must carry a date and an owner. A rotation that stops at 1.4 is
strictly worse than no rotation — it costs a fleet-wide APK release and leaves
the exposure intact.

### 1.6 Purge the value from the repository

- Remove the literal from `README.md` (3 occurrences) and
  `RIS-IT-Admin-Guide.html:88`; replace with a reference to the Cloudflare
  secret name.
- Stop committing `ris-boot-launcher.apk` to the tree; publish it as a CI
  build artifact or GitHub Release instead, and update the runbook's
  "APK source" note accordingly.

Git history still contains the old value. That is acceptable **only because
step 1.5 has already made it worthless** — the ordering matters.

---

## Phase 2 — Per-tablet keys

### 2.1 Key storage

Twelve distinct keys, carried in a single Worker secret `RIS_TABLET_KEYS` as
a JSON object mapping room email to key. One secret, parsed once per isolate
and cached at module level — no KV reads, no per-request cost.

`authorizeRoomRequest` already receives `room`, so the lookup is
`keys[room] === tabletKey`. A key presented for the wrong room fails, which
is the property phase 1 cannot provide: a key lifted from the Lungo tablet
cannot read or book Macchiato.

### 2.2 Key delivery to the device

**Already built in phase 1** (see revised 1.2). `KioskWebViewActivity` resolves
`tablet_key` from SharedPreferences with the Java constant as fallback, both
injection sites share the resolved value, and runbook Step 8 writes the entry.

Phase 2 therefore requires no Android change at all — only twelve different
values in the prefs files that phase 1 already writes.

### 2.3 Migration and fallback

The Worker accepts a per-room key **or** the phase-1 shared key during
migration. Tablets convert one at a time as prefs are rewritten. When all
twelve carry their own key, the shared key is removed from the Worker and the
Java constant is emptied.

Timing: activate after one full weekend has elapsed following phase 1 step 1.5.
A weekend is the specific condition a weekday soak cannot reach — no 06:00
reboot, a different alarm chain, and 60 hours between launches.

### 2.4 Revocation

Rotating one room becomes: change one entry in `RIS_TABLET_KEYS`, write that
tablet's prefs, restart the app. No APK release, no fleet-wide action. This
capability is the point of the whole exercise.

---

## Rollout criteria

Applied at 1.3 before the remaining ten, and again in phase 2 before removing
the shared fallback.

### On both pilot tablets

| Check | Why it is here |
|---|---|
| The tablet renders meetings after an app restart, with `tablet_key` confirmed present in its prefs | Not the visible screen alone — a cached render looks identical to a working fetch |
| Instant booking creates a real Exchange event | POST `/api/book` |
| **+30 min extend** persists after the next `fetchCal()` | PATCH `/api/event`; a failed PATCH reverts silently on refresh |
| **End early / auto-release** DELETE returns < 400 | Fails silently on screen (`index.html:2651`). Must be read from the debug overlay or the Worker log |
| **Survives a cold reboot** | The decisive test — see below |
| One clean overnight, no new incidents | Catches anything that only manifests on the scheduled wake cycle |

### On the remaining ten, unchanged

| Check | Why it is here |
|---|---|
| Old key still returns 200 | Proves dual-accept did not break the untouched fleet |
| A bogus key still returns 401 | Proves dual-accept did not widen the gate |

### The reboot test

A tablet can pass every functional check above on a `localStorage` value while
the installed APK still carries the old constant — layer 2 only re-asserts
itself at launch. Only a cold reboot proves the shipped APK is what is
authenticating.

This is a specific instance of a failure this project has hit repeatedly:
verifying the artifact that was produced rather than the artifact that ships.
The reboot is non-negotiable for that reason.

### Rollback

At any point before 1.5, rollback is: remove the `tablet_key` entry from that
tablet's prefs and restart the app. The old key remains valid throughout, so
the tablet is immediately functional again — no APK change, and the rollback
is per-device rather than fleet-wide.

After 1.5 there is no rollback, which is why it gates on all twelve confirmed
rather than on elapsed time.

---

## Residual risks (accepted)

- **The key travels in a URL query string** (`&tabletkey=`), so it can appear
  in WebView and proxy logs. Pre-existing; not changed by this work. Phase 2
  reduces the value of any single leaked instance to one room.
- **Git history retains the old shared key.** Worthless after 1.5.
- **A tablet's own key is extractable by anyone with the device or ADB.**
  Inherent to a client-held credential. Phase 2 bounds the damage to one room
  and makes revocation a one-line change; it does not prevent extraction.

---

## Decisions taken

**Phase 2 timing — decided 2026-09-24: separate deployment, activated after
one full weekend has elapsed following step 1.5.**

Waiting is nearly free, because the prefs-read ships in the phase 1 APK, so
phase 2 costs no fleet-wide install. Rushing is not free: if phase 2 activates
while phase 1 is still settling, a 401 has three candidate causes on the same
request path — wrong shared key, missing prefs entry, malformed key map — all
producing an identical symptom. Separated, each failure has one explanation.

The weekend specifically, rather than a day count, because it is the one
condition a weekday soak cannot reach: no 06:00 cold reboot, a different alarm
chain, and roughly 60 hours between app launches.

**Key delivery — decided 2026-09-24: prefs, not the Java constant.** See the
revised 1.2. Discovered during planning; the original approach would have
published the new key via CI.
