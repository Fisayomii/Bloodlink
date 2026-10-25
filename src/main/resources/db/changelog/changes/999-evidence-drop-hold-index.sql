--liquibase formatted sql

-- EVIDENCE ONLY. Never merge. Removes the database backstop so ConcurrentApprovalIT can be seen to fail.
--changeset team4:999-evidence-drop-hold-index
DROP INDEX reservation_one_active_hold_idx;
--rollback CREATE UNIQUE INDEX reservation_one_active_hold_idx ON reservation (unit_id) WHERE status = 'ACTIVE';
