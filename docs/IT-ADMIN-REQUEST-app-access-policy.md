# Request to M365 / Exchange admin — restrict app-only calendar access

**Raised by:** Vorutchapon (RIS Room Display owner)
**Date:** 2026-09-29
**Summary:** One app registration currently holds a tenant-wide, admin-consented
application permission to read and write **every calendar in the tenant**. We want it
restricted to the 12 meeting-room mailboxes it actually uses. This does not change how the
application works today.

---

## Background

RIS Room Display runs the meeting-room tablets on floor 8. It reaches Exchange through a
Cloudflare Worker, which signs in as the service account `rismeetingroomsystem@central.co.th`
(delegated / username+password) and works only against the room mailboxes.

While reviewing it we noticed the app registration **also** carries an application
(app-only) permission that is far broader than anything the system uses.

**App registration:** `RIS OAuth Meeting Room Kiosk`
**Application (client) ID:** `80648895-4acf-4ac5-b4a3-c5bf6bc98983`
**Tenant ID:** `817e531d-191b-4cf5-8812-f0061d89b53d`

Its API permissions today:

| Permission | Type | Consented |
|---|---|---|
| `Calendars.ReadWrite` | **Application** — "Read and write calendars in **all mailboxes**" | Yes, admin-consented for Central Group |
| `Calendars.ReadWrite` | Delegated | Yes |
| `Calendars.ReadWrite.Shared` | Delegated | Yes |
| `Mail.Send` | Delegated | Yes |
| `User.Read` | Delegated | Yes |

**The concern is the second row.** Because it is already admin-consented, anyone holding
the application's client ID and client secret can obtain an app-only token and read or
write **any calendar in the tenant** — without the service account, and without going
through our application. The secret is held in Cloudflare Workers secrets and is not
exposed, but the grant is wider than the system needs by a large margin.

---

## What we are asking for

### 1. Please confirm (questions)

**Q1.** Is the application permission `Calendars.ReadWrite` (Application type) on this app
registration still required by anything else in the organisation? We believe it is unused —
our Worker authenticates with delegated permissions, not app-only — but we cannot see
whether another integration relies on it.

**Q2.** Is there already an `ApplicationAccessPolicy` in place for this app ID? Please run:

```powershell
Get-ApplicationAccessPolicy | Where-Object { $_.AppId -eq "80648895-4acf-4ac5-b4a3-c5bf6bc98983" }
```

If this returns nothing, the app is unrestricted and can reach all mailboxes.

**Q3.** Is `rismeetingroomsystem@central.co.th` excluded from any Conditional Access or MFA
policy? It must be, because the sign-in method the Worker uses cannot complete an MFA
challenge. We would like to know the exact exclusion so it can be reviewed.

### 2. Please apply (the actual request)

Restrict the app to the existing mail-enabled security group **`RIS Meeting Rooms`**, which
already contains exactly the room mailboxes (13 members — the 12 live rooms plus
`risicare@central.co.th`, a decommissioned room).

In Exchange Online PowerShell:

```powershell
New-ApplicationAccessPolicy `
  -AppId "80648895-4acf-4ac5-b4a3-c5bf6bc98983" `
  -PolicyScopeGroupId "<primary SMTP address of the RIS Meeting Rooms group>" `
  -AccessRight RestrictAccess `
  -Description "RIS Room Display - restrict app-only Graph access to meeting room mailboxes only"
```

Then verify — the first should be **Granted**, the second **Denied**:

```powershell
Test-ApplicationAccessPolicy -Identity rismacchiato@central.co.th -AppId "80648895-4acf-4ac5-b4a3-c5bf6bc98983"
```

```powershell
Test-ApplicationAccessPolicy -Identity <any ordinary user mailbox> -AppId "80648895-4acf-4ac5-b4a3-c5bf6bc98983"
```

Policy changes can take up to an hour to propagate.

---

## Impact

**Expected impact on the room display system: none.**

`ApplicationAccessPolicy` governs **app-only** access. Both things that use this app
registration today use **delegated** access, which the policy does not affect:

1. the Cloudflare Worker, signing in as the service account (username/password), and
2. the admin dashboard, where staff sign in interactively with their own Microsoft account
   — this same app registration is the sign-in client.

So we are constraining a grant that neither path presently uses.

If Q1 reveals another integration depending on app-only access to non-room mailboxes, that
integration **would** be affected — which is exactly why we are asking before proceeding.

Please let us know if you would prefer to apply this outside working hours. From our side
it can be applied at any time; the tablets are in use 07:00–20:30 on weekdays and we will
confirm they are still functioning afterwards.

---

## Separate, lower priority — for a later conversation

We are considering moving the Worker from username/password sign-in to app-only
authentication. That would remove the service account password entirely, and remove the
need for the MFA exclusion in Q3.

If the access policy above is in place first, that migration becomes a narrowing rather
than a widening of access. It would additionally need:

- `Mail.Send` added as an **Application** permission with admin consent (currently
  Delegated only) — used for the weekly no-show report. The same access policy would keep
  it scoped to the room mailboxes rather than allowing send-as for any mailbox.

No action needed on this now. We would raise it separately once the access policy is
confirmed.

---

## Contact

Any questions to Vorutchapon. Happy to join a call or test alongside you while the policy
is applied.

---

## OUTCOME — 2026-09-29: no action needed

IT admin confirmed (in Thai): policy control is already implemented; the application can
manage **only** the RIS Meeting Room mailboxes, and `rismeetingroomsystem@central.co.th`
likewise has rights over only those mailboxes.

**So the concern in this document does not apply.** The portal's "Read and write calendars
in all mailboxes" text describes what the permission *type* allows, not its effective
scope — an access policy already constrains it. This document was written from the portal
view alone, which does not show that policy.

**Verified by test, 2026-09-29.** IT ran both checks:

| Mailbox | Result |
|---|---|
| `vorutchapon@central.co.th` (ordinary user) | **Denied** |
| `rismacchiato@central.co.th` (meeting room) | **Granted** |

Both cases matter: Denied alone could also mean the app reaches nothing at all. The pair
demonstrates the scoping is correct in both directions.

No change requested. Keeping this on file because:

- it records the permission model and why it is safe, for whoever reviews it next;
- the verification commands remain useful if the scoping is ever in doubt
  (`Test-ApplicationAccessPolicy` against an ordinary mailbox should return **Denied**);
- Q3 (the Conditional Access / MFA exclusion on the service account) was not answered and
  is still worth knowing, though it is low priority.
