# Status Card Availability Logic & APK Version Monitoring

**Date:** 2026-07-10  
**Status:** Approved for implementation

---

## Problem

1. The status card always shows the next meeting title/time even when that meeting is hours away, making the room appear occupied to people walking by looking for a free room now.
2. The admin dashboard "Index outdated" tile is not actionable — staff need to know which tablets have an outdated **APK** so they can physically walk to them and trigger an update.

---

## Design

### 1. Status Card — 15-Minute Threshold

The status card (`#cur-card`) only shows meeting details when action is required now:

| Condition | Card content | Theme |
|-----------|-------------|-------|
| Meeting in progress (`cur`) | IN USE + title + time + buttons | 🔴 Red |
| Next meeting ≤15min away | UPCOMING + title + time + Check In | 🟠 Orange |
| Next meeting >15min away | **Available until HH:MM** | 🟢 Green |
| No meetings today | **Available all day** | 🟢 Green |

The "SOON" display range (previously 15–30min) is removed. The card is either Available (green) or actionable (orange/red).

### 2. Available State — Visual Treatment

When showing Available, the time or phrase is the hero element — large, bold, bright blue — so people scanning quickly can read the key info at a glance:

```
FREE
Available until
14:30          ← same large bold font as meeting title, bright blue (#64b5f6)
```

```
FREE
Available
all day        ← "all day" in same large bold blue
```

### 3. Dashboard Color Consistency

`deriveRoomStatus()` in `ris-shared.js` drives the heartbeat `status` field used by the dashboard dots. Change the SOON threshold from **30min → 15min** so dashboard colors match what the kiosk card shows. A room with a meeting 20min away reports "available" (green dot) on both surfaces.

### 4. APK Version in Heartbeat

**Problem:** Web content doesn't know the Android APK version.  
**Solution:** `KioskWebViewActivity.loadDisplay()` appends `&apkversion=5.36` to the WebView URL. `index.html` reads it from `URLSearchParams` on load and stores it in the config. The heartbeat payload includes `apkVersion: cfg.apkVersion`.

Worker stores `apkVersion` alongside the existing heartbeat fields in KV.

### 5. Admin Dashboard — APK Outdated Tile

Replace the "Index outdated" tile with **"APK outdated"**:
- Fetch latest APK version from `/api/version` (already exists)
- Compare each room's `r.apkVersion` against `latestApkVersion`
- Tile shows count of online tablets with outdated APK
- Each room card shows `⚠️ APK X.XX` in amber when behind

---

## Files Changed

| File | Change |
|------|--------|
| `ris-shared.js` | SOON threshold 30 → 15min |
| `index.html` | Card logic, Available visual treatment, read+send apkVersion in heartbeat |
| `KioskWebViewActivity.java` | Append `&apkversion=` to WebView URL |
| `dashboard.html` | Swap tile to APK outdated, compare apkVersion |
| `cloudflare-worker.js` | Store apkVersion from heartbeat payload |
| `index-version.json` | Version bump |
| `apk-version.json` + `build.gradle` | APK version bump |
