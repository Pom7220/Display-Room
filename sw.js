// RIS Room Display — Service Worker
const CACHE = 'ris-room-display-v1';
const CORE_FILES = ['./', './index.html', './msal-browser.min.js'];

// Install: cache core files
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(CORE_FILES))
      .then(() => self.skipWaiting())
  );
});

// Activate: clean old caches
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// Fetch: network first, fall back to cache
// Always use network for Microsoft Graph and auth calls
self.addEventListener('fetch', e => {
  const url = e.request.url;

  // Never intercept Microsoft/auth calls — always go to network
  if (url.includes('microsoft') || url.includes('msauth') ||
      url.includes('graph.microsoft') || url.includes('login.microsoftonline') ||
      url.includes('open-meteo')) {
    return; // let browser handle normally
  }

  e.respondWith(
    fetch(e.request)
      .then(res => {
        // Cache successful GET responses for our own files
        if (e.request.method === 'GET' && res.status === 200) {
          const clone = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});
