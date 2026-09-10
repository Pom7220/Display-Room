# Tablet Rollout Runbook

Follow this for every new tablet. Each step is confirmed from the Doppio rollout (2026-09-10).
Target time per tablet: **10–15 minutes** once FortiGate is updated.

---

## Prerequisites (do once, before rolling out any tablet)

### 1. FortiGate — add new tablet IPs to Cloudflare bypass rule

**Critical: without this, the APK will see an HTML page instead of JSON from Cloudflare, and the WebView will show a white screen.**

Ask the network engineer to add these IPs to the FortiGate SSL inspection bypass rule for Cloudflare (same rule that covers the 6 active tablets):

| Room       | IP            |
|------------|---------------|
| Doppio     | 10.0.54.81    |
| Cappuccino | 10.0.54.85    |
| Americano  | 10.0.54.10    |
| Lungo      | 10.0.54.73    |
| Ristretto  | 10.0.54.79    |
| Espresso   | 10.0.54.112   |

Do not proceed with any tablet until the network engineer confirms the rule is updated.

---

## Per-tablet rollout steps

### 2. Verify room email exists in Microsoft 365

Confirm `ris<roomname>@central.co.th` exists in Exchange (e.g. `risdoppio@central.co.th`).
Check in Microsoft 365 Admin → Exchange → Recipients.
All 6 new tablet rooms were confirmed present on 2026-09-10.

### 3. ADB connect

```
C:\TEMP\platform-tools\adb.exe connect <IP>:5555
```

If `device unauthorized`: a dialog appears on the tablet — tap **"Always allow from this computer"** (NOT "Allow once" — that expires on reboot).

If connection refused: USB Debugging not enabled on the tablet. Enable via Settings → Developer Options → USB Debugging, then retry.

### 4. Install APK

```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 install -r "ris-boot-launcher-VORUTCHAPON.apk"
```

Wait for `Success`. Takes ~30 seconds.

### 5. Set SharedPreferences

The APK reads room email and room name from SharedPreferences. Set them via ADB:

```powershell
# Step 5a — write prefs file to sdcard
$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="room_email">ris<roomname>@central.co.th</string>
    <string name="room_name"><RoomName></string>
</map>
"@
$xml | Out-File -FilePath "C:\TEMP\platform-tools\ris_kiosk_prefs.xml" -Encoding ascii

# Step 5b — push to sdcard
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 push C:\TEMP\platform-tools\ris_kiosk_prefs.xml /sdcard/ris_kiosk_prefs.xml

# Step 5c — copy to app data dir and fix ownership
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell "su -c 'cp /sdcard/ris_kiosk_prefs.xml /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chown u0_a49 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ && chown u0_a49 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml && chmod 771 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ && chmod 660 /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'"
```

Replace `<roomname>` (lowercase) and `<RoomName>` (display name) with the room's values.

Example for Cappuccino:
- `room_email`: `riscappuccino@central.co.th`
- `room_name`: `Cappuccino`

### 6. Verify prefs were written correctly

```
C:\TEMP\platform-tools\adb.exe -s <IP>:5555 shell "su -c 'cat /data/data/th.co.central.ris.bootlauncher/shared_prefs/ris_kiosk_prefs.xml'"
```

Expected output (no BOM, correct email and name):
```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="room_email">ris<roomname>@central.co.th</string>
    <string name="room_name"><RoomName></string>
</map>
```

### 7. Launch room display

In the APK's room picker (opens automatically after install or after step 5), tap **"Launch Room Display"**. The room display should load within 5 seconds.

If it shows white: FortiGate bypass is not yet active for this IP. Wait for network engineer and retry.

### 8. Verify heartbeat appears in dashboard

Check the admin dashboard at `https://ris-display.ris-display.workers.dev/` — the new room should appear with a fresh heartbeat within 2 minutes.

### 9. ADB disconnect

```
C:\TEMP\platform-tools\adb.exe disconnect <IP>:5555
```

---

## Room reference

| Room       | IP            | Email                          |
|------------|---------------|-------------------------------|
| Doppio     | 10.0.54.81    | risdoppio@central.co.th        |
| Cappuccino | 10.0.54.85    | riscappuccino@central.co.th    |
| Americano  | 10.0.54.10    | risamericano@central.co.th     |
| Lungo      | 10.0.54.73    | rislungo@central.co.th         |
| Ristretto  | 10.0.54.79    | risristretto@central.co.th     |
| Espresso   | 10.0.54.112   | risespresso@central.co.th      |

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| White screen, "Update check failed: <html>" in room picker | FortiGate bypass not set for this IP | Network engineer adds IP to bypass rule |
| `device unauthorized` on ADB connect | "Allow once" was tapped instead of "Always allow" | Reconnect ADB, tap "Always allow" on tablet |
| Connection refused on ADB connect | USB debugging disabled | Enable in Settings → Developer Options |
| Prefs file not readable (empty room email) | File owned by root instead of app user | Run step 5c chown commands |
| Meet in Touch blocking foreground | Meet in Touch single-app mode active | `adb shell su -c 'pm disable me.exzy.meetingroom'` then reboot |
