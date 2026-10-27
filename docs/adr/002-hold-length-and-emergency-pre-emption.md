# ADR-002: How long a hold lasts, and whether an emergency may take a held unit

- **Status:** Accepted, 2026-10-25. Hold lengths built 25 Oct; releasing a hold for an emergency built 27 Oct
  (`POST /requests/{id}/hold-release`, tested in `HoldReleaseIT`).
- **Decided by:** Oluwafisayomi Aiyetogbon, Okechukwu Daniel. Interface impact agreed with Ufoma Onowho.
- **This is the brief's design problem with no clean answer.**

## Context

When a bank approves a request, specific units are reserved for that hospital until the hold ends. Then the units
return to stock if they were not issued. The brief names the tension:

- A **long hold** keeps a unit away from someone who may need it sooner.
- A **short hold** wastes the hospital's time and can release a unit just as the courier arrives.
- Should an **emergency** request be allowed to take a unit that is held for a **routine** one?

Facts that shape the answer:

- An emergency is collected at once, or not at all. A routine request is collected on a plan, usually by a courier
  booked for a time.
- Only the bank knows whether a routine hold's courier is already on the road. The service does not arrange transport.
  That is out of scope.
- The service already shows a lapsed hold as available on every read, even if the background job has not run
  (`unit_status` view). The hold length is therefore the only thing that decides when a unit returns to stock.

## Decision

1. **Hold length depends on the request's urgency.** It is set when the request is approved and shown to both sides as
   "hold ends at":

   | Urgency | Hold | Why |
   | --- | --- | --- |
   | Emergency | 1 hour | Collected at once; if it is not, the unit should go back quickly for the next emergency |
   | Urgent | 2 hours | Same day, short notice |
   | Routine | 4 hours | Leaves time to book and send a courier |

   Each length is a setting (`HOLD_EMERGENCY`, `HOLD_URGENT`, `HOLD_ROUTINE`), so it can change without a code change.

2. **No automatic pre-emption.** An emergency never silently takes a unit that is held for someone else.

3. **Bank staff may release a routine hold for an emergency, as a recorded decision.** The rules are:
   - Only staff of the bank that owns the unit can do it, and only for a hold on a **routine** request.
   - It needs a reason and the emergency request it is for.
   - The routine request goes back to **PENDING**, with a history entry saying which emergency took its unit and who
     decided. It is never lost or silently cancelled.
   - It is one transaction, protected by the same three layers as approval (ADR-001).

   - It is refused when free matching units could already meet the emergency: the bank approves the emergency
     directly, and no promise is broken without need.

## Alternatives rejected

| Option | Why not |
| --- | --- |
| One hold length for every request (for example 4 h) | An unused emergency hold would keep a scarce unit away for 4 hours |
| Hold until the request's `neededBy` time | The hospital would choose its own hold length. A far-off `neededBy` would hoard a unit, and needs a cap anyway |
| Automatic pre-emption: an emergency always takes a routine hold | It breaks a promise the bank already made, possibly while the courier is on the way. That is the exact failure the brief describes. Only the bank knows |
| Never allow pre-emption | Leaves bank staff no recorded way to act when a life-threatening request meets a held unit |

## Cost accepted

- An emergency can wait up to 4 hours for a unit held by a routine request if bank staff do not act. The rule depends on
  a person's judgement, and that judgement is recorded rather than automated.
- A routine courier that arrives after 4 hours finds the hold lapsed. The unit may still be there, but it is no longer
  promised.
- Releasing a hold needs one more endpoint and a contract change, so the interface must build one more confirmation
  screen.

## What would make us reverse it

- **Holds too short:** the history shows `HOLD_LAPSED` followed within 30 minutes by the same hospital asking again
  for the same group and component, more than occasionally. That means couriers are arriving late, so lengthen the
  routine hold.
- **Holds too long:** emergency requests are refused with 409 while matching units sit in routine holds that later
  lapse unused. Shorten the routine hold, or allow pre-emption without a manual step for those cases.
