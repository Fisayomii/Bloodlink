# ADR-006: Keeping a donation slot within its capacity, in the database

- **Status:** Accepted, 2026-10-30. Built and tested.
- **Decided by:** Oluwafisayomi Aiyetogbon. Awaiting review by Okechukwu Daniel.

## Context

The brief: "A donation slot cannot be booked beyond its capacity, including when two donors book the last place in the
same second." As with reservations (ADR-001), counting the bookings in Java and then inserting is read-then-write: two
donors both count 3 of 4 and both insert. The rule has to hold in the database.

## Decision

Two layers, in the same shape as ADR-001.

1. **Places, protected by a partial unique index** (`007-donation-slots.sql`). Every active booking (BOOKED or ATTENDED)
   holds a place number from 1 to the slot's capacity. The capacity is copied onto the booking and pinned to the slot by a
   foreign key on `(slot_id, slot_capacity)`, so a CHECK can require `place_no BETWEEN 1 AND slot_capacity`.
   `booking_one_active_per_place_idx ON booking (slot_id, place_no) WHERE status IN ('BOOKED', 'ATTENDED')` lets each
   place be held once. A 4-place slot cannot hold a 5th active booking: there is no 5th place to take.
2. **Lock the slot row** in `DonationService.book` (`SELECT … FROM donation_slot WHERE id = ? FOR UPDATE`). Bookings for
   one slot then run one after another, each sees the places already taken, and the loser gets a clear 409 `SLOT_FULL`
   instead of a database error. Cancelling frees the place for the next donor.

A second partial unique index, `booking_one_active_per_donor_idx ON booking (slot_id, donor_id)`, gives a donor one active
booking per slot, so a double tap records one booking (409 `ALREADY_BOOKED`).

`ConcurrentBookingIT` releases bookings at the same instant through the real HTTP service: two donors for the last place,
and ten donors for three places, five rounds each.

## Measured on PostgreSQL, 30 Oct (six sessions, three places, a 0.3 s pause between choosing a place and inserting)

| Protection | Active bookings on the 3-place slot |
| --- | --- |
| Slot lock and place index (what ships) | 3 |
| Place index only (lock removed) | 1: never over capacity; the others found their place taken and would be told the slot is full |
| Neither | 6: overbooked |

## Alternatives rejected

| Option | Why not |
| --- | --- |
| Count in Java, then insert | Read-then-write; the brief rules it out |
| A `booked` counter on the slot with `CHECK (booked <= capacity)`, kept up by a trigger | Works, but the count lives in two places and a trigger hides the rule from someone reading the service. Place numbers keep it in one declarative index, like the reservation rule |
| `SERIALIZABLE` isolation with retries | Correct, but losers fail with serialisation errors that must be retried; harder to explain and test |
| The slot lock alone | Protects only callers that take the lock. A bug, or a booking written another way, could still overbook |

## Cost accepted

- Bookings for one slot wait for each other (a few milliseconds each). Bookings for different slots do not.
- A booking carries `slot_capacity`, so changing a slot's capacity would also mean updating its bookings. The contract has
  no way to change a capacity, so this does not arise yet.

## What would make us reverse it

The client wants capacity changed after bookings exist (for example a nurse calls in sick). Then move the capacity check
to a counter with a trigger, or renumber places in the same transaction.
