# BloodLink decisions

One dated line per decision or contract change: what changed, why, and the two engineers who agreed.
The service implements only paths in `contract/openapi.yaml`; the interface calls only those paths.
Longer reasoning goes in `docs/adr/`.

Team 4: Okechukwu Daniel (service), Oluwafisayomi Aiyetogbon (service), Ufoma Onowho (interface). Supervisor: Chukwuma.

> Every line names the two people who agreed it. Data and rules lines were reviewed by Okechukwu Daniel; contract and
> interface-facing lines were agreed with Ufoma Onowho. Longer reasoning: `docs/adr/`.

## Areas

| Date | Decision | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-23 | Engineer A, data and rules plus seed data, logging and the load test: **Okechukwu Daniel** | Brief's split for two service engineers | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-23 | Engineer B, API and access plus pipeline, both deployments, contract tests and the migration job: **Oluwafisayomi Aiyetogbon** | Same; B works closest to the interface through the contract | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-24 | Okechukwu Daniel's laptop failed, so Oluwafisayomi Aiyetogbon wrote Engineer A's code for the Monday checkpoint (schema, migrations, rules, seed data, logging). Daniel reviewed the data model, the rules and the pull requests from his phone, and takes back the load test and the index analysis when his laptop is repaired | Brief: "An engineer who finishes their area early takes work from whichever area is behind, and records that they did so." | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Team lead: **Oluwafisayomi Aiyetogbon**, coordinating BloodLink and FairSeat | Brief asks each team to designate one lead across both products | Oluwafisayomi Aiyetogbon, Ufoma Onowho |

## Stack

| Date | Decision | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-23 | Java 25 (LTS) on Spring Boot 4.1.1, Maven | 25 is the current LTS; Boot 4.1 supports Java 17 to 26. Rejected: Java 21 on Boot 3.5, because 3.5's free support ended 30 Jun 2026. Cost: fewer examples online for Boot 4. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-23 | PostgreSQL 17, migrations with Liquibase (SQL changesets with rollback) | Brief requires reversible migrations. Rejected: Flyway, because undo is a paid feature. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-23 | Integration tests on real Postgres via Testcontainers; contract checked by Spectral now, Schemathesis against the deployed URL from 31 Oct | Brief: "a real database rather than a substitute" | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-23 | Self-issued JWT (demonstration accounts, BCrypt passwords) | No real identity verification is allowed. Rejected: Keycloak or a hosted provider, as more moving parts on a free tier. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-23 | Interface: React + TypeScript (Vite), types generated from the contract | Interface engineer's choice | Ufoma Onowho, Oluwafisayomi Aiyetogbon |
| 2026-10-24 | Separate repositories per tier: `bloodlink` (service, owns the contract) and `bloodlink-web` (interface, React + Tailwind) | Each side owns its repository and pipeline. The brief allows either layout; it asks for two live addresses per product. Rejected: one repository with `api/` and `web/`. Cost: the contract is copied into the interface repository, so it can drift. Mitigation: `contract:sync` plus a CI check in `bloodlink-web` that fails when its copy differs from `bloodlink` `main`. Reverse if the drift check fails repeatedly. Confirmed by the supervisor, Chukwuma, 2026-10-25. ADR-005. | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Data access with plain SQL through Spring's `JdbcClient`; no JPA/Hibernate | The rules depend on exact SQL (conditional updates, `FOR UPDATE SKIP LOCKED`, partial unique indexes), and explicit SQL is easier to review and explain. Rejected: JPA, which hides the SQL that protects the rules. Cost: more mapping code by hand. Reverse if mapping code starts to dominate the service. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Business rules enforced in the schema wherever the database can: one active hold per unit (partial unique index), unit states and enums (CHECK), units only at blood banks (foreign key on `(id, type)`), declined requests carry a reason, history is append-only (trigger). See `docs/data-model.md`. | The brief: "Reading a value and then writing it in application code does not meet this requirement." | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Every failure returns the contract's Error shape, including 401 and 403; X-Request-Id accepted if safe (letters, digits, `._:-`, up to 64), otherwise replaced with a UUID, and logged on every line | One shape the interface can act on; one id to follow a journey across both tiers. Unsafe ids are replaced so nobody can inject fake log lines. | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Sign-in issues a self-signed JWT (HS256, key from `JWT_SECRET`, 8-hour expiry, claims `sub`, `role`, `org`); passwords stored as BCrypt (cost 10) | Brief rules out real identity verification; one dependency, no identity server to host. Rejected: Keycloak or a hosted provider. Cost: no refresh tokens, password reset or sign-in rate limiting. Reverse if real users enter scope. | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Wrong email and wrong password get the same 401 `INVALID_CREDENTIALS`, and take the same time (an unknown email is still checked against a dummy hash) | Does not reveal which emails have accounts | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Role rules per path in `SecurityConfig` (e.g. only bank staff record units; only hospital staff request units); organisation rules in each service | Brief: authorisation "enforced by the service, not by the interface", with a wrong-role test that fails when the check is removed (`RoleRulesIT`) | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Request bodies with fields outside the contract are refused (422) | Keeps a patient name out: there is no field for one, so sending one fails | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Demo organisations and accounts as a Liquibase changeset in context `demo`, with rollback; ids match the contract examples | Brief: seed data and demo credentials in the README. Ids matching the examples make Swagger's example values work against the real service. | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Effective state computed in the database (`unit_status` view, ADR-003): past expiry shows `EXPIRED`, a lapsed hold shows `AVAILABLE`; reads never change data | Brief: a lapsed hold "must still never be shown as held" if the job does not run for an hour. One definition in the database, used by every read. Rejected: relying on the background job. Cost: every read joins the reservation check. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Business dates are Lagos dates (`SET TIME ZONE 'Africa/Lagos'` on every connection) | A unit expiring "today" means today in Lagos, not UTC (which would roll over at 01:00) | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Availability written out with the same rule as the view, so each half uses its own partial index; a test checks the two agree | Measured 1.3 ms and 417 rows examined for 417 returned on 50,000 units (`docs/data-model.md`) | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Unit body values checked as text against the allowed lists (422 naming the field); query filters use enums (400 for an unknown value) | The interface can show a body error next to its field; the contract classes a bad query value as malformed | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Bank staff always see only their own bank's units, whatever `bankId` they send; the bank of a new unit comes from the token, and a `bankId` in the body is refused | Brief: organisation rules "enforced by the service, not by the interface" | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Approval protected by three layers (ADR-001): `FOR UPDATE SKIP LOCKED` when choosing units, a guarded `UPDATE ... AND state = 'AVAILABLE'`, and the partial unique index; `ConcurrentApprovalIT` races three approvals for the last unit (5 rounds) and `docs/evidence.md` records it failing with the protection removed | Brief's key rule. Rejected: `SERIALIZABLE` isolation with retries (more failures under load, harder to explain); a check in Java (read-then-write). Cost: a racing approval is refused immediately rather than queued. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Units are reserved on approval, not when the hospital requests; approval is all-or-nothing | Brief journey 3: "The blood bank approves, which reserves specific units". Partial approval: see the 2026-10-25 line below. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Hold length by urgency: emergency 1 h, urgent 2 h, routine 4 h (`HOLD_*` settings). No automatic pre-emption; bank staff may release a routine hold for an emergency as a recorded decision (planned, contract v0.2) | The design problem. Decision, rejected options, cost and reversal trigger: ADR-002 | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Approval applies lapsed holds on the units it takes: the old reservation becomes LAPSED, its request LAPSED (System history entry) if nothing else is held | Brief: lapse "applied" on write paths; reads only compute it (`reservation_status`, `blood_request_status` views) | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | `POST /requests` idempotent by `(created_by, idempotency_key)` unique constraint + `INSERT ... ON CONFLICT DO NOTHING`; replay compared by SHA-256 of the body (neededBy normalised to UTC) | Safe even when two submissions arrive at the same moment; a different body with the same key is a client error (422) | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Another hospital's or bank's request answers 404 on read and on decisions | Does not reveal which requests exist | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Demonstration data generated by `scripts/gen_seed.py` into `005-demo-data.sql` (context `demo`), dates relative to first setup, rows marked `SEED-` / `payload_hash = 'seed'`; `SeedDataIT` checks the brief's counts on every build | Brief: 60+ units across 8 groups and 3 banks in every state, 20+ requests, two units expired while reserved. A generator with its own checks beats 150 hand-typed rows. Cost: dates drift from "today" after setup. Donation slots follow with the donation feature. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | One seeded APPROVED request holds its units for 10 days (labelled simulated in the README) | Keeps the RESERVED state visible throughout the assessment; real holds would lapse within hours of setup | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-24 | Swagger UI at `/` shows the committed contract (`/openapi.yaml`), not one generated from the code | The docs must show the agreement with the interface. Rejected: springdoc generating the document from controllers, which would describe the code rather than the contract and could drift. Cost: Swagger UI loads from a CDN, so the page needs internet. | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-24 | Service allows cross-origin calls only from `ALLOWED_ORIGINS` (the interface's address) | The interface is a separate site; nothing else should call the service from a browser | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-25 | Partial approval: **not allowed for now**. Approval reserves every unit asked for, or nothing (409, request stays PENDING). Question sent to the client | Keeps one request = one decision, and the race protection covers the whole set. Options: (a) all or nothing; (b) approve fewer, the request becomes partly approved and the rest stays open; (c) approve fewer and close the rest. Cost: a hospital needing 3 when 2 are free must send a new request for 2. Reverse if the client says banks need (b) | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | A unit that expires while reserved ends as **EXPIRED**: unit EXPIRED, reservation UNIT_EXPIRED, request UNIT_EXPIRED with a System history entry. DISCARDED stays a staff action (damaged or failed bag) | EXPIRED is what the clock says and needs nobody to act, so the request can never stay open. DISCARDED records a person's decision about a physical bag. Rejected: ending as DISCARDED automatically, which would claim a disposal nobody recorded. Question sent to the client: do banks also need to record disposal of expired bags (would add EXPIRED → DISCARDED as a staff action)? | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | The key rule is also checked against the deployed service after every deploy: `scripts/race-deployed.mjs` (pipeline job "Rule check on the deployed service") | Brief: the concurrency test runs "against the deployed service". `ConcurrentApprovalIT` in CI is the one shown failing without the protection | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Five decision records in `docs/adr/`: 001 the protected rule, 002 hold length, 003 state computed on read, 004 SQL and migrations, 005 contract and repositories | Brief: five records, each with rejected options, a named cost and a reversal trigger | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |

## Hosting (free tiers only)

Checked on each provider's own pricing or documentation page. Full steps in `docs/deploy.md`.

| Date checked | Tier | Provider and plan | Sleeps when idle? | Hours or credit limit | Database expiry or pause | Checked by |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-25 | Service (Docker) | Render, Free web service, Frankfurt | Yes, after 15 minutes without traffic | 750 instance hours per workspace per month (shared with FairSeat) | n/a | Oluwafisayomi Aiyetogbon |
| 2026-10-25 | Database | Neon, Free plan, AWS Frankfurt | Compute suspends after 5 minutes idle; resumes on next connection | 100 compute-hours per project per month; 1 GB per project | None: permanent plan; limits suspend compute, never delete data | Oluwafisayomi Aiyetogbon |
| 2026-10-25 | Interface (static) | Vercel, Hobby plan | No: static files served from the CDN | Free; personal and non-commercial use only; 100 GB data transfer and 1,000,000 CDN requests a month; over a limit, that feature pauses for 30 days | n/a | Oluwafisayomi Aiyetogbon |

Deploy rule: the host's auto-deploy is **off**. GitHub Actions calls the host's deploy hook only after contract lint and build-and-test pass on `main`.

| Date | Decision | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-25 | Deploy on Render (service) and Neon (database), both Frankfurt, instead of Qocent | The facilitators asked for Qocent; it was unreachable on 24 and 25 Oct and the Monday checkpoint needs a live service. Same Docker image, so moving later changes only the deploy step. Confirmed by the supervisor, Chukwuma, 2026-10-25. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Database on Neon rather than Supabase or Render Postgres | Render's free Postgres is deleted after 30 days; Supabase pauses a project after a week without activity (assessors may look later) and its direct connection is IPv6-only, which Render cannot reach. Cost: about half a second to wake Neon after 5 idle minutes. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Faster cold starts: Java 25 ahead-of-time cache recorded by a practice start during the image build, jar unpacked, C1 compiler only (`TieredStopAtLevel=1`) | The free service sleeps; waking time is what users notice. Cost: lower peak throughput from C1 only. Reverse if the load test shows the service CPU-bound. | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Keep-warm ping (`keep-warm.yml`), off by default, switched on by the `KEEP_WARM` variable on demo days only | Free hours are shared across the workspace; keeping two services awake around the clock would exhaust them | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |
| 2026-10-25 | Migrations run when the service starts (Liquibase on startup) | Simplest reliable path for the first deploys; moving them into the pipeline before the deploy is planned with the rollback check (Fri 30 Oct) | Oluwafisayomi Aiyetogbon, Okechukwu Daniel |

## Contract changes

| Date | Change | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-24 | Contract tooling moved to `contract/package.json`; service moved from `api/` to the repository root | Repository now holds only the service and the contract | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-23 | v0.1.0 created: `POST /sessions`, `GET /organisations`, `GET` and `POST /units`, `GET /availability`, `GET` and `POST /requests`, `GET /requests/{id}`, `POST /requests/{id}/approval`, `POST /requests/{id}/declination`, `GET /actuator/health`, shared `Error` shape | Monday journey 1 to 3 | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-23 | Sign-in is `POST /sessions`, not `/auth/login`; decline is `POST /requests/{id}/declination`, not `/decline` | Brief: resources are named as things, not actions | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-23 | `POST /requests` requires an `Idempotency-Key` header; replay returns 200 and `Idempotent-Replayed: true` | Brief: at least one write safe to submit twice, with the client's part documented | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-23 | Approval reserves units; creating a request reserves nothing. Approval is all or nothing. | Brief journey 3: "The blood bank approves, which reserves specific units". Partial approval is a question for the client. | Oluwafisayomi Aiyetogbon, Ufoma Onowho |
| 2026-10-23 | Non-visible requests return 404, not 403 | Do not reveal other hospitals' requests | Oluwafisayomi Aiyetogbon, Ufoma Onowho |

## Open questions for the client

| # | Question | What we do until answered |
| --- | --- | --- |
| 1 | Can a bank approve part of a request when fewer units are free than asked for (2 of 3)? | All or nothing (decision of 2026-10-25) |
| 2 | How long should a hold last, and can an emergency take a unit held for a routine request? | Decided in ADR-002; client asked to confirm the lengths |
| 3 | When a reserved unit expires, do banks also need to record that the bag was discarded? | Ends as EXPIRED; DISCARDED is a separate staff action |
| 4 | Do donors need an account to book a donation slot? | Donor account (demo `donor@bloodlink.demo`) |
| 5 | Is final submission on 3 or 5 November? | Planning for 3 November |

Answered: separate interface and service repositories are acceptable (Chukwuma, 2026-10-25).
