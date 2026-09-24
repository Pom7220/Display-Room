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

### 1.2 New APK on the A/B pair

Set `TABLET_KEY` to the new value, release through CI, install on
**Macchiato (10.0.54.106)** and **Viennese (10.0.54.107)** — the established
A/B pair.

A/B is only possible because of 1.1. With a single shared secret the pilot
tablets and the remaining ten cannot hold different keys.

### 1.3 Verify — see "Rollout criteria" below

### 1.4 Remaining ten

Roll the same APK to the other ten tablets using the runbook procedure.

### 1.5 Remove the old secret

**This step is the rotation.** Until `RIS_TABLET_KEY` is deleted from Cloudflare,
the exposed value still authenticates and nothing has been achieved.

Gate: the admin panel shows `tablet:match:new` for all twelve rooms, observed
across at least one cold reboot. Then delete the old secret and confirm a
request carrying the old value returns 401.

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

Move the value out of the Java constant into SharedPreferences, alongside
`room_email` and `room_name`, which `loadDisplay()` already reads by exactly
this pattern (`KioskWebViewActivity.java:111-113`).

```java
String tabletKey = prefs.getString("tablet_key", TABLET_KEY);
```

The constant remains as a fallback so a tablet whose prefs lack the entry
keeps working on the shared key through the migration. Both `loadDisplay()`
and `interceptNavigation()` must read the same resolved value — they inject
the key separately and would otherwise diverge.

Runbook Step 8 gains `tablet_key` in the prefs XML it already writes. No new
tooling; the same `push` + `chown` sequence.

### 2.3 Migration and fallback

The Worker accepts a per-room key **or** the shared key during migration.
Tablets convert one at a time as prefs are written. When all twelve carry
their own key, the shared fallback is removed from both the Worker and the
Java constant.

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
| `/api/calendar` returns 200 with verdict `tablet:match:new` | The verdict, not the visible screen — a cached render looks identical to a working fetch |
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

At any point before 1.5, rollback is: reinstall the previous APK. The old key
remains valid throughout, so a rolled-back tablet is immediately functional.
After 1.5 there is no rollback — which is why 1.5 gates on all twelve
confirmed rather than on elapsed time.

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

## Open question for implementation planning

Whether phase 2 follows phase 1 immediately or after a settling period. The
argument for immediate: it reuses the same APK release cycle and avoids a
second fleet-wide install. The argument for waiting: two changes to the auth
path in one week, on a system where the most recent auth change (Tier 2
strict auth) already produced one regression — the admin panel's own status
fetch missing its key, commit 18752be.

Recommendation: run them as separate deployments, but plan phase 2 before
phase 1 ships, so the Java change in 2.2 can be folded into the same APK with
the prefs entry simply absent until phase 2 activates it.
