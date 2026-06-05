// RIS Room Display — Service Worker v2.1
// Bump CACHE version to force all clients to update
var CACHE = 'ris-room-display-v21';
var CORE_FILES = ['./', './index.html', './msal-v1.min.js'];

// Install: cache core files, skip waiting immediately
self.addEventListener('install', function(e) {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE).then(function(c) {
      return c.addAll(CORE_FILES);
    })
  );
});

// Activate: delete ALL old caches, claim all clients immediately
self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(keys) {
      return Promise.all(
        keys.map(function(k) {
          // Delete any cache that isn't current version
          if(k !== CACHE) {
            console.log('SW: deleting old cache', k);
            return caches.delete(k);
          }
        })
      );
    }).then(function() {
      return self.clients.claim();
    })
  );
});

// Fetch: network FIRST, cache fallback
// Always bypass cache for auth/API calls
self.addEventListener('fetch', function(e) {
  var url = e.request.url;

  // Never intercept Microsoft/auth/weather calls
  if(url.indexOf('microsoft') > -1 ||
     url.indexOf('msauth') > -1 ||
     url.indexOf('login.microsoftonline') > -1 ||
     url.indexOf('graph.microsoft') > -1 ||
     url.indexOf('open-meteo') > -1 ||
     url.indexOf('wttr.in') > -1) {
    return;
  }

  e.respondWith(
    fetch(e.request).then(function(res) {
      if(e.request.method === 'GET' && res.status === 200) {
        var clone = res.clone();
        caches.open(CACHE).then(function(c) { c.put(e.request, clone); });
      }
      return res;
    }).catch(function() {
      return caches.match(e.request);
    })
  );
});
