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

Future rollout (USB debugging enabled as of 2026-09-10; all on PoE — whether debugging persists across PoE cycle is unconfirmed, see candidate pattern above):
- Doppio:      10.0.54.81
- Cappuccino:  10.0.54.85
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

## Confirmed Patterns (continued)

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

## Candidate Patterns

<!-- Agent appends here when new unclassified patterns are observed. -->
<!-- Format: ### [YYYY-MM-DD] [RoomName] — [brief description] -->
<!-- Fields: status, seenCount, firstSeen, lastSeen, evidence, hypothesis, fix applied, fix outcome -->

---

## Retired Patterns

<!-- Patterns not seen in 30+ days are moved here by the agent. -->
