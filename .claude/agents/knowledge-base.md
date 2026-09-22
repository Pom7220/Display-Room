# RIS Health Agent — Knowledge Base

Patterns accumulated from live runs AND manual debug sessions.
Agent reads this at the start of every run and self-amends when patterns are promoted or retired.
**Convention:** At the end of every manual debug session with Claude, update this file before closing the session. Human review recommended when a candidate reaches 3+ occurrences without a clear fix.

## Guiding Principles

1. **Stable system first** — the goal is a self-sustaining display system that runs without daily intervention. Every fix, pattern, and agent improvement should move toward fewer incidents, not just faster reaction to them.
2. **Stay within Cloudflare KV free plan limits** — 100,000 reads/day, 1,000 writes/day. The agent must not introduce fix loops or polling patterns that burn KV operations. Auto-fixes (reload, OTA) should be applied once per tablet per run, never retried within the same run. Diagnostics fetched once per run only.

---

## Confirmed Patterns

### [2026-09-10] Alarm event race condition (APK < 5.88)
- status: confirmed
- seenCount: 6 (all tablets, 2 consecutive days — Sep 9–10)
- firstSeen: 2026-09-09
- confirmedOn: 2026-09-10
- evidence: `restart` (06:00) and `wake` (07:30) events absent from alarm log on v5.86/5.87. ADB logcat confirmed alarms DID fire. `logAlarmEvent` was async — `pm install -r` in `silentInstall` force-killed the process mid-POST.
- root cause: Race condition — `logAlarmEvent` background thread killed by `pm install -r` before HTTP POST completed.
- fix: OTA to APK 5.88. `logAlarmEventSync` (blocking) now called before `silentInstall`. 3× retry added to async path as safety net.
- fix outcome: All 6 tablets updated to 5.88 on 2026-09-10 08:43–44 BKK. `ota_install` events confirmed. Monitor from 2026-09-11 06:00 for `restart` events.

### [2026-09-09] Viennese Chrome foreground (APK backgrounded by OS)
- status: confirmed
- seenCount: 1
- firstSeen: 2026-09-09 13:33 BKK
- confirmedOn: 2026-09-10
- evidence: `unexpected_reboot` at 13:32 BKK (Sep 9), then `app_resumed_from_bg` at 14:33 BKK with empty apkVersion (no `window.Android` interface = Chrome was active). APK recovered after PoE cycle. Heartbeat gap 13:33–14:33. ADB logs cleared by PoE on Android 4.4.2 — exact trigger unknown.
- root cause: APK process sent to background between 13:33–14:33 BKK. Chrome or another app took foreground. `unexpected_reboot` event likely from a process restart that didn't reinstate kiosk mode.
- fix: PoE cycle resolved. No APK change — trigger not reproducible without retained logs.
- agent action: If `app_resumed_from_bg` with empty apkVersion appears in alarmLog, flag as "Chrome mode incident" — physical inspection recommended.

### [2026-09-10] 06:00 alarm = process restart, screen stays OFF
- status: confirmed
- confirmedOn: 2026-09-10
- evidence: Dashboard showed all 5 non-Mocha tablets in standby at 06:24 BKK. User expected them to be awake. ADB logcat confirmed `ACTION_RESTART` fired correctly — it restarts the APK process but does NOT wake the screen. Screen only wakes at 07:30 `wake` alarm via `BootReceiver.launchWebView`.
- root cause: Design intent, not a bug. `restart` = silent process restart for OTA check. `wake` = screen on + kiosk launch.
- agent action: Do NOT flag tablets as offline if heartbeat gap aligns with 05:50–06:10 AND next heartbeat resumes at 07:30. This is normal standby behaviour.

### [2026-09-10] ADB authorization requires "Always allow" on first connect
- status: confirmed
- seenCount: 1
- confirmedOn: 2026-09-10
- evidence: During Doppio rollout, ADB connected but showed "device unauthorized" after reboot. Root cause was NOT reboot — "Always allow from this computer" was not tapped on the initial authorization dialog. Once tapped, connection was persistent.
- root cause: Android ADB RSA key authorization is per-device. Must tap "Always allow from this computer" (not just "Allow once") on first connect. If "Allow once" was tapped, the authorization is lost on reboot or ADB server restart.
- agent action: N/A — manual step only. Rollout runbook must include: tap "Always allow from this computer" on first ADB connect.
- adb note: See ADB Manual Investigation runbook below.

### [2026-09-10] `ota_install` event confirms APK update completed
- status: confirmed
- confirmedOn: 2026-09-10
- evidence: All 6 tablets logged `ota_install` with `apkVersion: 5.88` within 30 seconds of "Update all" trigger at 08:43–44 BKK. `RestartReceiver.onReceive(MY_PACKAGE_REPLACED)` fires in the newly installed version — reliable OTA completion signal.
- root cause: N/A — this is a positive signal, not a failure pattern.
- agent action: After any OTA run, confirm `ota_install` appears in each updated tablet's alarmLog within 15 min. If absent after 15 min, OTA may have silently failed — flag for retry.

### [2026-09-14] LGKioskMode boot loop after off-hours PoE power cut
- status: confirmed
- seenCount: 2 (Macchiato .106, Viennese .107)
- firstSeen: 2026-09-14 18:00 BKK
- confirmedOn: 2026-09-15
- evidence: NW team PoE power-cycle at 18:00 caused cold reboot after 07:00. LGKioskMode BootCompleteReceiver found stored 07:00 schedule already passed → immediate alarm (~68s) → reboot → loop. Loop lasted ~20 hours.
- root cause: LGKioskMode stores RB schedule in non-standard storage. Any cold reboot after 07:00 BKK causes it to fire immediately on next boot.
- fix: `adb shell su -c pm clear com.lge.lgkioskmode` — wipes stored schedule. v5.99+ runs this on every boot automatically. v5.100 adds daily cold reboot at 06:00 (before stored schedule fires) to prevent accumulation.
- agent action: If tablet shows boot loop (uptime < 90s repeatedly, no online status), flag for physical ADB intervention: `su -c pm clear com.lge.lgkioskmode`.

### [2026-09-15] v5.100 — cold_boot replaces restart in alarm log
- status: confirmed (from 2026-09-17 06:00 BKK onwards)
- evidence: v5.100 changes ACTION_RESTART from soft restart to cold reboot. BootReceiver now posts `cold_boot` event on every BOOT_COMPLETED.
- agent action: From v5.100, the expected daily alarm chain is `cold_boot` (❄️ ~06:01) → `wake` (☀️ 07:00) → `standby` (🌙 20:30). A missing `cold_boot` before 07:00 on a weekday may indicate cold reboot failed — check heartbeat uptime continuity. `restart` events will no longer appear.
- agent action: Do NOT flag 06:00 cold reboots as incidents. `unexpected_reboot` is only posted if boot time is outside 05:45–06:15 BKK window.

---

## ADB Manual Investigation Runbook

Use when the agent flags a case as "needs ADB" — pattern not explainable from Worker data alone.

**Prerequisites:**
- ADB platform tools: `C:\TEMP\platform-tools\adb.exe`
- Tablet IP address (see tablet-ips below)
- USB debugging must be enabled on the tablet (re-enable physically if PoE cycled)

**Known tablet IPs** (update if DHCP changes):

Active — confirmed IPs after NW rearrangement on 2026-09-14:
- Doppio:      10.0.54.101  (ADB authorized, v5.101 ✅)
- Cappuccino:  10.0.54.102  (ADB authorized, v5.101 ✅ — MEET IN TOUCH disabled; UID mismatch fixed 2026-09-17)
- Americano:   10.0.54.103  (deployed 2026-09-18, v5.106 ✅)
- Lungo:       10.0.54.104  (deployed 2026-09-18, v5.106 ✅)
- Ristretto:   10.0.54.105  (deployed 2026-09-18, v5.106 ✅)
- Macchiato:   10.0.54.106  (ADB authorized, v5.101 ✅ — MEET IN TOUCH disabled 2026-09-17)
- Viennese:    10.0.54.107  (ADB authorized, v5.101 ✅)
- Decaffinato: 10.0.54.108  (ADB unauthorized, v5.101 ✅)
- Latte:       10.0.54.109  (ADB authorized, v5.101 ✅ — Android 10; MEET IN TOUCH disabled; plain reboot confirmed working)
- Mocha:       10.0.54.110  (ADB authorized, v5.101 ✅)
- Affogato:    10.0.54.111  (ADB authorized, v5.101 ✅)
- Espresso:    10.0.54.112  (ADB authorized, v5.109 ✅ — deployed 2026-09-21, userId=10048, risespresso@central.co.th)

Note: All IPs changed by NW team on 2026-09-14. Old mapping is obsolete. No LGKioskMode risk on lobby tablets.
NW confirmed 2026-09-14 (Monday night): FortiGate SSL bypass rule covers entire IP range 10.0.54.101–120, including all lobby tablets. Network/FortiGate is NOT a blocker for any tablet deployment. Do NOT suspect network as a cause without direct evidence — this was a persistent false assumption that cost multiple debug sessions.

**Step 1 — Connect:**
```
C:\TEMP\platform-tools\adb.exe connect <ip>:5555
```
If connection refused: USB debugging not enabled. Enable via Settings → Developer Options → USB Debugging on the tablet.

**Step 2 — Pull recent alarm-related logs:**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 logcat -d -t 1000 | findstr /i "ScheduleReceiver UpdateChecker AlarmManager restart wake standby"
```

**Step 3 — Check if AlarmManager alarms are still registered:**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell dumpsys alarm | findstr /i "ris.bootlauncher"
```
Expected: 3 entries for ACTION_RESTART, ACTION_WAKE, ACTION_STANDBY. If missing, alarm chain is broken — PoE cycle or manual APK relaunch needed.

**Step 4 — Disconnect:**
```
C:\TEMP\platform-tools\adb.exe disconnect <ip>:5555
```

**After investigation:** Update this knowledge base with findings before closing the session.

---

## New Tablet Rollout Runbook

Use for first-time APK install on a fresh tablet. After initial install, all future updates go via OTA ("Update all" on dashboard).

**Prerequisites:**
- ADB platform tools: `C:\TEMP\platform-tools\adb.exe`
- APK file: `D:\OneDrive - Central Group\Claude.AI project\Room-Display\ris-boot-launcher-VORUTCHAPON.apk`
- Tablet IP address and room email/name confirmed
- USB debugging enabled on tablet (Settings → Developer Options → USB Debugging)

**⚠️ NEVER use `adb install -r`** — the `-r` flag reassigns a new UID, which breaks prefs ownership (Cappuccino bug, 2026-09-17). Use plain `adb install` for fresh installs.

**Step 1 — Connect:**
```
C:\TEMP\platform-tools\adb.exe connect <ip>:5555
```
On the tablet: tap **"Always allow from this computer"** (not just "Allow once" — loses auth on reboot).

**Step 2 — Record current UID (before install):**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell dumpsys package th.co.central.ris.bootlauncher | findstr userId
```
Note the `userId=XXXXX` value.

**Step 3 — Install APK (no -r flag):**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 install "D:\OneDrive - Central Group\Claude.AI project\Room-Display\ris-boot-launcher-VORUTCHAPON.apk"
```

**Step 4 — Verify UID unchanged after install:**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell dumpsys package th.co.central.ris.bootlauncher | findstr userId
```
If UID changed from Step 2 → fix prefs ownership before rebooting:
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell su -c "chown u0_a<N> /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml"
```
(Replace `<N>` with new UID suffix, e.g. userId=10048 → u0_a48)

**Step 5 — Set room prefs:**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell su -c "am broadcast -a th.co.central.ris.ACTION_SET_PREFS --es room_email <room>@central.co.th --es room_name <RoomName> -n th.co.central.ris.bootlauncher/.SettingsReceiver"
```

**Step 6 — Disable MEET IN TOUCH:**

LG Android 4.4 (all lobby/office tablets except Latte):
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell su -c "pm disable me.exzy.meetingroom"
```
Android 10 (Latte only):
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell pm disable-user --user 0 me.exzy.meetingroom
```

**Step 7 — Clear LGKioskMode (LG tablets only):**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell su -c "pm clear com.lge.lgkioskmode"
```

**Step 8 — Reboot:**
```
C:\TEMP\platform-tools\adb.exe -s <ip>:5555 shell su -c "reboot"
```

**Step 9 — Verify on dashboard:**
Tablet should appear with heartbeat and correct room name within ~5 minutes of reboot.

---

## Confirmed Patterns (continued)

### [2026-09-17] MEET IN TOUCH force-lock black screen
- status: confirmed
- seenCount: 2 (Macchiato .106, Cappuccino .102)
- firstSeen: 2026-09-17 09:50 BKK
- confirmedOn: 2026-09-17
- evidence: Macchiato black screen at 09:50 BKK — ADB `dumpsys device_policy` showed `me.exzy.meetingroom` registered as Device Admin with `force-lock` policy. Cappuccino same state. MEET IN TOUCH can call `lockNow()` at any time to darken screen, making tablet unreachable (no heartbeat, no ping).
- root cause: MEET IN TOUCH registered as Device Admin with force-lock policy. It can lock the screen at will, displacing the kiosk WebView.
- fix: `adb shell su -c "pm disable me.exzy.meetingroom"` — disables entire package, persists across reboots. On Android 10 (Latte): `adb shell pm disable-user --user 0 me.exzy.meetingroom` (no su needed).
- fix outcome: Macchiato and Cappuccino screens restored immediately. v5.99+ BootReceiver runs `pm disable me.exzy.meetingroom/.SystemBroadcastReceiver` on every LG boot automatically — but full `pm disable` via ADB is more thorough for already-affected tablets.
- agent action: If tablet shows black screen + no heartbeat + no ping, and has LGKioskMode (LG Android 4.4), check MEET IN TOUCH Device Admin. Run `dumpsys device_policy | grep -A5 meetingroom`. If force-lock listed → `pm disable me.exzy.meetingroom`.
- note: Latte (Android 10) did NOT have MEET IN TOUCH as Device Admin. Disabled preventively via `pm disable-user`.

### [2026-09-17] Latte (Android 10) cold reboot — DPM.reboot() requires Device Owner not Device Admin
- status: confirmed
- confirmedOn: 2026-09-17
- evidence: v5.100 used `DevicePolicyManager.reboot()` requiring Device Owner privilege. Activating via Settings → Security → Device Administrators only grants Device Admin — not Device Owner. `dpm.reboot()` threw silent SecurityException. Fallback `su -c reboot` also fails on Android 10 (invalid uid/gid). Both paths silently failed — no cold reboot on Latte.
- fix: v5.101 — use plain `Runtime.exec("reboot")` on API 21+ (works on Android 10 without root). Keep `su -c reboot` for API 19 (LG Android 4.4). `BootLauncherDeviceAdminReceiver` and `device_admin.xml` removed as dead code.
- fix outcome: `adb shell reboot` confirmed working on Latte. ❄️ cold_boot at 10:49 BKK confirmed. v5.101 deployed to all 8 tablets.
- agent action: If Latte shows no ❄️ cold_boot chip on a weekday, check APK version — must be ≥ v5.101 for cold reboot to work.

### [2026-09-17] Cappuccino UID mismatch — prefs unreadable after APK reinstall
- status: confirmed
- confirmedOn: 2026-09-17
- evidence: Cappuccino showed "Tap anywhere to continue" after every cold reboot. WebView URL missing `room=` and `roomname=` parameters. Prefs file `ris_kiosk_prefs.xml` existed with correct values but owned by `u0_a49` (UID 10049). App's current UID was 10048 (`dumpsys package | grep userId`). App could not read its own prefs file — `getSharedPreferences()` returned empty strings for room_email and room_name. Without room identity, web app shows fullscreen prompt instead of room display.
- root cause: APK was reinstalled (sideload via `adb install`) which assigned a new UID (10048). Old prefs file remained owned by old UID (10049). Android app cannot read prefs owned by a different UID.
- fix: `adb shell su -c "chown u0_a<N> /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml"` where N matches the current UID from `dumpsys package th.co.central.ris.bootlauncher | grep userId`. Tablet immediately launched correctly after reboot.
- prevention: After every `adb install -r` sideload, verify UID matches prefs ownership. Get current UID: `dumpsys package th.co.central.ris.bootlauncher | findstr userId`. Rewrite prefs ownership with that UID.
- agent action: If tablet shows no `room=` in WebView URL (visible in logcat chromium lines) but prefs file exists, check UID mismatch.

### [2026-09-17] Latte device clock 7 hours ahead — standby fires at 13:30 instead of 20:30
- status: candidate (root cause unconfirmed — watch tomorrow 06:00 cold_boot)
- firstSeen: 2026-09-17 13:30 BKK
- evidence: Latte `standby` event logged at 13:30 BKK (server-side timestamp). Device clock showed 21:04 when actual BKK was 14:04 — exactly +7h ahead. `persist.sys.timezone` = Asia/Bangkok (correct). `NTP cache age: Long.MAX_VALUE` — NTP has never successfully synced this boot session. `auto_time = 1` (enabled but not working). Standby at 13:30 = 20:30 − 7h → exact match for device clock being 7h ahead.
- hypothesis: RTC stores local BKK time; Android reads it as UTC and adds +7 → clock shows UTC+14 effective. Every boot re-applies the double-offset. NTP either blocked (UDP 123 firewall) or returning wrong time.
- workaround applied: Disabled `auto_time` (`settings put global auto_time 0`) and set clock to actual BKK time (`su 0 date 091714172026.00`). Latte restored via `am start -n .../MainActivity --ez auto_launch true`. Clock holds correctly within session but will revert on next cold reboot.
- trigger: Manual cold reboot at 10:49 BKK (our test). Before that reboot the clock was presumably correct; NTP did not sync after boot to correct the RTC offset.
- to-watch: Tomorrow 06:00 — if cold_boot logged at ~23:00 BKK tonight or ~06:00 correct → confirms/refutes RTC as persistent source. If clock wrong again after 06:00 reboot, permanent fix needed: either force NTP sync, correct RTC once via `su 0 date <UTC_as_local>`, or add clock check to BootReceiver.
- recovery: `settings put global auto_time 0; su 0 date MMDDHHMMYYYY.ss` (use actual UTC time in BKK format, e.g. if actual BKK=14:17, actual UTC=07:17, run `su 0 date 091707172026.00`). Then `am start -n th.co.central.ris.bootlauncher/.MainActivity --ez auto_launch true`.
- agent action: If Latte `standby` fires outside 20:20–20:40 BKK window, suspect device clock offset. Check `adb shell date` vs actual BKK time. If >10 min off, apply recovery above.

### [2026-09-17] silentInstall hangs when network unavailable — blocks cold reboot
- status: confirmed
- confirmedOn: 2026-09-17
- evidence: ACTION_RESTART broadcast sent to Cappuccino (LG Android 4.4) — no reboot after 10+ minutes. Direct `adb shell su -c "reboot"` worked immediately. Root cause: `UpdateChecker.silentInstall()` makes OkHttp call to `/api/version` with no timeout configured. If network is unavailable (or slow), the thread hangs indefinitely — `su -c reboot` never executes. Same mechanism explains 5 consecutive days of OTA failure on Cappuccino at 06:00 BKK (network not ready).
- fix needed: Add connect + read timeout to OkHttpClient in `silentInstall()` — target v5.102.
- agent action: If cold_boot missing at 06:00 on LG tablet despite v5.101+, suspect silentInstall network hang. Cannot self-recover — physical reboot or PoE cycle needed.

### [2026-09-12] Heartbeat watchdog restart (APK ≥ 5.90)
- status: confirmed
- confirmedOn: 2026-09-12 (deployed)
- evidence: ForegroundWatchService 5-min loop checks `now - lastHeartbeatSuccessMs > heartbeatIntervalMs + 15 min` during 07:30–20:30 BKK. JS calls `window.AndroidHB.recordHeartbeatSuccess(intervalMins)` on every successful XHR heartbeat. `lastHeartbeatSuccessMs = 0` on cold start — watchdog skips until first JS heartbeat arrives (no false restart on boot). On threshold exceeded: logs `heartbeat_watchdog_restart`, relaunches via `getLaunchIntentForPackage` + `Process.killProcess`.
- agent action: If `heartbeat_watchdog_restart` appears in alarmLog, classify as RECOVERED. No fix action needed. Report: "Self-recovered via heartbeat watchdog at HH:MM BKK — JS loop had been silent since [last heartbeat ts]."

### [2026-09-12] ACTION_WATCHDOG self-heal alarm (APK ≥ 5.89)
- status: confirmed
- confirmedOn: 2026-09-12 (deployed)
- evidence: AlarmManager fires every 30 min independently of APK process state. On Android 4.4 (API 19), checks if KioskWebViewActivity is top activity via ActivityManager.getRunningTasks(1). If not (and BKK time 07:30–20:30), logs watchdog_relaunch and relaunches. Skips on API 21+ (Latte) — getRunningTasks restricted to own package on Lollipop+.
- agent action: If watchdog_relaunch appears in alarmLog, classify as RECOVERED. No fix action needed — watchdog already resolved it. Report: "Self-recovered via watchdog at HH:MM BKK."

### [2026-09-10] Doppio white screen — root cause unconfirmed
- status: candidate
- seenCount: 1
- firstSeen: 2026-09-10
- evidence: APK installed (v5.88), prefs correct (risdoppio@central.co.th / Doppio). OkHttp gets HTTP 200 with `<html><body><script` body from `/api/version` — Layer 7 HTTPS interception confirmed. WebView shows white screen. DNS resolves `ris-display.ris-display.workers.dev` to `172.67.213.200` (correct Cloudflare IP) — same as working tablets. ICMP blocked on both Doppio and Affogato (normal for Cloudflare). TCP/443 connects (OkHttp receives a response) but interceptor returns HTML instead of proxying to Cloudflare.
- ruled out: FortiGate (network engineer: all 12 whitelisted; only Latte/Android 10 needed special bypass). Zero Trust (Applications page empty). System proxy/VPN (null). Firmware difference (V.03.02.65 identical to working tablets). nativeOnDraw failed / X509Util errors (appear on working Affogato too). DNS (same resolved IP as Affogato). DHCP vs static IP (changed Doppio to static IP 10.0.54.81, DNS 10.0.10.1, Gateway 10.0.54.11 — identical to working tablets — still gets HTML).
- key clue: All 6 non-working tablets are in Lobby zone. All 6 working tablets are in Office zone. Something in the Lobby network path intercepts HTTPS to Cloudflare at Layer 7 and returns HTML. The 2026-07-10 FortiGate fix was documented as "office network" only — Lobby network was never configured.
- root cause CONFIRMED 2026-09-11: Cloudflare Worker real-time logs show all 6 working tablets (Macchiato, Viennese, Decaffinato, Latte, Mocha, Affogato) reaching the Worker. Zero requests from Doppio appear — Doppio's HTTPS traffic is intercepted on the local network before reaching Cloudflare. The interceptor returns HTTP 200 + HTML (`<html><body><script`). This is a LOCAL NETWORK device on the Lobby VLAN (gateway 10.0.54.11) intercepting outbound HTTPS to 172.67.213.200:443.
- what NOT to do: Do not run `am start` or `am broadcast ACTION_TEST_WAKE` on Doppio while Meet in Touch is in operation — it launches KioskWebViewActivity and pushes Meet in Touch to background.
- fix required: Network engineer must identify what device on Lobby VLAN (gateway 10.0.54.11) intercepts HTTPS to Cloudflare (172.67.213.200:443) and configure it to allow traffic, same as Office VLAN. The "all 12 whitelisted" statement from network engineer is incorrect or refers only to SSL inspection — a separate URL/content filter is blocking Lobby VLAN traffic to Cloudflare Workers.
- fix applies to: All 6 Lobby tablets (Doppio, Cappuccino, Americano, Lungo, Ristretto, Espresso) — same VLAN, same issue.
- evidence to show NW engineer: "Cloudflare Worker logs show zero requests from Doppio (10.0.54.81) while all Office tablets appear. Request intercepted before leaving building. Device on Lobby VLAN gateway 10.0.54.11 must be configured to allow HTTPS to 172.67.213.200:443."

## System Architecture — Current State (2026-09-18)

### Deployment status (2026-09-18)
- **12 tablets now live** (all rooms complete as of 2026-09-21):
  - Office (8): Doppio, Cappuccino, Macchiato, Viennese, Decaffinato, Mocha, Affogato, Latte
  - Lobby (4): Americano (.103), Lungo (.104), Ristretto (.105), Espresso (.112, deployed 2026-09-21)
- **APK**: v5.109 on all deployed tablets
  - v5.109: Level 2 escalated reboot — after ≥3 failed process restarts or 120 min hung, fires hard reboot. Daily cap 3 reboots. Weekend guard on checkAndHeal(). Dashboard ⚡❄️ chip for escalated_reboot events.
- **index.html**: v3.10.211 target (dashboard auto-refresh reduced 15s→5min to stay within KV read limit)
- **MEET IN TOUCH**: disabled on all lobby tablets via `pm disable me.exzy.meetingroom`. Latte disabled via `pm disable-user --user 0` (Android 10, no su needed).
- **Network**: FortiGate SSL bypass covers 10.0.54.101–120 — no network blocker for any tablet.
- **KV reads**: Dashboard auto-refresh 5min (was 15s) — ~30k reads/day per open tab, well within 100k free limit.

## System Architecture — Current State (2026-09-12)

### Self-heal layers (innermost to outermost)
1. **v5.90 heartbeat watchdog** — ForegroundWatchService 5-min loop; if JS heartbeat silent > interval+15 min during 07:30–20:30 BKK, logs `heartbeat_watchdog_restart` and restarts process via `getLaunchIntentForPackage` + `Process.killProcess`. Covers: dead JS loop, frozen WebView XHR.
2. **v5.89 ACTION_WATCHDOG** — AlarmManager fires every 30 min; on API 19 only, checks if KioskWebViewActivity is top activity; relaunches if not. Logs `watchdog_relaunch`. Covers: APK pushed to background (API 19 tablets only).
3. **06:00 ACTION_RESTART** — daily process restart + OTA check. Covers: accumulated memory issues, pending OTA.
4. **Agent morning report** — observer only; flags OFFLINE_DEAD and ALARM_GAP; sends OTA for version upgrades. Does NOT send reload (watchdogs handle recovery).
5. **Physical PoE cycle** — last resort for OFFLINE_DEAD where AlarmManager chain is broken.

### Agent behaviour (as of 2026-09-12)
- **Reload command: RETIRED** — agent no longer sends reload. Tablets self-heal via watchdogs.
- **fix-log endpoint: RETIRED** — agent no longer reads or writes `/api/fix-log`. Every run is fully independent.
- **OTA: unlimited** — `perform_update` sent to ALL HEALTHY_OUTDATED tablets per run, no cap.
- **OFFLINE_RECOVERABLE**: agent reports the gap and notes watchdog will self-heal. No action taken.
- **OFFLINE_DEAD**: flag for physical intervention only.

### AlarmManager protection
- ACTION_WATCHDOG (v5.89) re-registers itself every 30 min — keeps the chain alive between reboots.
- BOOT_COMPLETED re-registers all alarms on every power-on via BootReceiver.
- Allied Telesis PoE schedule (daily 05:30 BKK) discussed but not configured — mixed Office/Lobby switch layout makes per-zone scheduling impractical for now.
- Agent ALARM_GAP detection: if `restart` event missing 2+ consecutive days → flag for investigation.

### Cloud Agent Design — On Hold (2026-09-11)
- **Decision**: Full cloud autonomous agent requires a persistent office-network bridge device (always-on machine on 10.0.54.x running ADB + Cloudflare Tunnel)
- **Without bridge**: agent can detect + analyse + OTA autonomously; Tier 2 (OFFLINE_DEAD, broken alarm chain) requires human PoE cycle
- **Resume trigger**: decision on office bridge device (dedicated Pi ~$50, or existing always-on machine)
- **Agent bug fixed 2026-09-11**: agent now detects tablets absent from diagnostics response as OFFLINE_DEAD (was silently skipping Mocha)

## Candidate Patterns

<!-- Agent appends here when new unclassified patterns are observed. -->
<!-- Format: ### [YYYY-MM-DD] [RoomName] — [brief description] -->
<!-- Fields: status, seenCount, firstSeen, lastSeen, evidence, hypothesis, fix applied, fix outcome -->

### [2026-09-12] Mocha + Latte simultaneously absent from diagnostics
- status: confirmed (Mocha half) / resolved (Latte half)
- seenCount: 3
- firstSeen: 2026-09-12 17:23 BKK
- lastSeen: 2026-09-13 08:26 BKK
- evidence: `/api/diagnostics` returned only 4 of 6 expected Office rooms — Mocha and Latte both absent. KV heartbeat record expired (>2h). CF logs confirmed JS heartbeat (not OkHttp) is the active heartbeat during active hours. `reload` command sent earlier had no effect — process was dead.
- root cause investigation: JS heartbeat XHR failing while GET /api/calendar (different JS call) was still working — suggests JS XHR thread specifically died. KV TTL expired after >2h silence.
- known limitation: v5.89 ACTION_WATCHDOG skips on Latte (API 29 — getRunningTasks restricted). v5.90 heartbeat watchdog covers Latte too once installed.
- fix applied: `perform_update` to v5.90 sent 2026-09-12 20:04 BKK. Tablets were expected to install at 06:00 BKK 2026-09-13 via ACTION_RESTART.
- fix outcome (2026-09-13 08:26 BKK run): **Latte recovered** — present in diagnostics, heartbeat ~56 min old, apkVersion still 5.88 (OTA to 5.90 re-sent this run). **Mocha still absent** from diagnostics at 08:26 BKK, past the 08:00 escalation checkpoint set in the prior run.
- escalation (per prior run's own criteria): Mocha AlarmManager chain appears broken — **physical PoE cycle required on 10.0.54.110 (Mocha)**. No agent action can recover it (absent from KV entirely = process dead, not just slow).
- fix outcome (2026-09-14 09:30 BKK run): **Latte fully resolved** — apkVersion 5.90, restart ✅ 06:00, wake ✅ 07:30, `ota_install` confirmed matching heartbeat. **Mocha back online** (heartbeat ~59 min old, presumably PoE-cycled or self-recovered between runs) but still on apkVersion 5.88 — no `ota_install` logged since 2026-09-10. Also showing a NEW symptom: `restart` alarm event missing from alarmLog for both today (2026-09-14) and yesterday (2026-09-13, weekend variant) — 2-day consecutive gap on APK ≥5.88. `wake` alarm fired fine both days. `perform_update` re-sent to Mocha targeting 5.90 this run.
- ADB investigation (2026-09-14 ~09:32–09:35 BKK, on-device via 10.0.54.110:5555): **hypothesis of "AlarmManager chain broken" is REFUTED by direct evidence.** `dumpsys alarm` shows `ACTION_RESTART`, `ACTION_WAKE`, `ACTION_STANDBY` all still registered under `ScheduleReceiver` with 19 cumulative wakes/alarms each (matching the app's running lifetime) — the alarms ARE firing and the chain is intact. Logcat confirms the local restart process itself also worked: `perform_update` sent this run triggered a live `pm install -r` sequence at 09:32:20–09:32:29 BKK (force-stop → codePath swap → PACKAGE_REMOVED/ADDED → `RestartReceiver` started proc → `KioskWebViewActivity` relaunched) — so command-polling, install, and restart are all functioning normally on this device right now.
- **Root cause found for the missing `restart` server-log event**: `ConnectivityService` logged `tryFailover: set mActiveDefaultNetwork=-1, prevNetType=9` (network type 9 = ETHERNET) at **05:49:45 and 05:59:50 BKK** on 2026-09-14 — i.e. the device's default network route dropped/failed over twice, bracketing the exact 05:50–06:10 BKK restart-alarm window. The local `ACTION_RESTART` alarm and process restart succeeded (per dumpsys/logcat above), but the `logAlarmEventSync` HTTP POST to the Cloudflare Worker almost certainly failed silently because there was no active default network route at that moment. This is a **local Ethernet/PoE connectivity blip specific to Mocha's port**, not an AlarmManager or APK logic bug. Only one morning's data point was available in the retained logcat buffer (buffer covered 09-13 20:30 through the time of inspection), so recurrence across multiple mornings is not yet confirmed — needs another ADB check on a subsequent morning to see if `tryFailover` recurs at the same 05:50–06:10 window before calling this the confirmed cause of Mocha's alarm-log gaps.
- agent action: Do not classify Mocha's `restart` alarm gaps as "AlarmManager chain broken" going forward — reclassify as candidate "network failover at restart-alarm window" pending a second confirmed occurrence. Escalation path if it recurs: check the Mocha PoE switch port / Ethernet cabling, not the tablet software.

### [2026-09-12] OTA sent previous run did not change apkVersion
- status: candidate
- seenCount: 1
- firstSeen: 2026-09-12 19:56 BKK (evening run)
- evidence: Fix-log showed `perform_update` sent to Affogato and Decaffinato at 2026-09-12T10:25 UTC (17:25 BKK) targeting v5.89 (`actualOutcome` was still null). At the evening check (12:56 UTC / 19:56 BKK), both tablets were online (heartbeat <6 min old) but `apkVersion` still read 5.88 — no version change occurred despite the command being sent and the tablet staying reachable. Target has since moved to 5.90.
- hypothesis: Unconfirmed — could be OTA command not delivered, `perform_update` silently failing client-side, or target version changing before the update cycle completed. No ADB evidence.
- agent action taken: Per script logic (heartbeat age <70min = fix succeeded), previous entries were marked `actualOutcome: "online"` since the tablets are reachable — but this does NOT confirm the OTA itself completed. A fresh `perform_update` was sent this run for both rooms targeting 5.90. If apkVersion is still 5.88 at the next run, escalate — do not just resend silently a third time.
- 2026-09-13 08:26 BKK run: all 5 present tablets (Affogato, Decaffinato, Latte, Macchiato, Viennese) still report apkVersion 5.88; `perform_update` re-sent to all 5 targeting 5.90. This is the first run since 5.90 was cut (2026-09-12), so 5.88 here is expected, not a repeat failure. Verify apkVersion advanced to 5.90 at the next run — if still 5.88 then, this becomes a second occurrence of the stuck-OTA pattern.
- fix outcome (2026-09-14 09:30 BKK run): **RESOLVED for the 5 Office tablets** — Affogato, Decaffinato, Latte, Macchiato, Viennese all show apkVersion 5.90 with matching `ota_install` events at 2026-09-14 06:00 BKK. The overnight OTA cycle worked as designed — no stuck-OTA pattern for these 5. **Mocha is a new instance**: apkVersion has been stuck at 5.88 since its last `ota_install` on 2026-09-10, i.e. 4+ days and at least 2 prior `perform_update` sends with no version change — this is a stronger case than the original 1-occurrence pattern above. Given Mocha also has a concurrent alarm-chain gap (see the Mocha entry above), the two symptoms may share a root cause (AlarmManager/OTA-install chain broken on this specific device) rather than being independent. Do not resend a 4th time without also flagging for ADB per the runbook if 5.88 persists at the next run.

### [2026-09-13] Scheduled runs firing at wrong BKK wall-clock time vs. their own label
- status: candidate (strong — 3 occurrences, recommend user-side cron review)
- seenCount: 3
- firstSeen: 2026-09-13 08:26 BKK
- lastSeen: 2026-09-16 15:15 BKK
- evidence: The scheduled task invocation's own header text said "EVENING run (21:00 BKK)" but the diagnostics fetch and system clock at execution time were 2026-09-13T01:26 UTC = 08:26 BKK — a morning time, not evening. Fix-log/reload steps in the scheduled task body were skipped in favor of the retired-fix-log / no-reload rules in this knowledge base (see Agent behaviour section) since those are newer and postdate the task file.
- second occurrence (2026-09-16, ris-health-evening task): Same task label ("EVENING run (21:00 BKK)") fired with system clock at 2026-09-16T08:13:29 UTC = 15:13 BKK — an afternoon time, neither the 08:00 morning slot nor the 21:00 evening slot. This is a different offset from the first occurrence (08:26 BKK vs 15:13 BKK), which argues against a fixed timezone-offset misconfiguration (e.g. a constant UTC/BKK confusion would reproduce the same wall-clock time each time) and more toward inconsistent/drifting scheduling of the underlying cron trigger.
- third occurrence (2026-09-16, ris-health-morning task, this run): The MORNING-labeled task fired in the SAME real-world window as the second occurrence above — `date -u` read 2026-09-16T08:15:28Z = 15:15 BKK, essentially simultaneous with the mislabeled "evening" run (15:13 BKK, same day). Two differently-labeled scheduled tasks (morning vs. evening) both actually fired around 15:13–15:15 BKK today, ~2.5 hours apart from each other in wall-clock terms but both far from their respective 08:00/21:00 BKK targets. This rules out a per-task static offset and points at the scheduler itself (both tasks' triggers, or the clock/timezone it evaluates them against) rather than either task's individual config.
- hypothesis: Unconfirmed — evidence now spans 2 differently-named scheduled tasks and 3 firings, none matching their intended BKK slot, with no consistent constant offset. Root mechanism (scheduler bug, host clock/timezone drift, or both tasks sharing a broken trigger definition) not yet inspected directly.
- agent action: None — flagged for user review. Recommend inspecting both `ris-health-morning` and `ris-health-evening` scheduled task configs directly (e.g. via the scheduled-tasks management tools) rather than waiting for further occurrences; 3 firings with no working pattern already exceeds the usual promote-at-3 threshold, but this candidate does not fit this agent's tablet-focused root-cause tables (6c/6d) so it is not being force-promoted there.

### [2026-09-16] Fleet-wide missing `standby` event on 2026-09-15
- status: candidate
- seenCount: 1
- firstSeen: 2026-09-16 15:13 BKK (observed retrospectively from alarmLog)
- evidence: In the 2026-09-16 15:13 BKK run, all 6 Office tablets (Affogato, Decaffinato, Latte, Macchiato, Mocha, Viennese) show a `standby` event for 2026-09-14 13:30 UTC (20:30 BKK) but NONE show a `standby` event for 2026-09-15 13:30 UTC (20:30 BKK) — the entry is absent from the alarmLog array between the surrounding chronological entries (not just outside a length cap, since neighboring entries on both sides are present). All 6 tablets were online and healthy (apkVersion 5.96–5.99) through that window per heartbeatHistory. Today's (2026-09-16) standby window has not yet occurred at the time of this run (15:13 BKK), so it cannot be evaluated yet.
- hypothesis: Unconfirmed. A single-day, fleet-wide (all 6 simultaneously) gap points away from a per-device APK/AlarmManager bug and toward something shared — e.g. a brief Cloudflare Worker/KV outage, a network blip at the office gateway around 20:20–20:40 BKK on 2026-09-15, or the same underlying scheduling irregularity as the "Scheduled run fired at wrong time" candidate above. No ADB or Cloudflare Worker log evidence collected yet.
- agent action: None taken (no fix exists for a logging gap). Flagged for user review. If a future run shows another fleet-wide gap on the same event, promote toward confirmed and note the recurring weekday/time.

### [2026-09-16] Fleet-wide missing `restart` event on 2026-09-16 + wake fired 30min early
- status: candidate
- seenCount: 1
- firstSeen: 2026-09-16 15:15 BKK (ris-health-morning run)
- evidence: All 6 Office tablets (Affogato, Decaffinato, Latte, Macchiato, Mocha, Viennese) are missing today's `restart` event (expected 2026-09-15T23:00 UTC / 06:00 BKK window). All 6 are on target APK 5.99 and otherwise healthy (heartbeatAgeMin 13–44 min at run time). All 6 DID log a `wake` event today, but at 2026-09-16T00:00:0x UTC (07:00 BKK) — 30 min earlier than the documented 07:20–07:40 window and earlier than their own historical pattern (previous days' `wake` entries cluster around 00:30 UTC / 07:30 BKK). All 6 fired within seconds of each other, and within seconds of UTC midnight specifically.
- Macchiato-specific: also missing `restart` for 2026-09-15 (yesterday) — a 2-consecutive-day gap for this tablet alone, on top of today's fleet-wide gap.
- Mocha-specific: continues its pre-existing candidate pattern ("network failover at restart-alarm window", first logged 2026-09-14) — another `restart` gap this morning, no new ADB evidence collected this run.
- hypothesis: Unconfirmed. A same-second, fleet-wide gap in one event (`restart`) plus a fleet-wide 30-min-early shift in another event (`wake`) on the same morning suggests a shared cause — e.g. a Cloudflare Worker/KV write hiccup at 2026-09-15T23:00Z, or an upstream time-sync/AlarmManager scheduling change pushed to all tablets around the same OTA cycle — rather than 6 independent per-device faults. Possibly related to the same scheduling irregularity noted in the "Scheduled runs firing at wrong BKK wall-clock time" candidate above (unconfirmed link — both are timing anomalies observed the same day, no causal evidence connects the Claude Code task scheduler to the tablets' own AlarmManager).
- agent action: No OTA sent (all 6 already on target 5.99). No reload sent (per self-heal philosophy). Flagged for user review / ADB investigation per the runbook — check `dumpsys alarm` on any of the 6 for the ACTION_RESTART registration, and Cloudflare Worker logs around 2026-09-15T23:00Z for write errors on the alarm-log endpoint.

### [2026-09-21] Incident reports — KV write cost and OTA relationship
- status: confirmed
- confirmedOn: 2026-09-21
- evidence: Worker code audited — `handleIncidentReport` and `handleAdminUpdate` are independent paths; no incident type sets `cmd:perform_update:*` keys.
- KV writes per incident type:
  - `standby_failure`: 5 writes — incident record, `standby_open:room`, `incident_active:room`, `hb_history:room` snapshot, `incidents_index`
  - `unexpected_reboot`: 2 writes — incident record, `incidents_index`
  - All other types: 2 writes — incident record, `incidents_index`
- OTA trigger path: `perform_update` is ONLY set by (a) dashboard "Update all" button → `handleAdminUpdate`, or (b) health agent version-comparison logic. Zero incident types trigger OTA. Incidents are write-and-log only.
- `incident_active` flag: read on every heartbeat to gate `hb_history` writes during active incidents. Does NOT trigger OTA or any agent action.
- Weekend flood risk: `unexpected_reboot` fires for every boot outside 05:45–06:15 BKK window (Level 2 escalation reboots). With 12 tablets this can spike writes. Decision (2026-09): these are informative only — Worker logs them, health agent takes no action. Already implemented — no code change needed.
- agent action: Do NOT expect incident reports to trigger OTA. Track unexpected_reboot clusters to identify which rooms are escalating excessively.

### [2026-09-22] False `unexpected_reboot` — activity recreation misread as device reboot
- status: confirmed
- seenCount: 1 severe (Viennese, 11 events) + 6/day fleet-wide at 07:30
- firstSeen: 2026-09-22 01:15 BKK (severe); 07:30 variant daily since 2026-09-19
- confirmedOn: 2026-09-22
- evidence: Viennese logged 11 `unexpected_reboot` 01:15–05:20 BKK. `/data/system/dropbox/` held only TWO `SYSTEM_BOOT` entries (Sep 21 06:01, Sep 22 06:01) — the scheduled cold reboots. Zero `cold_boot` alarm events between 00:52 and 06:01. No tombstones. Device never rebooted.
- root cause: `ForegroundWatchService.checkAndRestore()` had no standby-window gate (unlike `checkHeartbeat()` and `checkEscalation()`, which both gate on `timeBKK < 730 || >= 2030`). When StandbyActivity left the foreground it called `BootReceiver.launchWebView()`, whose `FLAG_ACTIVITY_CLEAR_TASK` destroys and recreates the activity → fresh WebView → page load with no `ris_reload_reason` flag → JS boot detector reported a device reboot.
- second root cause: JS wake-suppression window was `_mb<=10` (07:00–07:10 BKK) but the WAKE alarm moved to 07:30 BKK in APK v5.108 (2026-09-19). Every tablet loading the page at wake filed a false incident — 6 rooms/day.
- why only LG: `checkAndRestore()` returns early on API 21+, so Latte (Android 10) is immune.
- retry interaction: during `standby_retry` 1–3, `launchStandby()` CLEAR_TASKs the kiosk away before the page finishes loading, so no incident posts. Once the 3-retry budget is spent, the kiosk page survives and starts posting. That is why false reboots began at 01:15, 23 min after the 00:52 `standby_failure`.
- fix: APK 5.110 — `checkAndRestore()` relaunches StandbyActivity during standby; `Android.getDeviceUptimeMs()` added. Web 3.10.214 — reports `kiosk_relaunch` when uptime > 5 min; wake window moved to 00:30 UTC ±15.
- agent action: Treat `unexpected_reboot` as real ONLY if a matching `cold_boot` alarm event exists within ~2 min. Otherwise it is an activity recreation. `kiosk_relaunch` (v3.10.214+) is the correctly-labelled version and is informational.
- open question: what displaced StandbyActivity at 00:05:03/05/07 BKK on Viennese, Decaffinato and Mocha simultaneously (3 of 12, all LG). Decaf and Mocha recovered on retry 1; Viennese did not. Not explained by any Worker cron (crons are 01:00/14:00 UTC and the handler is a no-op). Also unexplained: the ~25-min spacing of the false reboots when the service loop is 5 min.

### [2026-09-22] Dashboard grouped incidents by UTC date
- status: confirmed
- confirmedOn: 2026-09-22
- evidence: `renderAdminIncidents` sliced `inc.reportedAt` (UTC) but compared against a BKK-derived `today`. Everything 00:00–07:00 BKK was filed under the previous day. The `Resolve all` filter had the same bug and silently skipped those incidents.
- fix: both call sites shift to BKK before slicing. The incident KEY stays UTC-sliced — that is how the Worker builds it (`now.toISOString().slice(0,10)`). Do not "fix" the key.

---

## Retired Patterns

<!-- Patterns not seen in 30+ days are moved here by the agent. -->
