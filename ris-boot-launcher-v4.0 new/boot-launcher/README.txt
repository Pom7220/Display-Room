# RIS Kiosk Boot Launcher v1.3
## Build & Install Instructions

This APK auto-launches Chrome to the RIS Room Display URL
every time the LG tablet reboots.

---

## What it does
- On first open: shows room picker (12 RIS rooms)
- Saves selected room to SharedPreferences
- On boot: launches Chrome with room config in URL params
- Config survives reboots regardless of Chrome context (PWA vs browser)
- Falls back to default browser if Chrome not found

## URL
- Opens: https://ris-display.ris-display.workers.dev/
- Appends: ?room=<email>&roomname=<name>&approval=0|1

---

## Build requirements
- Android SDK compileSdkVersion 29
- Gradle 7.2
- AGP 7.0.4
- Java JDK 8 or later

---

## Install
1. Build APK via GitHub Actions or locally
2. Copy to USB drive
3. Install on LG tablet from File Manager
4. Open "RIS Kiosk" → tap the room for this tablet
5. Tap "Launch Room Display"
6. On every subsequent reboot, Chrome auto-opens with correct room

