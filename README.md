# BloodLink service

Help hospitals find and reserve available blood units before time runs out. TeSA cohort 6 capstone, Team 4.
This repository is the **service tier** (Java). The interface (React + Tailwind, by Ufoma Onowho) lives in its own
repository, `bloodlink-web`, and is deployed at the address below.

All data is fictional. The product makes no clinical decision, does no compatibility testing and never stores patient details.
Matching is by the exact blood group, rhesus type and component entered.

| | Address |
| --- | --- |
| Interface | https://bloodlink-orpin.vercel.app |
| Service | https://bloodlink-o2ie.onrender.com |

## Repository

```
contract/openapi.yaml   The agreement between interface and service: the single source of truth
contract/package.json   Node tooling for the contract only (Spectral lint, Prism mock)
src/                    Spring Boot 4.1 service, Java 25
docs/decisions.md       Every decision and contract change, dated, with two names
docs/adr/               Five decision records (context, decision, rejected options, cost, what would reverse it)
docs/data-model.md      Tables, the rules each constraint protects, indexes and measured query plans
docs/evidence.md        The concurrency test passing, and failing with the protection removed
docs/ai-log.md          AI tools used, what was accepted, and what was checked and found wrong
scripts/                Seed-data generator; the race check against the deployed service
```

**The contract lives here and only here.** The interface repository copies it with `npm run contract:sync`,
and its CI fails if its copy differs from this repository's `main`. To change the contract: open a PR here that
edits `contract/openapi.yaml` and adds a line to `docs/decisions.md`, get one review from each side, merge,
then the interface runs `contract:sync`.

## Run the whole system locally

1. **Database and service** (needs Java 25, Maven 3.9, Docker):

   ```bash
   docker run -d --name bloodlink-db -p 5432:5432 \
     -e POSTGRES_DB=bloodlink -e POSTGRES_USER=bloodlink -e POSTGRES_PASSWORD=bloodlink postgres:17-alpine
   mvn spring-boot:run          # http://localhost:8080/actuator/health
   ```

2. **Interface**: clone the interface repository and follow its README, pointing `VITE_API_URL` at `http://localhost:8080`.

Tests: `mvn verify` runs checkstyle, unit tests and integration tests on a real Postgres (Testcontainers, needs Docker).

## API docs (Swagger UI)

With the service running, open **http://localhost:8080/** (or the deployed address).

- It shows `contract/openapi.yaml` exactly as committed (packaged into the jar and served at `/openapi.yaml`),
  not a document generated from the code. A test checks the served file matches the committed one.
- **Servers** defaults to "This service", so **Try it out** calls the running service. Pick the Prism mock to compare.
- Each call gets its own `X-Request-Id` (`swagger-...`), so you can find it in the service logs.
- Paths that are in the contract but not built yet return 401, or 404 once signed in. That is expected until each feature lands.
- Start the app with `mvn spring-boot:run`. Running the main class straight from IntelliJ skips the Maven step that copies the
  contract, so `/openapi.yaml` would be missing.

## Contract and mock

Needs Node 22.

```bash
cd contract
npm ci
npm run lint:contract   # Spectral: fails on any warning, and on an operation missing x-safety
npm run mock            # Prism mock on http://localhost:4010
```

## Logs

Every line carries the request id, e.g. `INFO [web-7f9c2b1e]`, and each request writes one access line (method, path, status, time).
Send `X-Request-Id` from the interface and search the logs for it to follow one user's journey. On the host, set
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` to get one JSON object per line.

## Host settings

| Variable | Example | Notes |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://host:5432/db?sslmode=require` | JDBC form, not `postgres://` |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | | From the managed Postgres |
| `ALLOWED_ORIGINS` | `https://bloodlink.netlify.app` | The interface's address, comma-separated. Anything else is refused by CORS. |
| `JWT_SECRET` | 48+ random characters | Signs access tokens. Required on the host; the local default logs a warning |
| `JWT_TTL` | `8h` | How long a sign-in lasts (contract: 8 hours) |
| `LIQUIBASE_CONTEXTS` | `demo` | `demo` loads the demonstration accounts; `none` leaves them out |
| `PORT` | `8080` | Most hosts set it |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` | One JSON object per log line on the host |
| `DB_POOL_SIZE` | `5` | Keep small on free tiers |

## Deployment

Render (service, Docker) and Neon (PostgreSQL), both free and in Frankfurt. Step by step, including settings and the
free-tier terms checked: `docs/deploy.md`. On demo days set the repository variable `KEEP_WARM=true` so the free service
does not fall asleep.

## Pipeline

`.github/workflows/api.yml`: **Contract lint** and **Build and test** run on every pull request and every push to `main`.
**Deploy** runs only on `main` after both pass. It calls the host's deploy hook (`API_DEPLOY_HOOK` secret) and waits for
`/actuator/health` at `API_URL` (repository variable). Turn the host's own auto-deploy off.
Before the deploy, **Contract tests** start the new build with a throwaway database and check every answer against the
contract with Schemathesis, as each role (`scripts/contract-test.sh`); any divergence stops the deploy. Deploy then waits
until `/actuator/info` shows the new commit. After it, **Contract tests on the deployed service** repeat a shorter run
against the live address, and **Rule check on the deployed service** runs `scripts/race-deployed.mjs`: three hospitals ask the live
service for the last unit of a group and the bank's three approvals are sent together; exactly one must succeed.
Run it yourself with `node scripts/race-deployed.mjs https://bloodlink-o2ie.onrender.com` (Node 22).

## Demonstration accounts

All fictional. Every account's password is **`Demo-Password-1`**.

| Role | Email | Organisation |
| --- | --- | --- |
| Administrator | `admin@bloodlink.demo` | none |
| Bank staff | `staff@lagos-central.bank.demo` | Lagos Central Blood Bank (demo), Ikeja |
| Bank staff | `staff@lekki.bank.demo` | Lekki Regional Blood Centre (demo), Lekki |
| Bank staff | `staff@surulere.bank.demo` | Surulere Community Blood Bank (demo), Surulere |
| Hospital staff | `staff@yaba.hospital.demo` | Yaba General Hospital (demo), Yaba |
| Hospital staff | `staff@ikorodu.hospital.demo` | Ikorodu Children's Hospital (demo), Ikorodu |
| Hospital staff | `staff@vi.hospital.demo` | Victoria Island Medical Centre (demo), Victoria Island |
| Donor | `donor@bloodlink.demo` | none |

**Try it in Swagger** (http://localhost:8080/): open **POST /sessions → Try it out**, sign in with one of the accounts above,
copy `accessToken` from the response, click **Authorize** (padlock, top right), paste it, and every call now runs as that user.

The accounts come from `002-demo-accounts.sql`, loaded by the Liquibase context `demo`. Set `LIQUIBASE_CONTEXTS=none` to leave them out.

## Demonstration data

`005-demo-data.sql`, also in the `demo` context, adds **68 units** (all eight groups at each of the three banks, in every
state) and **24 requests** (every state, from all three hospitals), with their reservations and history. Dates are relative
to the moment the database is first set up. It is generated by `scripts/gen_seed.py`, which checks the brief's
requirements before writing the file; edit the generator, then run `python scripts/gen_seed.py` from the repository root.

Cases worth showing:

| Case | Where to see it |
| --- | --- |
| The race: Lagos Central has **one** O-negative red-cell unit; Yaba, Ikorodu and Victoria Island each have a pending emergency request for it | `GET /requests?state=PENDING` as `staff@lagos-central.bank.demo`; approve one, then another gets 409 |
| Two units that **expired while reserved**: unit EXPIRED, reservation UNIT_EXPIRED, request UNIT_EXPIRED with a System history entry | `GET /requests?state=UNIT_EXPIRED` as the administrator |
| A unit past its expiry date that **no job has marked** yet: still shown as EXPIRED | `GET /units?state=EXPIRED` as `staff@surulere.bank.demo` |
| Lapsed holds, declined requests with reasons, issued units, cancelled requests | `GET /requests` as the administrator |
| AB-negative running low (one unit per bank) | `GET /availability?bloodGroup=AB&rhesus=NEGATIVE` |

## Issue, reversal, cancellation (contract v0.2)

| Who | Call | What happens |
| --- | --- | --- |
| Bank staff | `POST /requests/{id}/issue` | Only while APPROVED (holds running, no unit expired). Units ISSUED, request FULFILLED |
| Bank staff | `POST /requests/{id}/issue/reversal` with a reason | Units back to AVAILABLE only if not expired (otherwise EXPIRED); request PENDING again; both history entries kept |
| The requesting hospital only | `POST /requests/{id}/cancellation` with a reason | Request CANCELLED; any held units straight back in stock |
| Bank staff | `POST /requests/{id}/hold-release` with `forRequestId` and a reason | ADR-002: a routine hold released for a matching emergency, in one transaction with the emergency's approval |
| Bank staff, administrator | `GET /units/{id}` | The unit and its full history |

Try it in Swagger: as `staff@lagos-central.bank.demo`, approve one of the pending O-negative requests, then issue it, then
reverse the issue and open `GET /units/{id}` for the unit: both ISSUED and ISSUE_REVERSED are there.

## The rule this product depends on

A unit is reserved for at most one request at a time, even when three hospitals ask for the last unit in the same second.
Three layers in the database protect it (ADR-001). `ConcurrentApprovalIT` proves it in CI on every pull request, the
pipeline repeats it against the deployed service, and `docs/evidence.md` records the same test failing with the
protection removed.

Background work, and what happens if it does not run for an hour: nothing visible changes. Lapsed holds and expired units
are computed on every read (ADR-003), so a lapsed hold is never shown as held and an expired unit is never offered.

## Simulated or out of scope

- No real blood bank, hospital, donor or patient is involved; every name is fictional and marked "(demo)".
- **The one seeded APPROVED request holds its two units for 10 days**, so the RESERVED state stays visible during the
  assessment. Real holds last 1 to 4 hours depending on urgency (`HOLD_*` settings).
- Seeded FULFILLED and CANCELLED requests were written directly as data. Issuing, reversing an issue and cancelling through
  the service work for any request created through the service (see "Issue, reversal, cancellation" below).
- No SMS, push, payments or courier arrangements.

## Known limitations (26 Oct checkpoint)

- **Not built yet:** the hold and expiry sweep jobs (reads are already correct without them), donation slots and
  bookings, and the dashboard. In contract v0.2; planned for 28–30 Oct. Until then those paths answer 404.
- **Partial approval** is not allowed: approval reserves every unit asked for, or nothing. Asked the client.
- **Free hosting sleeps:** the first request after 15 idle minutes waits for the service to wake (measured 28 s).
- **Sign-in** has no refresh tokens, password reset or rate limiting (demonstration accounts only).
- **Still to measure:** the load test with its saturation point, contract tests against the deployed service, and the
  index analysis for five queries (31 Oct–2 Nov). Screenshots and performance figures are added then.
