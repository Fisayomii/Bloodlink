--liquibase formatted sql

-- Contract v0.2: issuing units, reversing an issue, a hospital cancelling, and releasing a routine hold for an emergency.
--
-- * A reversed issue marks its reservations REVERSED (both the issue and the reversal stay in the history).
-- * A request can go back to PENDING (a reversed issue, a released hold) and be approved again, possibly with the same
--   unit. The old rule "a request reserves a given unit at most once, ever" would refuse that, so it is replaced by what
--   actually matters, already enforced by reservation_one_active_hold_idx: a unit has at most one ACTIVE hold.

--changeset team4:006-reservation-status-reversed
ALTER TABLE reservation DROP CONSTRAINT reservation_status_check;
ALTER TABLE reservation ADD CONSTRAINT reservation_status_check
    CHECK (status IN ('ACTIVE', 'LAPSED', 'ISSUED', 'REVERSED', 'UNIT_EXPIRED', 'RELEASED'));
--rollback ALTER TABLE reservation DROP CONSTRAINT reservation_status_check;
--rollback ALTER TABLE reservation ADD CONSTRAINT reservation_status_check CHECK (status IN ('ACTIVE', 'LAPSED', 'ISSUED', 'UNIT_EXPIRED', 'RELEASED'));

--changeset team4:006-reservation-request-unit-repeatable
ALTER TABLE reservation DROP CONSTRAINT reservation_request_unit_key;
--rollback ALTER TABLE reservation ADD CONSTRAINT reservation_request_unit_key UNIQUE (request_id, unit_id);
