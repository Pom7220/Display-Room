// ris-shared.js — RIS Room Display shared logic
// ES5 ONLY — loaded by both index.html (Chrome 42) and dashboard.html
// DO NOT use: const/let, arrows, template literals, async/await, optional chaining
// Version: 1.4 (2026-07-01) — fetch→XHR in fetchCalendarForRoom+fetchBookRoom (Chromium 30 WebView safe)

// ── ROOM DEFINITIONS ──
var RIS_ROOMS = [
  {num:1,  name:'Espresso',    email:'risespresso@central.co.th',    seats:'8–12', zone:'lobby',  approval:false},
  {num:2,  name:'Doppio',      email:'risdoppio@central.co.th',      seats:'6–8',  zone:'lobby',  approval:false},
  {num:3,  name:'Cappuccino',  email:'riscappuccino@central.co.th',  seats:6,      zone:'lobby',  approval:false},
  {num:4,  name:'Americano',   email:'risamericano@central.co.th',   seats:6,      zone:'lobby',  approval:false},
  {num:5,  name:'Lungo',       email:'rislungo@central.co.th',       seats:4,      zone:'lobby',  approval:false},
  {num:6,  name:'Ristretto',   email:'risristretto@central.co.th',   seats:4,      zone:'lobby',  approval:false},
  {num:7,  name:'Macchiato',   email:'rismacchiato@central.co.th',   seats:'5–8',  zone:'office', approval:true },
  {num:8,  name:'Viennese',    email:'risviennese@central.co.th',    seats:6,      zone:'office', approval:false},
  {num:9,  name:'Decaffinato', email:'risdecaffeinato@central.co.th',seats:6,      zone:'office', approval:false},
  {num:10, name:'Latte',       email:'rislatte@central.co.th',       seats:6,      zone:'office', approval:false},
  {num:11, name:'Mocha',       email:'rismocha@central.co.th',       seats:6,      zone:'office', approval:false},
  {num:12, name:'Affogato',    email:'risaffogato@central.co.th',    seats:6,      zone:'office', approval:false}
];

// ── SHARED HELPERS ──

// Parse Graph API dateTime object — handles UTC and local time
function gDate(o) {
  var s = o.dateTime, tz = o.timeZone || '';
  if (!s) return new Date();
  var t = s.replace(/\.\d+$/, '');
  if (tz === 'UTC' || tz === 'utc') {
    if (t.charAt(t.length - 1) !== 'Z') t = t + 'Z';
    return new Date(t);
  }
  if (t.charAt(t.length - 1) === 'Z' || t.indexOf('+') > 10) {
    return new Date(t);
  }
  // No timezone — parse components manually (cross-browser safe)
  var parts = t.split('T');
  if (parts.length < 2) return new Date(t);
  var dp = parts[0].split('-');
  var tp = parts[1].split(':');
  return new Date(
    parseInt(dp[0], 10), parseInt(dp[1], 10) - 1, parseInt(dp[2], 10),
    parseInt(tp[0], 10), parseInt(tp[1], 10), tp[2] ? parseInt(tp[2], 10) : 0
  );
}

// Check if meeting spans a full day (all-day event)
function isAllDay(m) {
  return (m.end - m.start) >= 86399000;
}

// Map a Graph API event to our internal meeting object
function mapEvent(ev) {
  return {
    id: ev.id,
    title: ev.subject || '(No title)',
    organizer: (ev.organizer && ev.organizer.emailAddress && ev.organizer.emailAddress.name)
      || (ev.organizer && ev.organizer.emailAddress && ev.organizer.emailAddress.address)
      || '',
    start: gDate(ev.start),
    end: gDate(ev.end),
    attendees: (ev.attendees || []).length,
    joinUrl: (ev.onlineMeeting && ev.onlineMeeting.joinUrl)
      || (ev.onlineMeeting && ev.onlineMeeting.joinWebUrl)
      || null,
    isOnline: !!ev.isOnlineMeeting,
    showAs: ev.showAs || 'busy',
    isCancelled: !!ev.isCancelled,
    // tentative = pending approval — excluded from active status
    isPending: ev.showAs === 'tentative'
  };
}

// Filter and sort raw Graph API events
// Rules (same as kiosk):
// - Exclude cancelled meetings
// - Keep all showAs values incl. 'free' (room calendars return 'free' via delegated access)
// - tentative = isPending, excluded from cur/nxt but shown as pending slot
function filterMeetings(evArr) {
  return evArr
    .map(mapEvent)
    .filter(function(m) { return !m.isCancelled; })
    .sort(function(a, b) { return a.start - b.start; });
}

// Derive room status from a list of filtered meetings
// Returns: {status, cur, nxt, pendingCur, freeUntil, allDayCur}
// status: 'avail' | 'busy' | 'soon' | 'pending' | 'unknown'
// allDayCur: true when the current booking is an all-day room block
// 'pending' = tentative meeting now or upcoming soon — not confirmed but not available either
function deriveRoomStatus(meetings) {
  var now = new Date();
  var endOfToday = new Date(); endOfToday.setHours(23, 59, 59, 999);

  // Current confirmed meeting: non-pending, happening now
  // ALL-DAY events ARE included — a room booked all day is not available
  var cur = null;
  for (var i = 0; i < meetings.length; i++) {
    var m = meetings[i];
    if (!m.isPending && m.start <= now && m.end >= now) {
      cur = m; break;
    }
  }

  // Next confirmed meeting: non-pending, non-all-day, in future today
  // All-day events are excluded from nxt — they don't represent a "coming up soon" slot
  var nxt = null;
  for (var j = 0; j < meetings.length; j++) {
    var n = meetings[j];
    if (!n.isPending && !isAllDay(n) && n.start > now && n.start <= endOfToday) {
      nxt = n; break;
    }
  }

  // Pending meeting happening now or upcoming today
  var pendingCur = null;
  for (var k = 0; k < meetings.length; k++) {
    var p = meetings[k];
    if (p.isPending && !isAllDay(p) && p.end >= now && p.start <= endOfToday) {
      pendingCur = p; break;
    }
  }

  var status, freeUntil = null;
  var allDayCur = cur ? isAllDay(cur) : false;

  if (cur) {
    // Confirmed meeting in progress — busy
    status = 'busy';
    // For all-day bookings, freeUntil = end of today (not tomorrow 07:00 local)
    freeUntil = allDayCur ? endOfToday : cur.end;
  } else if (pendingCur && pendingCur.start <= now) {
    // Pending meeting in progress now — show as pending
    status = 'pending'; freeUntil = pendingCur.end;
  } else if (nxt) {
    var minsUntil = Math.round((nxt.start - now) / 60000);
    status = minsUntil <= 30 ? 'soon' : 'avail';
    freeUntil = nxt.start;
  } else if (pendingCur) {
    // Pending upcoming meeting — show as pending instead of available
    var minsPending = Math.round((pendingCur.start - now) / 60000);
    status = minsPending <= 60 ? 'pending' : 'avail';
    freeUntil = pendingCur.start;
  } else {
    status = 'avail';
  }

  return { status: status, cur: cur, nxt: nxt, pendingCur: pendingCur, freeUntil: freeUntil, allDayCur: allDayCur };
}

// Fetch calendar for a single room via Cloudflare Worker proxy — returns Promise
// Worker handles ROPC auth — no user token needed on tablet
// XHR only — no fetch() — safe for Chromium 30 WebView (Android 4.4.2)
function fetchCalendarForRoom(email, tabletKey, startISO, endISO) {
  var workerOrigin = 'https://ris-display.ris-display.workers.dev';
  var url = workerOrigin + '/api/calendar'
    + '?room=' + encodeURIComponent(email)
    + '&startDateTime=' + encodeURIComponent(startISO)
    + '&endDateTime=' + encodeURIComponent(endISO);
  return new Promise(function(resolve) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.setRequestHeader('X-Tablet-Key', tabletKey || '');
    xhr.onreadystatechange = function() {
      if (xhr.readyState !== 4) return;
      try {
        var d = JSON.parse(xhr.responseText);
        resolve(filterMeetings(d.value || []));
      } catch(e) { resolve([]); }
    };
    xhr.onerror = function() { resolve([]); };
    xhr.send();
  });
}

// Book a room via Cloudflare Worker proxy — returns Promise
// XHR only — no fetch() — safe for Chromium 30 WebView (Android 4.4.2)
function fetchBookRoom(tabletKey, roomEmail, roomName, subject, startISO, endISO, organizerName, organizerEmail) {
  var workerOrigin = 'https://ris-display.ris-display.workers.dev';
  var url = workerOrigin + '/api/book';
  var body = JSON.stringify({
    room: roomEmail,
    roomName: roomName || roomEmail,
    subject: subject || 'Meeting',
    start: startISO,
    end: endISO,
    organizerName: organizerName || '',
    organizerEmail: organizerEmail || ''
  });
  return new Promise(function(resolve, reject) {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', url, true);
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('X-Tablet-Key', tabletKey || '');
    xhr.onreadystatechange = function() {
      if (xhr.readyState !== 4) return;
      try {
        var d = JSON.parse(xhr.responseText);
        if (d.error) {
          reject(new Error(d.error + (d.detail ? ' | ' + d.detail : '') + (d.status ? ' (HTTP '+d.status+')' : '')));
        } else {
          resolve(d);
        }
      } catch(e) { reject(e); }
    };
    xhr.onerror = function() { reject(new Error('Network error')); };
    xhr.send(body);
  });
}

// Format duration in minutes to human readable e.g. "3h 45m" or "45m"
function sharedFmtDur(mins) {
  if (mins < 60) return mins + 'm';
  var h = Math.floor(mins / 60);
  var m = mins % 60;
  return m > 0 ? h + 'h ' + m + 'm' : h + 'h';
}

// Format time HH:MM
function sharedFt(d) {
  var h = String(d.getHours()).padStart ? String(d.getHours()).padStart(2,'0') : (d.getHours()<10?'0':'')+d.getHours();
  var m = String(d.getMinutes()).padStart ? String(d.getMinutes()).padStart(2,'0') : (d.getMinutes()<10?'0':'')+d.getMinutes();
  return h + ':' + m;
}
