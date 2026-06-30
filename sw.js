// sw.js — RIS Room Display Service Worker
// VERSION must be bumped on every deploy — triggers cache clear on all clients
// Match this to APP_VERSION in index.html
var CACHE_VERSION = 'ris-v3.10.50';
var CACHE_NAME = CACHE_VERSION;

var CACHE_FILES = [
  './',
  './index.html',
  './ris-shared.js',
];

// Install — cache core files + skipWaiting to activate immediately
// Network-first fetch means new code always served from network — no disruption risk
self.addEventListener('install', function(e) {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE_NAME).then(function(cache) {
      return cache.addAll(CACHE_FILES);
    }).catch(function(err) {
      console.log('SW cache install failed:', err);
    })
  );
});

// Activate — delete ALL old caches, take control of any open pages.
// NOTE: clients.claim() here no longer triggers any reload logic —
// index.html (v3.10.50+) detects new versions itself via direct fetch
// version-check, not via SW lifecycle events (Chrome 42 has unreliable
// SW spec support). This SW now exists purely for offline fallback
// caching — see the fetch handler below.
self.addEventListener('activate', function(e) {
  e.waitUntil(
    Promise.all([
      caches.keys().then(function(keys) {
        return Promise.all(
          keys.filter(function(key) {
            return key !== CACHE_NAME;
          }).map(function(key) {
            console.log('SW deleting old cache:', key);
            return caches.delete(key);
          })
        );
      }),
      self.clients.claim()
    ])
  );
});

// Fetch — network first, fall back to cache
// Network-first ensures latest version is always served when online
self.addEventListener('fetch', function(e) {
  if (e.request.method !== 'GET') return;
  if (e.request.url.indexOf(self.location.origin) === -1) return;

  e.respondWith(
    fetch(e.request).then(function(response) {
      if (response && response.status === 200) {
        var clone = response.clone();
        caches.open(CACHE_NAME).then(function(cache) {
          cache.put(e.request, clone);
        });
      }
      return response;
    }).catch(function() {
      return caches.match(e.request).then(function(cached) {
        return cached || new Response('Offline', { status: 503 });
      });
    })
  );
});
