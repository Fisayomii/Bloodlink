# Demo script: three journeys from a clean start

Brief: "Three complete user journeys that can be demonstrated from a clean start, without improvising and without
editing data by hand." This is the script for the final walkthrough and the live defence.

**Repeatable by design.** Every journey makes the records it needs through the product itself: a new unit, new
requests, a new donation slot. Nothing depends on a seeded record that one rehearsal would use up, so the script works
on the first run and the tenth. The seeded race (one O-negative unit, three pending requests at Lagos Central) is
shown only by the automated tests, never clicked live, because approving it once uses it up.

Run it in the interface (https://bloodlink-orpin.vercel.app) in a clean browser window. Each step names the service
call behind it, so the same step can be shown in the service's Swagger page (https://bloodlink-o2ie.onrender.com) if
the interface is unavailable. Password for every account: `Demo-Password-1`.

About 9 minutes in this order: **2a, 1, 3, 2b**. Journey 2 opens a donation slot that starts a few minutes later,
because attendance can only be recorded once a slot has started; journeys 1 and 3 fill the wait.

## Before you start (not shown)

- [ ] 15 minutes before: open https://bloodlink-o2ie.onrender.com/actuator/health until it shows `{"status":"UP"}`.
      The free host sleeps; the first request after a sleep takes about 30 s. On the day, also set the repository
      variable `KEEP_WARM` to `true` (see `.github/workflows/keep-warm.yml`) and back to `false` afterwards.
- [ ] Three browser profiles or windows, signed in as: `staff@lekki.bank.demo` (bank), `staff@yaba.hospital.demo`
      (hospital 1), `donor@bloodlink.demo` (donor). Sign in to the others as the script reaches them.
- [ ] Tabs open: the latest green **api** run in GitHub Actions (for "Rule check on the deployed service"), `docs/evidence.md`
      (the race test red and green), `docs/performance.md`.
- [ ] Note the time. The slot in 2a starts **10 minutes from now**, rounded up to the next 5 minutes.
- [ ] As hospital 1, search Lekki's stock of **B, NEGATIVE, PLATELETS** (see "The combination for this run").

## The combination for this run

Journeys 1 and 3 use **B, NEGATIVE, PLATELETS** at Lekki. Each rehearsal leaves about three more of these units in
stock (they expire 5 days later), and journey 3 needs a request for the whole stock, which can ask for at most 20. If
Lekki already shows more than 15, use the next combination for the whole run instead, in this order: **AB, NEGATIVE,
PLASMA**; **A, NEGATIVE, RED_CELLS**; **O, NEGATIVE, WHOLE_BLOOD**. None of them is in the seeded stock.

## Journey 2a: a bank opens a donation slot and donors book it

| # | Who | Do | What to point out | Service call |
| --- | --- | --- | --- | --- |
| 1 | Lekki staff | Open a donation slot starting at the time you noted, 1 hour long, **capacity 1** | The slot belongs to the bank in the token; staff cannot open slots at another bank | `POST /donation-slots` |
| 2 | Donor | View donation slots at Lekki; the new slot shows **1 place left** | Places left are counted from the bookings, with the time they were computed | `GET /donation-slots?bankId=…` |
| 3 | Donor | Book the slot | **201**; the slot now shows 0 places left | `POST /donation-slots/{id}/bookings` |
| 4 | Second donor (`donor2@bloodlink.demo`) | Try to book the same slot | **409 SLOT_FULL**, "This slot has no places left. Please choose another time." The screen stays usable | same |

Say: "Capacity is protected in the database: each booking holds a numbered place, and a place can be held once. Two
donors booking the last place in the same second get one booking; `ConcurrentBookingIT` proves it, and fails with the
protection removed (`docs/evidence.md`)."

## Journey 1: a hospital finds blood, requests it and receives it

| # | Who | Do | What to point out | Service call |
| --- | --- | --- | --- | --- |
| 1 | Hospital 1 | Search availability: **B, NEGATIVE, PLATELETS**. Note the count at Lekki (often 0) | Exact group, rhesus and component only: no substitution, and the screen says so. The figure shows when it was computed | `GET /availability?bloodGroup=B&rhesus=NEGATIVE&component=PLATELETS` |
| 2 | Lekki staff | Record a unit: B, NEGATIVE, PLATELETS, collected today, expires in **5 days**, reference `DEMO-<HHMM>` | The bank comes from the token. A unit needs group, component, expiry and bank; expiry must be after collection | `POST /units` |
| 3 | Lekki staff | Submit the same unit again | **409 DUPLICATE_UNIT_REFERENCE**: a retry after a lost response is caught, not duplicated | same |
| 4 | Hospital 1 | Search again: Lekki shows **one more** unit | Availability is read from the records at the moment of the request, nothing cached | `GET /availability…` |
| 5 | Hospital 1 | Request **1** unit of B negative platelets from Lekki, urgency **EMERGENCY**, needed by in 2 hours | No patient name anywhere: the request carries a reference and the hospital. State: PENDING | `POST /requests` (with `Idempotency-Key`) |
| 6 | Lekki staff | Open the bank's requests; approve the new one | **APPROVED**; a specific unit is reserved, earliest expiry first, with "hold ends at" one hour away (emergency holds last 1 hour, ADR-002) | `POST /requests/{id}/approval` |
| 7 | Hospital 1 | Open the request | The same state the bank sees, with the reserved unit and the hold time | `GET /requests/{id}` |
| 8 | Lekki staff | Record the unit as issued | **FULFILLED**; the unit is ISSUED and leaves availability | `POST /requests/{id}/issue` |

Say: "If three hospitals ask for the last unit at the same moment, exactly one gets it." Then show the latest **api**
run, job "Rule check on the deployed service": three approvals sent together to this deployed service, one reservation.
`ConcurrentApprovalIT` is the same rule in CI, shown failing with the protection removed (`docs/evidence.md`).

## Journey 3: the bank handles what goes wrong

| # | Who | Do | What to point out | Service call |
| --- | --- | --- | --- | --- |
| 1 | Lekki staff | Record two more B negative platelet units (references `DEMO-<HHMM>-2`, `-3`) | Stock for the next step | `POST /units` |
| 2 | Hospital 1 | Request **1** unit, urgency **EMERGENCY** | Its hold will last 1 hour, so it shows on the dashboard in step 7 | `POST /requests` |
| 3 | `staff@ikorodu.hospital.demo` | Search, then request **as many units as the search shows at Lekki** (the whole stock), urgency URGENT | Two requests now want more than exists. A request may ask for at most 20 units: if Lekki shows more, see "The combination for this run" | `POST /requests` |
| 4 | Lekki staff | Approve hospital 1's request, then Ikorodu's | Hospital 1: APPROVED. Ikorodu: **409 UNITS_NO_LONGER_AVAILABLE**, "Only N of N+1 matching units are free now. Nothing was reserved; the request is still pending." All or nothing, never a partial reservation | `POST /requests/{id}/approval` |
| 5 | Lekki staff | Decline Ikorodu's request with a reason ("Not enough stock; try Lagos Central") | **DECLINED**; Ikorodu sees the reason. A decline without a reason is refused | `POST /requests/{id}/declination` |
| 6 | Lekki staff | Open journey 1's request (FULFILLED) and reverse the issue: "Recorded against the wrong request" | **PENDING** again. Open the unit: its history shows ISSUED **and** ISSUE_REVERSED, and the unit is AVAILABLE because it has not expired. Nothing is edited or deleted | `POST /requests/{id}/issue/reversal`, `GET /units/{id}` |
| 7 | Lekki staff | Open the dashboard | **Holds ending soon**: hospital 1's emergency hold, ending within the hour. Units available by group: B negative went up by the units recorded today. Open requests, groups running low, units expiring within 3 days (today's units, at 5 days, are not in it yet), recent activity listing every step above. Every figure is computed from the records, with `computedAt` | `GET /dashboard` |
| 8 | `admin@bloodlink.demo` | Requests in state **LAPSED**, then **UNIT_EXPIRED** | Seeded awkward cases. A lapsed hold is shown as lapsed and its unit as available even if the background job has not run (ADR-003). A unit that expired while reserved is EXPIRED and its request says what happened | `GET /requests?state=LAPSED`, `?state=UNIT_EXPIRED` |

## Journey 2b: the bank records the visit

Once the slot from 2a has started:

| # | Who | Do | What to point out | Service call |
| --- | --- | --- | --- | --- |
| 1 | Lekki staff | Open the slot's bookings; record the donor as **ATTENDED** | Attendance can only be recorded once the slot has started (before that: 409 SLOT_NOT_STARTED) | `POST /bookings/{id}/attendance` |
| 2 | Donor | Open my bookings | ATTENDED | `GET /bookings` |

If the slot has not started yet, say so and show step 1's refusal instead: it proves the rule, then return to it.

## After the demo

Nothing to clean up. Each run leaves its records (units `DEMO-…`, requests, a slot) in the history, as real use would.
Units recorded with a 5-day expiry pass through "expiring soon" and then EXPIRED on their own.

## What the interface must support for this script

To confirm with Ufoma before rehearsal. If a step is not in the interface, show it in Swagger and say so.

1. Bank staff open a donation slot with a start time a few minutes away and a capacity of 1.
2. A donor sees places left, books, and gets a clear "slot full" message.
3. Bank staff record a unit, and see the duplicate-reference refusal.
4. Availability search with the three filters, showing `computedAt` and the "exact match, no substitution" note.
5. A hospital requests with urgency and needed-by time.
6. Bank staff approve and decline (with a reason), and see the "no longer available" refusal on a usable screen.
7. Bank staff issue and reverse an issue; a unit's history.
8. The dashboard.
9. The administrator filters requests by state (LAPSED, UNIT_EXPIRED).
10. Bank staff record attendance.

## Likely questions

See the "BloodLink Trade-offs & Defence Prep" document.
