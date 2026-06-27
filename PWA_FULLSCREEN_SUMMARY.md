# RIS Room Display — PWA Auto-launch Fullscreen Summary

## Goal
Launch the kiosk app in fullscreen automatically when opened from home screen icon on Android (LG Chrome 42) — no tap, no PIN, no URL bar.

## Three-layer approach (all in place as of v3.10.14)

### Layer 1 — manifest.json (modern Android)
Tells Chrome to launch as fullscreen PWA when added to home screen.
```json
{
  "name": "RIS Room Display",
  "short_name": "RIS Rooms",
  "start_url": "./index.html",
  "display": "fullscreen",
  "orientation": "portrait",
  "background_color": "#040810",
  "theme_color": "#0a0a12",
  "scope": "/Display-Room/",
  "icons": [
    {"src": "icon-192.png", "sizes": "192x192", "type": "image/png"},
    {"src": "icon-512.png", "sizes": "512x512", "type": "image/png"}
  ]
}
```
Referenced in index.html head:
```html
<link rel="manifest" href="manifest.json">
```
**Effect:** Chrome hides URL bar + browser UI on launch. Works on Android 5+, Lenovo, iPhone.

---

### Layer 2 — Auto-fullscreen on PWA launch (index.html)
Detects if running as standalone PWA via `matchMedia`. If yes, calls `doFullscreen()` automatically — no user tap needed.

```javascript
function enterKiosk(){
  var kioskOn = true;
  try{ kioskOn = localStorage.getItem('roomdisplay_kiosk') !== '0'; }catch(e){}
  if(!kioskOn) return;

  // Block Android back button
  window.history.pushState(null, '', window.location.href);
  window.onpopstate = function(){ window.history.pushState(null, '', window.location.href); };

  // Already fullscreen — nothing to do
  if(!!(document.fullscreenElement || document.webkitFullscreenElement)) return;

  // Detect PWA standalone mode
  var isStandalone = false;
  try{
    isStandalone = window.matchMedia('(display-mode: standalone)').matches
      || window.matchMedia('(display-mode: fullscreen)').matches
      || window.navigator.standalone === true;
  }catch(e){}

  if(isStandalone){
    // PWA launch — auto fullscreen, no tap needed
    setTimeout(function(){
      doFullscreen();
      var ov = el('fs-overlay');
      if(ov) ov.style.display = 'none';
    }, 600);
    return;
  }

  // Browser launch — show tap-anywhere overlay (no PIN required)
  var ov = el('fs-overlay');
  if(ov){ ov.style.display = '-webkit-flex'; ov.style.display = 'flex'; }
}
```

Called from `launch()` after successful auth and calendar init.

---

### Layer 3 — Auto-fullscreen after MSAL redirect (index.html)
After Microsoft login redirect completes, automatically enters fullscreen.
No need to tap or re-enter PIN after token expiry triggers reauth.

```javascript
// Inside handleRedirectCallback, after launch():
setTimeout(function(){
  doFullscreen();
  var ov = el('fs-overlay');
  if(ov) ov.style.display = 'none';
}, 800);
```

---

### Layer 4 — doFullscreen() (ES5, Chrome 42 compatible)
```javascript
function doFullscreen(){
  var de = document.documentElement;
  if(de.requestFullscreen) de.requestFullscreen();
  else if(de.webkitRequestFullscreen) de.webkitRequestFullscreen();
  else if(de.mozRequestFullScreen) de.mozRequestFullScreen();
  else if(de.msRequestFullscreen) de.msRequestFullscreen();
}
```
Must be called from a user gesture OR from a timer after redirect (Chrome 42 allows this).

---

### Tap-anywhere overlay (fallback for browser launch)
HTML — tap anywhere triggers fullscreen, no PIN:
```html
<div id="fs-overlay" onclick="doFullscreen(); this.style.display='none';">
  <div class="fs-icon">&#9974;</div>
  <div class="fs-title">Tap anywhere to continue</div>
  <div class="fs-sub">
    Tap once to enter fullscreen.<br><br>
    For best experience: Chrome menu → Add to Home Screen,
    then launch from home screen icon for automatic fullscreen.
  </div>
</div>
```

---

## How to add to home screen on LG tablet (Chrome 42)
1. Open `https://pom7220.github.io/Display-Room/` in Chrome
2. Tap Chrome menu (⋮) → **"Add to home screen"**
3. Name it "RIS Rooms" → Add
4. Icon appears on home screen
5. Tap icon → launches fullscreen automatically (Layer 1 + 2 activate)

---

## Behaviour summary by scenario

| Launch method | URL bar | Fullscreen method | User action needed |
|---|---|---|---|
| Home screen icon (Android 5+) | Hidden by manifest | Layer 1 (manifest) | None |
| Home screen icon (Chrome 42) | Hidden after Layer 2 | Layer 2 (matchMedia) | None — auto after 600ms |
| Browser URL | Visible | Layer 3 (tap overlay) | Single tap anywhere |
| After token expiry redirect | Hidden | Layer 3 (auto after redirect) | None |
| Settings gear → Save & Launch | Visible briefly | Layer 3 (auto after 800ms) | None |

---

## APK Boot Launcher (additional layer)
Package: `th.co.central.ris.bootlauncher`
On tablet reboot → launches Chrome → opens `https://pom7220.github.io/Display-Room/`
Combined with home screen icon setup → fully automatic fullscreen on every reboot.

---

## Files involved
- `manifest.json` — PWA manifest, upload to repo root
- `index.html` — contains enterKiosk(), doFullscreen(), fs-overlay HTML
- `sw.js` — service worker, no skipWaiting/clients.claim (prevents mid-session disruption)

## Current status (v3.10.14)
- ✅ manifest.json deployed
- ✅ Auto-fullscreen on PWA launch (Layer 2)
- ✅ Auto-fullscreen after redirect (Layer 3)
- ✅ Tap-anywhere overlay (no PIN for initial fullscreen)
- ⏳ Verify on LG tablet after next reboot with clean URL
EOF