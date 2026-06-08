# RIS Kiosk Boot Launcher
## Build & Install Instructions

This tiny APK (no UI) auto-launches Chrome to the RIS Room Display URL 
every time the LG tablet reboots.

---

## What it does
- Listens for Android BOOT_COMPLETED broadcast
- Waits 8 seconds for WiFi to connect
- Opens Chrome at: https://pom7220.github.io/Display-Room/index.html?v=360608
- Falls back to default browser if Chrome not found
- Has no UI — just shows a one-time toast after install

---

## Build requirements
- Android Studio (any version) OR Android SDK command-line tools
- Java JDK 8 or later
- No internet needed after SDK is installed

---

## Build steps in Android Studio

1. Open Android Studio
2. File → Open → select the `ris-boot-launcher` folder
3. Wait for Gradle sync to complete
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. APK will be at:
   app/build/outputs/apk/debug/app-debug.apk

---

## Build steps via command line (Windows)

cd ris-boot-launcher
gradlew.bat assembleDebug

APK will be at:
app\build\outputs\apk\debug\app-debug.apk

---

## Build steps via command line (Mac/Linux)

cd ris-boot-launcher
chmod +x gradlew
./gradlew assembleDebug

---

## Install on LG tablet

1. Copy app-debug.apk to a USB drive
   OR send via email/WhatsApp to the tablet

2. On LG tablet:
   Settings → Security → enable "Unknown sources"

3. Open the APK file on the tablet → Install

4. A toast message will appear:
   "RIS Kiosk Launcher installed. Chrome will auto-launch on next reboot."

5. The app does NOT appear in the app drawer (no launcher icon)

6. Test: reboot the tablet — Chrome should open the kiosk URL automatically

---

## Update kiosk URL

If the URL changes (e.g. new version cache-bust string), edit:
  app/src/main/java/th/co/central/ris/bootlauncher/BootReceiver.java

Change line:
  private static final String KIOSK_URL = "https://...";

Then rebuild and reinstall.

---

## Uninstall

Settings → Apps → RIS Kiosk Launcher → Uninstall

---

## Package name
th.co.central.ris.bootlauncher
