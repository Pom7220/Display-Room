# RIS Health Agent — Knowledge Base

Patterns accumulated from live runs AND manual debug sessions.
Agent reads this at the start of every run and self-amends when patterns are promoted or retired.
**Convention:** At the end of every manual debug session with Claude, update this file before closing the session. Human review recommended when a candidate reaches 3+ occurrences without a clear fix.

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

### [2026-09-10] USB debugging reset on PoE cycle (Android 4.4.2)
- status: confirmed
- confirmedOn: 2026-09-10
- evidence: Viennese ADB session lost after PoE recycle. USB debugging flag cleared by Android 4.4.2 on power loss — expected OS behaviour.
- root cause: Android 4.4 does not persist USB debugging state across power cycles.
- agent action: If ADB investigation is needed after a PoE cycle, USB debugging must be re-enabled physically on the tablet before connecting.
- adb note: See ADB Manual Investigation runbook below.

### [2026-09-10] `ota_install` event confirms APK update completed
- status: confirmed
- confirmedOn: 2026-09-10
- evidence: All 6 tablets logged `ota_install` with `apkVersion: 5.88` within 30 seconds of "Update all" trigger at 08:43–44 BKK. `RestartReceiver.onReceive(MY_PACKAGE_REPLACED)` fires in the newly installed version — reliable OTA completion signal.
- root cause: N/A — this is a positive signal, not a failure pattern.
- agent action: After any OTA run, confirm `ota_install` appears in each updated tablet's alarmLog within 15 min. If absent after 15 min, OTA may have silently failed — flag for retry.

---

## ADB Manual Investigation Runbook

Use when the agent flags a case as "needs ADB" — pattern not explainable from Worker data alone.

**Prerequisites:**
- ADB platform tools: `C:\TEMP\platform-tools\adb.exe`
- Tablet IP address (see tablet-ips below)
- USB debugging must be enabled on the tablet (re-enable physically if PoE cycled)

**Known tablet IPs** (update if DHCP changes):

Active (current 6):
- Affogato:    10.0.54.111
- Mocha:       10.0.54.110
- Latte:       10.0.54.72
- Decaffinato: 10.0.54.108
- Viennese:    10.0.54.107
- Macchiato:   10.0.54.101

Future rollout:
- Doppio:      10.0.54.81
- Cappuccino:  10.0.54.72  ⚠️ same IP as Latte — verify before use
- Americano:   10.0.54.10
- Lungo:       10.0.54.73
- Ristretto:   10.0.54.79
- Espresso:    10.0.54.112

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

## Candidate Patterns

<!-- Agent appends here when new unclassified patterns are observed. -->
<!-- Format: ### [YYYY-MM-DD] [RoomName] — [brief description] -->
<!-- Fields: status, seenCount, firstSeen, lastSeen, evidence, hypothesis, fix applied, fix outcome -->

---

## Retired Patterns

<!-- Patterns not seen in 30+ days are moved here by the agent. -->
