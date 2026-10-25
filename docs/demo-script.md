# Monday checkpoint: service demo script

Journeys 1 to 3 from the brief, on the **deployed** service, using the demo data. About 6 minutes. Open the service's
Swagger page (`/`) in a clean browser window. Password for every account: `Demo-Password-1`.

## Before the session

- [ ] Wake the service 10 minutes early: open `<service>/actuator/health` until it shows `{"status":"UP"}` (free hosts sleep).
- [ ] Sign in once as each account below to check the logins work.
- [ ] Have the green CI run of `ConcurrentApprovalIT` and the red run from `evidence/no-protection` open in tabs.
- [ ] Have `docs/evidence.md` and `docs/data-model.md` open.

## 1. A blood bank records a unit (journey 1)

1. **POST /sessions** as `staff@lekki.bank.demo`. Copy `accessToken`, **Authorize**.
2. **POST /units** with today's collection date and an expiry in 30 days. Show **201** and the unit at Lekki.
3. Execute the same body again: **409 DUPLICATE_UNIT_REFERENCE**. Say: "a retry after a lost response is detected, not duplicated."
4. Add `"bankId": "<another bank>"` to the body: **422**. Say: "the bank comes from the token; staff cannot record units at another bank."

## 2. A hospital searches and requests the last unit (journey 2)

1. Sign in as `staff@yaba.hospital.demo`, **Authorize**.
2. **GET /availability** with `bloodGroup=O`, `rhesus=NEGATIVE`, `component=RED_CELLS`. Lagos Central shows **1** unit.
   Point at `computedAt` and `matching` ("exact group, no substitution").
3. **GET /requests?state=PENDING**: Yaba's pending emergency request to Lagos Central is there. (Ikorodu and Victoria
   Island have one each too: three hospitals, one unit.)
4. Show safe repeat submission: **POST /requests** with an `Idempotency-Key`, then the same key and body again:
   **200** with `Idempotent-Replayed: true`, and still one request.

## 3. The bank approves; three hospitals, one unit (journey 3)

1. Sign in as `staff@lagos-central.bank.demo`, **Authorize**.
2. **GET /requests?state=PENDING**, copy the three O-negative request ids.
3. **POST /requests/{id}/approval** for the first: **201 APPROVED**, one reservation with its "hold ends at" time.
4. Approve the second: **409 UNITS_NO_LONGER_AVAILABLE**, "Nothing was reserved; the request is still pending."
5. Switch to the CI tab: "Approving one at a time is easy. The hard case is three at the same instant." Show
   `ConcurrentApprovalIT`: three approvals released by a latch, five rounds, exactly one wins each time.
6. Show the red run: with the protection removed, all three won the same unit. Name the three layers (`docs/evidence.md`).

## 4. If there is time

- **GET /requests?state=UNIT_EXPIRED** as `admin@bloodlink.demo`: a unit that expired while reserved, and the request
  says what happened.
- Wrong role: as the Yaba hospital account, **POST /units**: **403 FORBIDDEN**. `RoleRulesIT` proves it in CI.
- Every response has an `X-Request-Id`; search the service log for it.

## Likely questions

See the "BloodLink Trade-offs & Defence Prep" document.
