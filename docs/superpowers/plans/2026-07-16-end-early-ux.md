# End Early UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the End confirm-dialog flow with an immediate green "released early" card state, and demote the End button to a small ghost button below the primary +30 button.

**Architecture:** All changes are in `index.html` only. A `ris_ended_early` localStorage entry (keyed by meetingId + room) persists the released state across reloads. `updateCurrentCard()` checks for this entry on every render cycle and renders the released-early card if the meeting is still within its scheduled window, or clears the entry if it has passed.

**Tech Stack:** Vanilla JS, HTML/CSS, localStorage. No Worker or APK changes.

## Global Constraints

- `index.html` only — no changes to `cloudflare-worker.js` or APK.
- No Exchange event cancellation — room stays booked in Outlook.
- No confirm dialog — tapping End early transitions immediately.
- Released state auto-clears at the meeting's original scheduled end time.
- State survives page reloads via localStorage keyed to `meetingId + activeRoom.email`.
- Version bump: `index.html` label + `index-version.json` on every commit.

---

### Task 1: Released-early helpers, CSS, and card rendering

**Files:**
- Modify: `index.html` — add CSS, localStorage helpers, rendering branch in `updateCurrentCard()`
- Modify: `index-version.json` — bump to v3.10.144

**Interfaces:**
- Produces:
  - `setEndedEarly(meetingId, room)` → void
  - `getEndedEarly()` → `{meetingId, room, endedAt}` | null
  - `clearEndedEarly()` → void
  - Released-early rendering branch inside `updateCurrentCard()` — returns early when active

---

- [ ] **Step 1: Add ghost button CSS**

Find the `.abtn-end` CSS rule (around line 227). Add immediately after it:

```css
.abtn-end-early{touch-action:manipulation;background:transparent;color:#aa4444;border:1px solid #663333;font-size:15px;font-weight:600;min-width:auto;min-height:36px;padding:8px 20px;margin-top:6px;}
```

- [ ] **Step 2: Add localStorage helpers**

Find the line `var _cardCountdownTimer=null;` (around line 880). Add immediately after:

```javascript
var _ENDED_EARLY_KEY='ris_ended_early';
function setEndedEarly(meetingId,room){
  try{localStorage.setItem(_ENDED_EARLY_KEY,JSON.stringify({meetingId:meetingId,room:room,endedAt:Date.now()}));}catch(e){}
}
function getEndedEarly(){
  try{var r=localStorage.getItem(_ENDED_EARLY_KEY);return r?JSON.parse(r):null;}catch(e){return null;}
}
function clearEndedEarly(){
  try{localStorage.removeItem(_ENDED_EARLY_KEY);}catch(e){}
}
```

- [ ] **Step 3: Add released-early rendering branch in `updateCurrentCard()`**

Inside `updateCurrentCard()`, find the lines (around line 2332):

```javascript
function updateCurrentCard(){
  if(_cardCountdownTimer){clearInterval(_cardCountdownTimer);_cardCountdownTimer=null;}
  var n=new Date(),cur=getCur(),nxt=getNext();
  var card=el('cur-card'),cont=el('cur-content');
```

Immediately after `var card=el('cur-card'),cont=el('cur-content');`, add:

```javascript
  // Released-early state: meeting ended early by organizer tap
  var _ee=getEndedEarly();
  if(_ee&&cur&&_ee.meetingId===cur.id&&_ee.room===activeRoom.email){
    if(new Date()<cur.end){
      setTheme('available');
      el('status-txt').textContent='Released Early';
      el('sdot').className='sdot av';
      cont.innerHTML=
        '<div class="meet-tag avail">RELEASED</div>'+
        '<div class="meet-title-lg">'+esc(cur.title)+'</div>'+
        '<div class="meet-time-lg" style="color:#64b5f6">'+ft(cur.start)+' &ndash; '+ft(cur.end)+'</div>'+
        (cur.organizer?'<div class="meet-org">'+svgUser(13)+esc(cur.organizer)+'<\/div>':'');
      el('action-row-outer').innerHTML=
        '<div style="text-align:center;font-size:18px;font-weight:700;color:#44ee88;margin:10px 0">'+
        '&#10003;&nbsp;Room released early &middot; Available now</div>';
      el('nav-book-btn').style.display=cfg.booking!==false?'':'none';
      return;
    } else {
      clearEndedEarly();
    }
  }
```

- [ ] **Step 4: Bump version**

In `index.html`, find `APP_VERSION` label and update from `v3.10.143` to `v3.10.144`.

In `index-version.json`:
```json
{
  "version": "v3.10.144"
}
```

- [ ] **Step 5: Manual verification of released-early rendering**

On any tablet or browser with a current meeting checked in:
1. Open browser dev tools → Application → localStorage
2. Manually set `ris_ended_early` to `{"meetingId":"<any current meeting id>","room":"<activeRoom.email>","endedAt":0}`
3. Reload the page
4. Expected: card turns green, shows "RELEASED" tag, meeting title/time/organizer still visible, "✓ Room released early · Available now" message, no buttons
5. Wait until meeting's scheduled end time passes (or manually set `endedAt` to a past time and set a meeting end in the past)
6. Expected: `ris_ended_early` is cleared from localStorage, card returns to normal Available state

- [ ] **Step 6: Commit**

```bash
git add index.html index-version.json
git commit -m "feat: released-early card state + localStorage helpers (v3.10.144)"
```

---

### Task 2: Button layout + endMeetingEarly function

**Files:**
- Modify: `index.html` — button layout in two places, new `endMeetingEarly()`, touch handler wiring

**Interfaces:**
- Consumes: `setEndedEarly(meetingId, room)` from Task 1, `updateCurrentCard()`, `getCur()`, `getNext()`
- Produces: `endMeetingEarly()` → void

---

- [ ] **Step 1: Change button layout in BUSY / IN USE section**

Find (around line 2417):
```javascript
      } else if(!isAllDay(cur)){
        btns='<button class="abtn abtn-end" id="end-btn">'+svgX()+' End</button>';
        btns+='<button class="abtn abtn-extend" id="ext-btn"><span style="display:block;text-align:center">+30</span><span style="display:block;text-align:center">min</span></button>';
      }
```

Replace with:
```javascript
      } else if(!isAllDay(cur)){
        btns='<button class="abtn abtn-extend" id="ext-btn"><span style="display:block;text-align:center">+30</span><span style="display:block;text-align:center">min</span></button>';
        btns+='<br><button class="abtn abtn-end-early" id="end-btn">End early</button>';
      }
```

- [ ] **Step 2: Change button layout in early check-in (UPCOMING) IN USE section**

Find (around line 2449):
```javascript
      var earlyBtns='<button class="abtn abtn-end" id="end-btn">'+svgX()+' End</button>';
      earlyBtns+='<button class="abtn abtn-extend" id="ext-btn"><span style="display:block;text-align:center">+30</span><span style="display:block;text-align:center">min</span></button>';
```

Replace with:
```javascript
      var earlyBtns='<button class="abtn abtn-extend" id="ext-btn"><span style="display:block;text-align:center">+30</span><span style="display:block;text-align:center">min</span></button>';
      earlyBtns+='<br><button class="abtn abtn-end-early" id="end-btn">End early</button>';
```

- [ ] **Step 3: Add `endMeetingEarly()` function**

Find the `function endMeeting(){` declaration (around line 2683). Add immediately before it:

```javascript
function endMeetingEarly(){
  var cur=getCur();if(!cur&&checkedIn)cur=getNext();if(!cur)return;
  setEndedEarly(cur.id,activeRoom.email);
  checkedIn=false;
  try{localStorage.removeItem('roomdisplay_checkedin');}catch(e){}
  updateCurrentCard();
}
```

- [ ] **Step 4: Wire touch handler to `endMeetingEarly()`**

Find (around line 3287):
```javascript
  if(upId(t,'end-btn')){e.preventDefault();endMeeting();return;}
```

Replace with:
```javascript
  if(upId(t,'end-btn')){e.preventDefault();endMeetingEarly();return;}
```

- [ ] **Step 5: Manual verification — full flow**

On a tablet or browser with an active checked-in meeting:
1. Confirm buttons show: **+30 min** (large, primary) above, **End early** (small ghost) below
2. Tap **End early**
3. Expected: no dialog — card immediately turns green, shows "RELEASED" tag + meeting details + "✓ Room released early · Available now", no buttons visible
4. Reload the page
5. Expected: green released state persists (localStorage survives reload)
6. Tap **+30 min** on a different meeting (confirm it still works normally)
7. Confirm `ris_ended_early` in localStorage matches `{meetingId, room, endedAt}`

- [ ] **Step 6: Commit and push**

```bash
git add index.html
git commit -m "feat: demote End to ghost button, wire endMeetingEarly() (v3.10.144)"
git push
```
