# Tablet Rollout Runbook

Follow this for every new tablet. Each step is confirmed from multiple rollouts.
Target time per tablet: **10–15 minutes**.

> **APK source:** After every push to `boot-launcher/`, CI builds and commits `ris-boot-launcher.apk` back to the repo root. Always `git pull` first to get the latest CI-built APK before sideloading. **Never use the local `ris-boot-launcher-VORUTCHAPON.apk`** — that is a local dev build, not the CI release.

---

## Prerequisites (do once, before rolling out any tablet)

### 1. FortiGate — verify new tablet IP is covered

FortiGate SSL bypass rule covers the entire range **10.0.54.101–120** (confirmed 2026-09-14). All lobby and office tablets fall within this range. No additional NW engineer action needed for new tablets within this range.

If a tablet IP falls outside 10.0.54.101–120, ask the network engineer to add it to the SSL inspection bypass rule for Cloudflare before proceeding.

---

## Per-tablet rollout steps

### 2. Verify room email exists in Microsoft 365

Confirm `ris<roomname>@central.co.th` exists in Exchange.
Check in Microsoft 365 Admin → Exchange → Recipients.

### 3. Pull latest APK

```
git pull
```

Use `ris-boot-launcher.apk` in the repo root — this is the CI-built release APK.

### 4. ADB connect

```
C:\TEMP\platform-tools\adb.exe connect <IP>:5555
```

If `device unauthorized`: a dialog appears on the tablet — tap **"Always allow from this computer"** (NOT "Allow once" — that expires on reboot).

If connection refused: USB Debugging not enabled. Enable via Settings → Developer Options → USB Debugging.

### 5. Record current UID (before install)

```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell dumpsys package th.co.central.ris.bootlauncher | findstr userId
```

Note the `userId=XXXXX` value. You will verify it hasn't changed after install.

### 6. Install APK

**⚠️ Never use `adb install -r`** — the `-r` flag can reassign a new UID which breaks prefs ownership (Cappuccino incident 2026-09-17).

For a fresh tablet (no existing install):
```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 install "ris-boot-launcher.apk"
```

Wait for `Success`. Takes ~30 seconds.

### 7. Verify UID unchanged after install

```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell dumpsys package th.co.central.ris.bootlauncher | findstr userId
```

If UID changed from Step 5 → fix prefs ownership before rebooting:
```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell su -c "chown u0_a<N> /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml"
```
(Replace `<N>` with new UID suffix — e.g. `userId=10048` → `u0_a48`)

### 8. Set SharedPreferences

```powershell
# Step 8a — write prefs file to sdcard
$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="room_email">ris<roomname>@central.co.th</string>
    <string name="room_name"><RoomName></string>
</map>
"@
$xml | Out-File -FilePath "C:\TEMP\platform-tools\ris_kiosk_prefs.xml" -Encoding ascii

# Step 8b — push to sdcard
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 push C:\TEMP\platform-tools\ris_kiosk_prefs.xml /sdcard/ris_kiosk_prefs.xml

# Step 8c — copy to app data dir and fix ownership
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell "su -c 'cp /sdcard/ris_kiosk_prefs.xml /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chown u0_a49 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ && chown u0_a49 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chmod 771 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ && chmod 660 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'"
```

Replace `<roomname>` (lowercase) and `<RoomName>` (display name) with the room's values. Replace `u0_a49` with the actual UID suffix from Step 5/7.

### 9. Disable MEET IN TOUCH

LG Android 4.4 (all tablets except Latte):
```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell su -c "pm disable me.exzy.meetingroom"
```

Android 10 (Latte only):
```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell pm disable-user --user 0 me.exzy.meetingroom
```

### 10. Clear LGKioskMode (LG tablets only — all except Latte)

```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell su -c "pm clear com.lge.lgkioskmode"
```

This prevents the LGKioskMode boot loop (if cold reboot happens after 07:00 BKK, LGKioskMode fires immediately on next boot — clearing the schedule prevents the loop).

### 11. Reboot

```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell su -c "reboot"
```

### 12. Verify heartbeat on dashboard

Check the dashboard — the new room should appear with a fresh heartbeat within ~5 minutes of reboot.

### 13. ADB disconnect

```
C:\TEMP\platform-tools\adb.exe disconnect <IP>:5555
```

---

## Room reference (all 12 rooms — complete as of 2026-09-21)

| Room | IP | Email | Zone | Tablet | APK |
|---|---|---|---|---|---|
| Doppio | 10.0.54.101 | risdoppio@central.co.th | Lobby | LG Android 4.4.2 | ✅ Live |
| Cappuccino | 10.0.54.102 | riscappuccino@central.co.th | Lobby | LG Android 4.4.2 | ✅ Live |
| Americano | 10.0.54.103 | risamericano@central.co.th | Lobby | LG Android 4.4.2 | ✅ Live |
| Lungo | 10.0.54.104 | rislungo@central.co.th | Lobby | LG Android 4.4.2 | ✅ Live |
| Ristretto | 10.0.54.105 | risristretto@central.co.th | Lobby | LG Android 4.4.2 | ✅ Live |
| Espresso | 10.0.54.112 | risespresso@central.co.th | Lobby | LG Android 4.4.2 | ✅ Live |
| Macchiato | 10.0.54.106 | rismacchiato@central.co.th | Office | LG Android 4.4.2 | ✅ Live |
| Viennese | 10.0.54.107 | risviennese@central.co.th | Office | LG Android 4.4.2 | ✅ Live |
| Decaffinato | 10.0.54.108 | risdecaffeinato@central.co.th | Office | LG Android 4.4.2 | ✅ Live |
| Latte | 10.0.54.109 | rislatte@central.co.th | Office | Android 10 | ✅ Live |
| Mocha | 10.0.54.110 | rismocha@central.co.th | Office | LG Android 4.4.2 | ✅ Live |
| Affogato | 10.0.54.111 | risaffogato@central.co.th | Office | LG Android 4.4.2 | ✅ Live |

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| White screen, "Update check failed: `<html>`" | FortiGate bypass not set for this IP | Verify IP is in 10.0.54.101–120 range; if outside, ask NW engineer |
| `device unauthorized` on ADB connect | "Allow once" tapped instead of "Always allow" | Reconnect ADB, tap "Always allow" on tablet |
| Connection refused on ADB connect | USB debugging disabled | Enable in Settings → Developer Options |
| Room prefs not loading (empty room name) | UID mismatch after reinstall, or prefs owned by wrong user | Check UID (Step 7), fix chown |
| Tablet shows "Tap anywhere to continue" after reboot | Prefs file unreadable (UID mismatch) | Fix ownership: `chown u0_a<N>` on prefs file and dir |
| MEET IN TOUCH black screen, no heartbeat | MEET IN TOUCH registered as Device Admin with force-lock | `pm disable me.exzy.meetingroom` then reboot |
| Boot loop (uptime < 90s repeatedly) | LGKioskMode schedule firing immediately after off-hours reboot | `su -c "pm clear com.lge.lgkioskmode"` |
| OTA not firing after "Update all" | Command TTL (30 min) expired before tablet heartbeated | Resend "Update all" while tablet is awake (07:00–20:30 BKK on weekdays) |
