# Evidence: the reservation rule fails without its protection

The brief: "An automated test that performs the two conflicting operations at the same moment against the deployed
service and asserts that exactly one succeeded. The team then removes the protection and records the same test failing.
A test that passes both before and after proves nothing and earns nothing."

## The test

`src/test/java/africa/tesa/bloodlink/request/ConcurrentApprovalIT.java`. Each round puts exactly one O-negative red-cell
unit in a bank, has three hospitals (Yaba, Ikorodu, Victoria Island) request it, then releases the bank's three
approvals at the same instant through the real HTTP service. It asserts one 201, two 409 `UNITS_NO_LONGER_AVAILABLE`,
exactly one ACTIVE reservation on the unit, and the two losing requests still PENDING. Five rounds.

## The protection (three layers, in `RequestService.approve`)

1. `SELECT ... FOR UPDATE OF u SKIP LOCKED` when choosing units.
2. `UPDATE unit SET state = 'RESERVED' WHERE id IN (...) AND state = 'AVAILABLE'`, refused if fewer rows change.
3. Partial unique index `reservation_one_active_hold_idx ON reservation (unit_id) WHERE status = 'ACTIVE'`.

## How the failing run is produced (never merged)

Apply `EVIDENCE-ONLY-remove-reservation-protection.patch` on a throwaway branch. It removes all three layers and adds a
0.2-second pause between reading the unit and reserving it. The pause is declared here because it only widens the
window in which the race happens; it does not create the race. Without the protection, three approvals that read the
unit before any of them writes will all reserve it.

```powershell
git checkout main
git pull
git checkout -b evidence/no-protection
git am "$HOME\Downloads\EVIDENCE-ONLY-remove-reservation-protection.patch"
git push -u origin evidence/no-protection
```

Open a **draft** pull request from `evidence/no-protection`. CI runs and **Build and test** goes red:
`ConcurrentApprovalIT` reports three 201s and three ACTIVE reservations on one unit, and `SchemaRulesIT` reports that a
second ACTIVE hold was accepted. Screenshot the failing check and the test report, save them under `docs/evidence/`,
then close the pull request without merging and delete the branch.

## Results

| Date | Run | Protection | Result | Link |
| --- | --- | --- | --- | --- |
| 2026-10-25 | Local PostgreSQL 16, approval SQL raced by three connections, 5 rounds | On | 5/5 rounds: one 201, two 409, 1 active hold | |
| 2026-10-25 | Same, protection removed (all three layers) | Off | 5/5 rounds: three 201s, 3 active holds on one unit | |
| 2026-10-25 | CI, `ConcurrentApprovalIT`, every pull request and push to `main` | On | Green: 5/5 rounds, one 201, two 409, one active hold | Actions › api › Build and test on `main` https://github.com/Fisayomii/Bloodlink/actions/runs/40191021808 |
| 2026-10-25 | CI, `ConcurrentApprovalIT`, draft PR `evidence/no-protection` | Off | Expected red: three 201s, three active holds on one unit (record what the run shows) | https://github.com/Fisayomii/Bloodlink/actions/runs/40192167070  [screenshot](evidence/no-protection-red.png) |
| Every deploy | Deployed service, `scripts/race-deployed.mjs` (pipeline job "Rule check on the deployed service") | On | Expected: one 201, two 409, requests APPROVED, PENDING, PENDING | Actions › api › latest `main` run |

## Screenshots

### Protection removed: the test fails

Draft PR `evidence/no-protection`, **Build and test** red. `ConcurrentApprovalIT` reports three 201s and three
ACTIVE reservations on one unit.

![Build and test failing with the protection removed](evidence/no-protection-red.png)

### Protection on: the test passes

Same test on `main`, green: one 201, two 409s, one active hold, five rounds.

![Build and test passing on main](evidence/main-green.png)
