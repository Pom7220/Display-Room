# End Early UX — Design Spec

**Date:** 2026-07-16
**Status:** Approved

## Problem

The End button is rarely used but exists for a real purpose: signalling to walk-in colleagues that a meeting finished early and the room is free. Currently it wipes the card entirely and shows no context. The +30 min extend button is the more common post-check-in action but is not visually prioritised.

## Goal

Demote End to a secondary action, promote +30 as the primary action, and replace the End confirmation dialog with a contextual green "released early" card state that keeps meeting context visible.

---

## Design

### Button layout after check-in

| Before | After |
|---|---|
| End (large) + +30 (large) | +30 (large primary) + End early (small ghost button below) |

The +30 button keeps its current size and style. End early becomes a small secondary text/ghost button — same single tap, clearly less prominent.

### End early card state

When End early is tapped:

1. **No confirm dialog** — the green card IS the confirmation. Immediate transition.
2. **Theme switches to green** (same as Available/free).
3. **Meeting details remain visible** — title, organizer, original time shown for context.
4. **Status text** in the no-show countdown area: `"Room released early · Available now"`
5. **Buttons hidden** — both +30 and End early disappear. No further actions.
6. **State persists in localStorage** keyed by `meetingId + room` — page reload does not undo the End action.
7. **Auto-clears at original scheduled end time** — next `updateCurrentCard()` cycle moves on naturally (next meeting or Available).

### State persistence

localStorage key: `ris_ended_early`
Value: `{ meetingId, room, endedAt }`

On `updateCurrentCard()`:
- If `ris_ended_early` matches current meeting's ID and room → apply green released state
- If current meeting's scheduled end has passed → remove `ris_ended_early` from localStorage

### What is NOT in scope

- Exchange event cancellation (room stays booked in Outlook/Exchange)
- Any notification or logging of the early end action
- Remote check-in or remote end

---

## Files to change

- `index.html` only — button layout, card state logic, localStorage persistence

## No changes to

- `cloudflare-worker.js`
- APK / boot-launcher
