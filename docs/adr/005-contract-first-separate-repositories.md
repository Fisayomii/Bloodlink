# ADR-005: Contract first, in the service repository, with a separate interface repository

- **Status:** Accepted, 2026-10-24. Confirmed by the supervisor (Chukwuma) on 2026-10-25.
- **Decided by:** Oluwafisayomi Aiyetogbon, Ufoma Onowho.

## Context

The interface engineer must never wait for the service. The brief asks for one OpenAPI document, a mock server from it,
contract tests in the pipeline, and a dated record of every change agreed by two engineers. Each tier is built and
deployed by a different person, with a different toolchain (Maven and Java; Node, React and Vercel).

## Decision

- `contract/openapi.yaml` is written first and lives in the **service** repository. It is linted by Spectral on every
  pull request. `npm run mock` serves it with Prism, so the interface was built against the mock from day 3.
- The interface lives in its **own repository** (`bloodlink-web`). It copies the contract with `npm run contract:sync`,
  and its CI fails when its copy differs from this repository's `main`.
- The live service serves the committed contract in Swagger UI at `/`, not a document generated from the code.
- Every contract change is a line in `docs/decisions.md` with the date, the reason and two names.

## Alternatives rejected

| Option | Why not |
| --- | --- |
| One repository with `api/` and `web/` | Two toolchains, two deploy targets and two owners in one pipeline. Every interface change would wait for the Java build |
| Generate the contract from the Java code (springdoc) | The document would describe what the code does, not what was agreed, and would change silently with the code |

## Cost accepted

- The contract is copied, so it can drift. The drift check in `bloodlink-web` CI is the mitigation.
- Commits are split across two repositories, so "who did what" needs both histories.

## What would make us reverse it

The drift check fails repeatedly, or a contract change ships to one tier without the other. Then move both tiers into
one repository with one pipeline.
