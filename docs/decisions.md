# BloodLink decisions

One dated line per decision or contract change: what changed, why, and the two engineers who agreed.
The service implements only paths in `contract/openapi.yaml`; the interface calls only those paths.
Longer reasoning goes in `docs/adr/`.

Team 4: Okechukwu Daniel (service), Oluwafisayomi Aiyetogbon (service), Ufoma Onowho (interface). Supervisor: Chukwuma.

> Fill in "Agreed by" before merging. Entries marked _to confirm_ were drafted before the team agreed them.

## Areas

| Date | Decision | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-23 | Engineer A, data and rules plus seed data, logging and the load test: **Okechukwu Daniel** (_to confirm_) | Brief's split for two service engineers | |
| 2026-10-23 | Engineer B, API and access plus pipeline, both deployments, contract tests and the migration job: **Oluwafisayomi Aiyetogbon** (_to confirm_) | Same; B works closest to the interface through the contract | |
| 2026-10-23 | Team lead: _to agree_ | Brief asks for one lead across both products | |

## Stack

| Date | Decision | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-23 | Java 25 (LTS) on Spring Boot 4.1.1, Maven | 25 is the current LTS; Boot 4.1 supports Java 17 to 26. Rejected: Java 21 on Boot 3.5, because 3.5's free support ended 30 Jun 2026. Cost: fewer examples online for Boot 4. | Oluwafisayomi Aiyetogbon, |
| 2026-10-23 | PostgreSQL 17, migrations with Liquibase (SQL changesets with rollback) | Brief requires reversible migrations. Rejected: Flyway, because undo is a paid feature. | Oluwafisayomi Aiyetogbon, |
| 2026-10-23 | Integration tests on real Postgres via Testcontainers; contract checked by Spectral now, Schemathesis against the deployed URL from Fri | Brief: "a real database rather than a substitute" | Oluwafisayomi Aiyetogbon, |
| 2026-10-23 | Self-issued JWT (demonstration accounts, BCrypt passwords) | No real identity verification is allowed. Rejected: Keycloak or a hosted provider, as more moving parts on a free tier. | Oluwafisayomi Aiyetogbon, |
| 2026-10-23 | Interface: React + TypeScript (Vite), types generated from the contract | Interface engineer's choice | Ufoma Onowho, |
| 2026-10-24 | Separate repositories per tier: `bloodlink` (service, owns the contract) and `bloodlink-web` (interface, React + Tailwind) | Each side owns its repository and pipeline. The brief allows either layout; it asks for two live addresses per product. Rejected: one repository with `api/` and `web/`. Cost: the contract is copied into the interface repository, so it can drift. Mitigation: `contract:sync` plus a CI check in `bloodlink-web` that fails when its copy differs from `bloodlink` `main`. Reverse if the drift check fails repeatedly. To confirm with Chukwuma. | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Data access with plain SQL through Spring's `JdbcClient`; no JPA/Hibernate | The rules depend on exact SQL (conditional updates, `FOR UPDATE SKIP LOCKED`, partial unique indexes), and explicit SQL is easier to review and explain. Rejected: JPA, which hides the SQL that protects the rules. Cost: more mapping code by hand. Reverse if mapping code starts to dominate the service. | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Business rules enforced in the schema wherever the database can: one active hold per unit (partial unique index), unit states and enums (CHECK), units only at blood banks (foreign key on `(id, type)`), declined requests carry a reason, history is append-only (trigger). See `docs/data-model.md`. | The brief: "Reading a value and then writing it in application code does not meet this requirement." | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Every failure returns the contract's Error shape, including 401 and 403; X-Request-Id accepted if safe (letters, digits, `._:-`, up to 64), otherwise replaced with a UUID, and logged on every line | One shape the interface can act on; one id to follow a journey across both tiers. Unsafe ids are replaced so nobody can inject fake log lines. | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Sign-in issues a self-signed JWT (HS256, key from `JWT_SECRET`, 8-hour expiry, claims `sub`, `role`, `org`); passwords stored as BCrypt (cost 10) | Brief rules out real identity verification; one dependency, no identity server to host. Rejected: Keycloak or a hosted provider. Cost: no refresh tokens, password reset or sign-in rate limiting. Reverse if real users enter scope. | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Wrong email and wrong password get the same 401 `INVALID_CREDENTIALS`, and take the same time (an unknown email is still checked against a dummy hash) | Does not reveal which emails have accounts | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Role rules per path in `SecurityConfig` (e.g. only bank staff record units; only hospital staff request units); organisation rules in each service | Brief: authorisation "enforced by the service, not by the interface", with a wrong-role test that fails when the check is removed (`RoleRulesIT`) | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Request bodies with fields outside the contract are refused (422) | Keeps a patient name out: there is no field for one, so sending one fails | Oluwafisayomi Aiyetogbon, |
| 2026-10-25 | Demo organisations and accounts as a Liquibase changeset in context `demo`, with rollback; ids match the contract examples | Brief: seed data and demo credentials in the README. Ids matching the examples make Swagger's example values work against the real service. | Oluwafisayomi Aiyetogbon, |
| 2026-10-24 | Swagger UI at `/` shows the committed contract (`/openapi.yaml`), not one generated from the code | The docs must show the agreement with the interface. Rejected: springdoc generating the document from controllers, which would describe the code rather than the contract and could drift. Cost: Swagger UI loads from a CDN, so the page needs internet. | Oluwafisayomi Aiyetogbon, |
| 2026-10-24 | Service allows cross-origin calls only from `ALLOWED_ORIGINS` (the interface's address) | The interface is a separate site; nothing else should call the service from a browser | |

## Hosting (free tiers only)

Check each provider's own pricing page before relying on it. Record what it says and the date checked.

| Date checked | Tier | Provider and plan | Sleeps when idle? | Hours or credit limit | Database expiry or pause | Checked by |
| --- | --- | --- | --- | --- | --- | --- |
| | Service (Docker) | | | | n/a | |
| | Database | | | | | |
| | Interface (static) | | | | n/a | |

Team 4 needs two services and two databases (BloodLink and FairSeat). Confirm how many free projects or databases one account allows.

Deploy rule: the host's auto-deploy is **off**. GitHub Actions calls the host's deploy hook only after contract lint and build-and-test pass on `main`.

## Contract changes

| Date | Change | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-24 | Contract tooling moved to `contract/package.json`; service moved from `api/` to the repository root | Repository now holds only the service and the contract | |
| 2026-10-23 | v0.1.0 created: `POST /sessions`, `GET /organisations`, `GET` and `POST /units`, `GET /availability`, `GET` and `POST /requests`, `GET /requests/{id}`, `POST /requests/{id}/approval`, `POST /requests/{id}/declination`, `GET /actuator/health`, shared `Error` shape | Monday journey 1 to 3 | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-23 | Sign-in is `POST /sessions`, not `/auth/login`; decline is `POST /requests/{id}/declination`, not `/decline` | Brief: resources are named as things, not actions | |
| 2026-10-23 | `POST /requests` requires an `Idempotency-Key` header; replay returns 200 and `Idempotent-Replayed: true` | Brief: at least one write safe to submit twice, with the client's part documented | |
| 2026-10-23 | Approval reserves units; creating a request reserves nothing. Approval is all or nothing. | Brief journey 3: "The blood bank approves, which reserves specific units". Partial approval is a question for the client. | |
| 2026-10-23 | Non-visible requests return 404, not 403 | Do not reveal other hospitals' requests | |

## Open questions for the client

1. Can a bank approve part of a request when fewer units are free than asked for? (v0: no, all or nothing.)
2. How long should a hold last, and can an emergency request take a unit held for a routine one? (Design problem, ADR-002.)
3. When a reserved unit expires, should it end as `EXPIRED` or `DISCARDED`? (v0: `EXPIRED`, and the request becomes `UNIT_EXPIRED`.)
4. Do donors need an account to book a slot?
5. Is final submission on 3 or 5 November?
6. (Chukwuma) Are separate interface and service repositories acceptable? The brief says "a public repository".
