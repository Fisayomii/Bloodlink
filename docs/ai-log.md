# AI log

The brief asks which AI tools we used, what we asked them to do, what we accepted and rejected, and at least one case
where the output was checked and found wrong. Every item below was checked by running it: a build, a test, a migration
against a real PostgreSQL, or a deploy.

## Tools

| Tool | Used by | Used for |
| --- | --- | --- |
| Claude (Anthropic), in claude.ai with code execution | Oluwafisayomi Aiyetogbon | Planning from the brief, drafting the OpenAPI contract, SQL migrations, Spring Boot code and tests, the seed-data generator, pipeline files, deployment steps, decision records and slides |

## What we asked for, and what we did with it

| Asked for | Accepted | Rejected or changed |
| --- | --- | --- |
| A plan for the two weeks from the brief | The order of work and the Monday scope | The first split assumed two working service laptops; re-planned for one builder (decisions file, 2026-10-24) |
| The OpenAPI contract for journeys 1–3 | Paths named as things (`/sessions`, `/declination`), one Error shape, `x-safety` on every operation | Nothing removed; every path was reviewed with Ufoma before the interface used it |
| Schema and migrations | Three-layer protection, CHECK constraints, append-only history trigger, status views | A constraint name and a function changeset (below) |
| Choice of data access and migration tools | Plain SQL with `JdbcClient`, Liquibase (ADR-004) | — |
| Hosting options | Render + Neon (ADR and `docs/deploy.md`) | Supabase, which we had proposed: projects pause after a week idle and its direct connection is IPv6-only |
| Commit messages | The messages | AI attribution lines in commits: removed, so the history shows who did the work |

## Output we checked and found wrong

**1. A migration that could not run.** The generated `001-core.sql` gave the table-level decline rule the name
`blood_request_decline_reason_check`. *How we checked:* ran the changelog with Liquibase against a local PostgreSQL.
*What was true:* PostgreSQL had already given that name to the column's own CHECK constraint, so the migration
failed with "constraint already exists". *What we did:* renamed it `blood_request_declined_has_reason_check`, and since
then every changeset is run, rolled back and run again before it is committed.

**2. A function body split in half.** The trigger function that makes history append-only was in a normal changeset.
*Checked:* the same Liquibase run. *True:* Liquibase splits statements on `;`, so the function body was cut at its
first semicolon. *Did:* moved it to its own changeset with `splitStatements:false`.

**3. A test that assumed the wrong sort order.** A generated integration test asserted that organisation names come
back "sorted", checked with Java's string ordering. *Checked:* CI on a real PostgreSQL. *True:* PostgreSQL sorts by its
collation rules, which order spaces, punctuation and case differently from Java. The service was right and the test was
wrong. *Did:* the test now checks the names that must be present, not Java's idea of order.

**4. A container that failed while building its startup cache.** The Dockerfile's practice start (Java 25 AOT cache) ran
as a non-root user. *Checked:* `docker build`. *True:* that user could not write `/app/app.aot`. *Did:* gave the user
ownership of `/app` (`chown app:app /app`).

**5. A setting that would have used up the free database.** A connection keep-alive interval was suggested to avoid
dropped connections. *Checked:* against Neon's documentation on compute suspension. *True:* a keep-alive every few
minutes keeps Neon's compute awake around the clock, spending the 100 free compute-hours a month. *Did:* removed it; a
short wake-up delay is the accepted cost (decisions file, hosting).

**6. Swagger "Try it out" calling the mock.** The contract lists the Prism mock first in `servers`. *Checked:* opened
the page and pressed Execute. *True:* Swagger picks the first server, so calls went to the mock, not the service.
*Did:* the page now selects "This service" on load.

**7. A pipeline file that did not parse.** The keep-warm workflow had a `curl` header with a colon inside a one-line
`run:`. *Checked:* GitHub Actions rejected the file. *True:* YAML read the colon as a key. *Did:* moved the command to a
block (`run: |`).
