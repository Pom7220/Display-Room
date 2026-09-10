# RIS Health Agent — Knowledge Base

Patterns accumulated from live runs. Agent reads this at the start of every run.
Self-amended by the agent when patterns are promoted (confirmed) or retired.
Human review recommended when a candidate reaches 3+ occurrences without a clear fix.

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

---

## Candidate Patterns

<!-- Agent appends here when new unclassified patterns are observed. -->
<!-- Format: ### [YYYY-MM-DD] [RoomName] — [brief description] -->
<!-- Fields: status, seenCount, firstSeen, lastSeen, evidence, hypothesis, fix applied, fix outcome -->

---

## Retired Patterns

<!-- Patterns not seen in 30+ days are moved here by the agent. -->
