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
- Doppio:      10.0.54.101  (ADB authorized, v5.100 via CI/Update All)
- Cappuccino:  10.0.54.102  (ADB authorized, v5.96 — OTA to 5.99/5.100 failing, investigate on-site 2026-09-17)
- Americano:   10.0.54.103  (app NOT yet deployed — lobby tablet)
- Lungo:       10.0.54.104  (app NOT yet deployed — lobby tablet)
- Ristretto:   10.0.54.105  (app NOT yet deployed — lobby tablet)
- Macchiato:   10.0.54.106  (RESOLVED 2026-09-15 — boot loop fixed via su -c pm clear com.lge.lgkioskmode; v5.100 via Update All)
- Viennese:    10.0.54.107  (RESOLVED 2026-09-15 — boot loop fixed via physical USB install + pm clear; v5.100 via Update All)
- Decaffinato: 10.0.54.108  (ADB unauthorized, v5.100 via Update All)
- Latte:       10.0.54.109  (ADB authorized, v5.100 via Update All; Android 10 — Device Admin activation needed for DPM.reboot())
- Mocha:       10.0.54.110  (ADB authorized, v5.100 via Update All)
- Affogato:    10.0.54.111  (ADB authorized, v5.100 via Update All)
- Espresso:    10.0.54.112  (app NOT yet deployed — lobby tablet)

Note: All IPs changed by NW team on 2026-09-14. Old mapping is obsolete. Lobby tablets (.103–.105, .112) on separate VLAN with FortiGate HTTPS interception issue — not yet deployed. No LGKioskMode risk on lobby tablets.

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

## Confirmed Patterns (continued)

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

---

## Retired Patterns

<!-- Patterns not seen in 30+ days are moved here by the agent. -->
