--liquibase formatted sql

-- One definition, in the database, of what a unit's state really is right now.
--
-- The stored state can lag behind the clock: a unit stays 'RESERVED' after its hold lapses until something releases it,
-- and stays 'AVAILABLE' after its expiry date until the expiry job marks it. The brief requires that a lapsed hold is
-- never shown as held, even if the background job has not run for an hour. So every read uses effective_state:
--
--   * AVAILABLE or RESERVED, but past its expiry date            -> EXPIRED
--   * RESERVED, but no ACTIVE reservation whose hold is still on  -> AVAILABLE
--   * otherwise                                                   -> the stored state
--
-- "Today" is the date in Lagos: every connection runs SET TIME ZONE 'Africa/Lagos' (see application.yml),
-- so current_date rolls over at midnight local time, not at 01:00.
-- Reading never changes stored data; write paths and the jobs apply these changes for real.

--changeset team4:003-unit-status-view
CREATE VIEW unit_status AS
SELECT u.*,
       CASE
           WHEN u.state IN ('AVAILABLE', 'RESERVED') AND u.expiry_date < current_date THEN 'EXPIRED'
           WHEN u.state = 'RESERVED' AND NOT EXISTS (
               SELECT 1 FROM reservation r
                WHERE r.unit_id = u.id AND r.status = 'ACTIVE' AND r.hold_expires_at > now()) THEN 'AVAILABLE'
           ELSE u.state
       END AS effective_state
  FROM unit u;
--rollback DROP VIEW unit_status;

--changeset team4:003-unit-reserved-idx
-- Availability counts AVAILABLE units (served by unit_available_idx) plus RESERVED units whose hold has lapsed.
-- RESERVED units are few, so a small partial index lets that second half avoid scanning the whole table.
CREATE INDEX unit_reserved_idx ON unit (blood_group, rhesus, component, bank_id) WHERE state = 'RESERVED';
--rollback DROP INDEX unit_reserved_idx;
