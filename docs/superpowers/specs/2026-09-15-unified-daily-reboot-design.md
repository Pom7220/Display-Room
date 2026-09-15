# Unified Daily Cold Reboot Design

**Date:** 2026-09-15  
**Status:** Approved — implement when back in office (2026-09-17+)  
**Version target:** v5.100

---

## Background & Motivation

The original daily cold reboot relied on LGKioskMode (`com.lge.lgkioskmode`), an LG system app, via RB broadcast (`com.lge.signage.intent.action.RB KEY_HOUR=7`). This created:

- A hidden dependency on an uncontrollable system app
- A persistent stored schedule in LGKioskMode's non-standard storage location
- A boot loop: if any cold reboot happened after 07:00, LGKioskMode's BootCompleteReceiver read the stored 07:00 schedule, saw it already passed, and fired an alarm immediately (~68s) → reboot → loop
- A model-specific approach (LG only) requiring separate handling for Latte (Android 10)

**Incident on 2026-09-14:** NW team power-cycled PoE switch at 18:00 to reassign IPs. All tablets cold-rebooted after 07:00 → Macchiato (.106) and Viennese (.107) entered boot loops lasting ~20 hours. Root fix: `su -c pm clear com.lge.lgkioskmode` wiped LGKioskMode's stored schedule.

---

## Design Goals

1. **No LGKioskMode dependency** — our app controls the reboot entirely
2. **No boot loop risk** — safe to cold-reboot at any time of day
3. **Unified approach** — same code path for Android 4.4 (LG) and Android 10 (Latte)
4. **MEET IN TOUCH coexistence** — stays running in background as backup room display
5. **Device Admin registration** — enables `DevicePolicyManager.reboot()` on Android 10, protects app from uninstall, prevents USB debugging lockdown

---

## Device Admin Registration

### Why

MEET IN TOUCH is registered as a Device Administrator. This gives it:
- `DevicePolicyManager.reboot()` — programmatic cold reboot without root (Android 5.0+ / API 21+)
- Protection from uninstall without physical consent (admin must be revoked first)
- Potential to lock/unlock developer options

We should do the same for our app. Benefits:

| Benefit | Android 4.4 (LG) | Android 10 (Latte) |
|---------|-----------------|-------------------|
| `DevicePolicyManager.reboot()` | Not available (API 19) — use `su -c reboot` | ✅ Available — no root needed |
| Uninstall protection | ✅ | ✅ |
| USB debugging protection | Investigate | Investigate |

### How

1. Create `DeviceAdminReceiver` subclass in our app
2. Declare it in `AndroidManifest.xml` with `BIND_DEVICE_ADMIN` permission and `ACTION_DEVICE_ADMIN_ENABLED` policy
3. On first launch, prompt user to activate Device Admin (one-time physical action)
4. In `ScheduleReceiver.ACTION_RESTART`:
   - If Device Admin active AND Android 10+ → use `DevicePolicyManager.reboot()`
   - Else → use `su -c reboot` (LG Android 4.4)

### Reboot logic (unified)

```
if (isDeviceAdminActive && Build.VERSION.SDK_INT >= 21) {
    devicePolicyManager.reboot(adminComponent)  // Android 10 (Latte)
} else {
    Runtime.exec("su -c reboot")                // Android 4.4 (LG)
}
```

### USB Debugging protection

To be investigated during implementation — Device Admin policies on Android 4.4 may allow locking developer options. If confirmed, add to v5.100. If not, defer to separate ticket.

### One-time activation

Device Admin requires the user to physically activate it on each tablet via:
Settings → Security → Device Administrators → Boot Launcher → Activate

This is a one-time step per tablet, similar to the initial ADB authorization. Include in the rollout runbook for v5.100.

---

## Daily Schedule (Weekdays)

| Time | Event | Mechanism |
|------|-------|-----------|
| 06:00 | OTA check → cold reboot | `ACTION_RESTART` → if Device Admin active + API 21+ → `DevicePolicyManager.reboot()` (Latte); else → `su -c reboot` (LG Android 4.4) |
| 06:01 | Boot → standby screen | `BootReceiver` → `launchStandby()` |
| 07:00 | Kiosk WebView on | `ACTION_WAKE` (also backup if WebView failed) |
| 20:30 | Standby screen | `ACTION_STANDBY` |

Weekends: no reboot. Tablets stay in standby until Monday 07:00.

---

## Architecture

### ScheduleReceiver.java — ACTION_RESTART (06:00)

Replace the current soft process restart with:

```
1. Run OTA check (same as now — check for new APK, install if available)
2. Reboot — method depends on Android version and Device Admin status:
   - If Device Admin active AND API 21+ → DevicePolicyManager.reboot()  (Latte, Android 10)
   - Else                               → su -c reboot                  (LG, Android 4.4)
```

The reboot happens unconditionally after OTA check, every weekday. No conditions on whether OTA installed or not.

**Why unconditional:** a daily cold reboot ensures clean memory state, flushes any hung processes, and re-runs BootReceiver's safety steps. OTA installs trigger a process restart anyway — the subsequent reboot is additive, not redundant.

### BootReceiver.java — on every BOOT_COMPLETED

Run in order, LG tablets only (`hasLgKioskMode()` gate):

```
1. pm disable me.exzy.meetingroom/.SystemBroadcastReceiver
   → prevents MEET IN TOUCH from sending RB broadcast this boot session
   
2. su -c pm clear com.lge.lgkioskmode
   → wipes any RB schedule MEET IN TOUCH may have written on a previous boot
   → safety net against future LGKioskMode schedule accumulation
```

Then for ALL tablets (no gate):

```
3. Check current time → before 07:00 or weekend → launchStandby()
4. ScheduleReceiver.schedule() → register ACTION_RESTART, ACTION_WAKE, ACTION_STANDBY, watchdog
```

### Why pm clear stays permanent

MEET IN TOUCH triggers its own cold reboot at 08:00 via LGKioskMode RB broadcast. After our 06:00 reboot, `pm clear` wipes LGKioskMode storage. When MEET IN TOUCH's SystemBroadcastReceiver fires at 08:01, it sends `RB KEY_HOUR=8` — but since `pm disable` blocked it this boot session, and `pm clear` wiped storage, LGKioskMode has nothing to act on. **MEET IN TOUCH's 08:00 cold reboot is effectively neutralised by our 06:00 reboot cycle.** This is an intentional side effect — one controlled reboot per day is better than two.

---

## MEET IN TOUCH Coexistence

- MEET IN TOUCH stays installed and runs normally after boot
- Its `SystemBroadcastReceiver` is disabled per-boot-session (not permanently) — it re-enables on next boot, then gets disabled again by BootReceiver
- MEET IN TOUCH displays its room UI first → our app takes over foreground after 90s delay (`BOOT_DELAY_MS`)
- MEET IN TOUCH remains a backup if our app fails to launch
- No changes to MEET IN TOUCH itself

---

## Boot Loop Prevention

| Scenario | Risk | Mitigation |
|----------|------|------------|
| Cold reboot any time after 07:00 | LGKioskMode stored schedule fires | `pm clear` on every boot wipes schedule |
| MEET IN TOUCH sends RB on boot | LGKioskMode stores new schedule | `pm disable` blocks broadcast; `pm clear` wipes storage |
| PoE power cut at any time | Cold reboot at unknown hour | Same — `pm clear` handles it |
| Weekend cold reboot | Tablet stays in standby | Time check in BootReceiver — no loop risk |

---

## What Changes in v5.100

**`ScheduleReceiver.java` — `ACTION_RESTART` handler:**
- Add `su -c reboot` after OTA check completes
- Gate on weekday only (already true — `ACTION_RESTART` only fires on weekdays)

**`BootReceiver.java`:**
- No change needed — `pm disable` + `pm clear` already in v5.99
- Remove `am broadcast RB KEY_ON_OFF=false` (was v5.98, already replaced by `pm clear` in v5.99)

**New: `BootLauncherDeviceAdminReceiver.java`:**
- Subclass of `DeviceAdminReceiver`
- Declared in `AndroidManifest.xml` with `BIND_DEVICE_ADMIN` + `android.app.device_admin` metadata

**`ScheduleReceiver.java` — reboot logic:**
```java
DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
ComponentName admin = new ComponentName(context, BootLauncherDeviceAdminReceiver.class);
if (Build.VERSION.SDK_INT >= 21 && dpm.isAdminActive(admin)) {
    dpm.reboot(admin);  // Latte (Android 10)
} else {
    Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});  // LG Android 4.4
}
```

**`build.gradle`:**
- Bump to versionCode 600, versionName "5.100"

---

## Deployment Plan

1. Deploy v5.100 via Update All during office hours
2. Monitor first 06:00 reboot cycle the following morning:
   - Confirm tablets cold-reboot at 06:00
   - Confirm boot at 06:01 → standby screen
   - Confirm kiosk WebView at 07:00
   - Confirm no loop (uptime climbs past 90s)
3. Physical access available during monitoring window

---

## Out of Scope

- Latte (Android 10) root access unknown — if `su -c reboot` fails silently on Latte, it falls back to soft process restart (current behaviour). Acceptable. Investigate separately.
- Lobby tablets (Americano, Lungo, Ristretto, Espresso) — not yet deployed, not in scope
- Cappuccino OTA lag — separate investigation needed
