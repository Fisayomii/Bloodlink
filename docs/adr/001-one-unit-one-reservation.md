# ADR-001: Protecting "one unit, one reservation" in the database

- **Status:** Accepted, 2026-10-25. Built and tested.
- **Decided by:** Oluwafisayomi Aiyetogbon, Okechukwu Daniel.

## Context

The rule BloodLink depends on is that a unit is reserved for at most one request at a time. The brief says three
hospitals requesting the last unit at the same moment must produce exactly one reservation. It also says: "Reading a
value and then writing it in application code does not meet this requirement."

## Decision

Reservations happen when a bank approves a request. Approval runs in one transaction, protected by three layers. Each
layer would stop the race on its own; together, removing any one of them is caught by a test.

1. **Lock the chosen rows:** `SELECT … FOR UPDATE OF u SKIP LOCKED` when picking units. A second approval skips the
   locked unit instead of waiting for it, finds nothing left, and is refused at once.
2. **Guarded state change:** `UPDATE unit SET state = 'RESERVED' WHERE id IN (…) AND state = 'AVAILABLE'`. If fewer rows
   change than were picked, someone else got there first. The whole transaction rolls back and the answer is
   409 `UNITS_NO_LONGER_AVAILABLE`, with the request still PENDING.
3. **Partial unique index:** `reservation_one_active_hold_idx ON reservation (unit_id) WHERE status = 'ACTIVE'`. The
   database itself refuses a second active hold, even if the code above had a bug or someone wrote SQL by hand.

`ConcurrentApprovalIT` sends three approvals for the last unit at the same instant through the real HTTP service,
against real PostgreSQL, five rounds. `scripts/race-deployed.mjs` does the same against the deployed service after every
deploy. With all three layers removed, the test fails (`docs/evidence.md`).

## Alternatives rejected

| Option | Why not |
| --- | --- |
| Check availability in Java, then insert | Read-then-write: two requests both read "available". The brief rules it out |
| `SERIALIZABLE` isolation with retries | Correct, but losers fail with serialisation errors that must be retried. More failures under load, and harder to explain |
| Plain `FOR UPDATE` without `SKIP LOCKED` | Racing approvals queue behind each other, then each must re-check. Slower, same result |
| A unique constraint only | Protects the data, but the loser would see a 500 rather than a clear 409 unless mapped carefully. It is kept as the backstop, not the only guard |

## Cost accepted

- A racing approval is refused immediately rather than waiting for the first to finish. If the first rolls back for
  another reason, the second must be retried by the bank.
- `SKIP LOCKED` means a unit locked by another approval looks "not available" for that instant.

## What would make us reverse it

The load test (due 31 Oct–2 Nov) shows approvals refused with 409 while matching units were in fact free, because they
were locked by unrelated approvals. Then move to `FOR UPDATE` (queueing) for that query.
