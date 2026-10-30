# ADR-003: Lapsed holds and expired units are computed when read

- **Status:** Accepted, 2026-10-25. Built. The background sweep (`HoldAndExpirySweep`) built 30 Oct: once a minute it writes
  down lapsed holds and expired units with a System history entry. `SweepIT` shows reads are the same before and after it.
- **Decided by:** Oluwafisayomi Aiyetogbon, Okechukwu Daniel.

## Context

The brief says: "If the background job that releases holds does not run for an hour, a lapsed hold must still never be
shown as held. State what the service does." A unit past its expiry date must also never be reserved or shown as
available, even before any job marks it.

## Decision

The database computes the true state on every read, through views:

- `unit_status`: a unit past its expiry date (Lagos date) shows **EXPIRED**. A RESERVED unit with no active hold still
  running shows **AVAILABLE**.
- `reservation_status`: an ACTIVE reservation past its hold shows **LAPSED**. One whose unit has expired shows
  **UNIT_EXPIRED**.
- `blood_request_status`: an APPROVED request with nothing still held shows **LAPSED** or **UNIT_EXPIRED**.

Reads never change stored data. Write paths make the change for real: approval releases lapsed holds on the units it
takes, and a background sweep will tidy the rest. **If the job does not run for an hour, nothing visible changes.** The
stored rows are just out of date until the next write or sweep.

## Alternatives rejected

| Option | Why not |
| --- | --- |
| A scheduled job is the only thing that changes state | If it stops, holds stay "held" and expired units stay "available". That is the failure the brief describes |
| Update state during reads ("lazy" writes) | A read that writes can deadlock with other writes, and the brief says a read must not change data |
| Compute state in Java after loading rows | Each screen would repeat the rule. One view keeps one definition, used by every query |

## Cost accepted

- Every read joins the reservation table. This is measured: 1.3 ms for availability over 50,000 units
  (`docs/data-model.md`).
- Stored `state` and shown state can differ, which is confusing when reading the raw tables. The views are the source of
  truth.

## What would make us reverse it

The index analysis or the load test shows the views dominating response times. Then store the state and run the sweep
every minute, accepting at most one minute of staleness and stating it on screen.
