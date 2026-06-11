// sw.js — RIS Room Display Service Worker
// VERSION must be bumped on every deploy — triggers cache clear on all clients
// Match this to APP_VERSION in index.html
var CACHE_VERSION = 'ris-v3.10.5';
var CACHE_NAME = CACHE_VERSION;

var CACHE_FILES = [
  './',
  './index.html',
  './ris-shared.js',
  './msal-v1.min.js'
];

// Install — cache core files
self.addEventListener('install', function(e) {
  self.skipWaiting(); // Activate new SW immediately without waiting
  e.waitUntil(
    caches.open(CACHE_NAME).then(function(cache) {
      return cache.addAll(CACHE_FILES);
    }).catch(function(err) {
      console.log('SW cache install failed:', err);
    })
  );
});

// Activate — delete ALL old caches
self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(keys) {
      return Promise.all(
        keys.filter(function(key) {
          return key !== CACHE_NAME; // Delete anything that isn't current version
        }).map(function(key) {
          console.log('SW deleting old cache:', key);
          return caches.delete(key);
        })
      );
    }).then(function() {
      return self.clients.claim(); // Take control of all open tabs immediately
    })
  );
});

// Fetch — network first, fall back to cache
// Network-first ensures latest version is always served when online
self.addEventListener('fetch', function(e) {
  // Only handle GET requests for our own origin
  if (e.request.method !== 'GET') return;
  if (e.request.url.indexOf(self.location.origin) === -1) return;

  e.respondWith(
    fetch(e.request).then(function(response) {
      // Cache successful responses
      if (response && response.status === 200) {
        var clone = response.clone();
        caches.open(CACHE_NAME).then(function(cache) {
          cache.put(e.request, clone);
        });
      }
      return response;
    }).catch(function() {
      // Network failed — serve from cache
      return caches.match(e.request).then(function(cached) {
        return cached || new Response('Offline', { status: 503 });
      });
    })
  );
});
