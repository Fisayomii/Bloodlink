# BloodLink service

Help hospitals find and reserve available blood units before time runs out. TeSA cohort 6 capstone, Team 4.
This repository is the **service tier** (Java). The interface lives in its own repository: _bloodlink-web link to add_.

All data is fictional. The product makes no clinical decision, does no compatibility testing and never stores patient details.
Matching is by the exact blood group, rhesus type and component entered.

| | Address |
| --- | --- |
| Interface | _to add after first deploy_ |
| Service | _to add after first deploy_ |

## Repository

```
contract/openapi.yaml   The agreement between interface and service: the single source of truth
contract/package.json   Node tooling for the contract only (Spectral lint, Prism mock)
src/                    Spring Boot 4.1 service, Java 25
docs/decisions.md       Every decision and contract change, dated, with two names
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
| `PORT` | `8080` | Most hosts set it |
| `DB_POOL_SIZE` | `5` | Keep small on free tiers |

## Pipeline

`.github/workflows/api.yml`: **Contract lint** and **Build and test** run on every pull request and every push to `main`.
**Deploy** runs only on `main` after both pass. It calls the host's deploy hook (`API_DEPLOY_HOOK` secret) and waits for
`/actuator/health` at `API_URL` (repository variable). Turn the host's own auto-deploy off.

## Demonstration accounts

_To add with the seed data (Sun 25 Oct)._

## Simulated or out of scope

No real blood bank or hospital is involved. No SMS, push, payments or courier arrangements.
