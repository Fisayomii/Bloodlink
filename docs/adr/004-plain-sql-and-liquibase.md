# ADR-004: Plain SQL with JdbcClient, and Liquibase for migrations

- **Status:** Accepted, 2026-10-25. Built.
- **Decided by:** Oluwafisayomi Aiyetogbon, Okechukwu Daniel.

## Context

The rules depend on exact SQL: conditional updates, `FOR UPDATE SKIP LOCKED`, partial unique indexes, views and a
trigger. The brief also asks for versioned migrations that run in the pipeline and can be reversed, and for every
engineer to be able to explain the code.

## Decision

- **Data access with plain SQL** through Spring's `JdbcClient`, mapped to Java records by hand. No JPA or Hibernate.
- **Migrations with Liquibase**, written as SQL changesets, one object per changeset, each with a `--rollback`. Rollback
  is tested by running update, then rollback, then update again.

## Alternatives rejected

| Option | Why not |
| --- | --- |
| JPA / Hibernate | It hides the SQL that protects the rules. Locking and conditional updates are possible, but harder to read and review, and lazy loading adds hidden queries |
| jOOQ | Type-safe SQL, but code generation and another tool to learn in two weeks |
| Flyway | Simple and popular, but undo migrations are a paid feature. The brief requires migrations that can be reversed |

## Cost accepted

- More mapping code written by hand (row to record).
- Liquibase's changelog format is more to learn than Flyway's numbered files.

## What would make us reverse it

Mapping code starts to outweigh the rules (many near-identical row mappers). Then consider jOOQ for those queries,
keeping hand-written SQL for the protected ones.
