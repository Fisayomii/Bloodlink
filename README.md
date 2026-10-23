# BloodLink

Help hospitals find and reserve available blood units before time runs out. TeSA cohort 6 capstone, Team 4.

All data is fictional. The product makes no clinical decision, does no compatibility testing and never stores patient details.
Matching is by the exact blood group, rhesus type and component entered.

| | Address |
| --- | --- |
| Interface | _to add after first deploy_ |
| Service | _to add after first deploy_ |

## Repository

```
contract/openapi.yaml   The agreement between interface and service (single source of truth)
api/                    Spring Boot 4.1 service, Java 25
web/                    React + TypeScript interface (Ufoma)
docs/decisions.md       Every decision and contract change, dated, with two names
```

## Contract and mock

Needs Node 22.

```bash
npm ci
npm run lint:contract   # Spectral: fails on any warning, and on an operation missing x-safety
npm run mock            # Prism mock on http://localhost:4010
```

Notes on the mock:
- Send any `Authorization: Bearer <anything>`; without it the mock returns 401, as the service will.
- It returns the lowest 2xx code by default. For `POST /requests` that is the 200 replay, so send `Prefer: code=201` to see the first-submission response.
- Force an error to build its screen state: `Prefer: code=409` (for example on approval), `Prefer: code=422`, `Prefer: code=500`.
- Request validation failures come back as 422 from the mock. The real service returns 400 `MALFORMED_REQUEST` for unreadable input and 422 `VALIDATION_FAILED` for rule breaks.
- `X-Request-Id` comes back as a placeholder from the mock; the service echoes the real one.

## Service

Needs Java 25, Maven 3.9 and Docker (for Postgres and the integration tests).

```bash
docker run -d --name bloodlink-db -p 5432:5432 \
  -e POSTGRES_DB=bloodlink -e POSTGRES_USER=bloodlink -e POSTGRES_PASSWORD=bloodlink postgres:17-alpine
mvn -f api/pom.xml spring-boot:run          # http://localhost:8080/actuator/health
mvn -f api/pom.xml verify                   # checkstyle, unit tests, integration tests on real Postgres
```

Environment variables on the host: `DATABASE_URL` (JDBC form: `jdbc:postgresql://host:5432/db?sslmode=require`),
`DATABASE_USERNAME`, `DATABASE_PASSWORD`, `PORT` (set by most hosts), `DB_POOL_SIZE` (default 5).

## Pipeline

`.github/workflows/api.yml`: Contract lint and Build and test run on every pull request and push to `main`.
Deploy runs only on `main` after both pass: it calls the host's deploy hook (`API_DEPLOY_HOOK` secret)
and waits for `/actuator/health` at `API_URL` (repository variable). Turn the host's own auto-deploy off.

## Demonstration accounts

_To add with the seed data (Sun 25 Oct)._

## Simulated or out of scope

No real blood bank or hospital is involved. No SMS, push, payments or courier arrangements.
