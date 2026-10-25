--liquibase formatted sql

-- What a reservation and a request really are right now, computed on read like unit_status (003).
-- Stored values can lag behind the clock until a write path or job applies the change; reads use these views, so a
-- lapsed hold is never shown as held and a request never looks open after its units have gone.
--
-- reservation_status.effective_status
--   * ACTIVE, but the unit is past its expiry date   -> UNIT_EXPIRED  (the unit can no longer be issued)
--   * ACTIVE, but the hold has passed                -> LAPSED
--   * otherwise                                      -> as stored
--
-- blood_request_status.effective_state
--   * APPROVED, and a reserved unit has expired      -> UNIT_EXPIRED  (the request shows what happened)
--   * APPROVED, and no hold is running or issued     -> LAPSED        (the units went back to stock)
--   * otherwise                                      -> as stored

--changeset team4:004-reservation-status-view
CREATE VIEW reservation_status AS
SELECT r.*,
       CASE
           WHEN r.status = 'ACTIVE' AND u.expiry_date < current_date THEN 'UNIT_EXPIRED'
           WHEN r.status = 'ACTIVE' AND r.hold_expires_at <= now() THEN 'LAPSED'
           ELSE r.status
       END AS effective_status
  FROM reservation r
  JOIN unit u ON u.id = r.unit_id;
--rollback DROP VIEW reservation_status;

--changeset team4:004-blood-request-status-view
CREATE VIEW blood_request_status AS
SELECT br.*,
       CASE
           WHEN br.state = 'APPROVED' AND EXISTS (
               SELECT 1 FROM reservation_status rs
                WHERE rs.request_id = br.id AND rs.effective_status = 'UNIT_EXPIRED') THEN 'UNIT_EXPIRED'
           WHEN br.state = 'APPROVED' AND NOT EXISTS (
               SELECT 1 FROM reservation_status rs
                WHERE rs.request_id = br.id AND rs.effective_status IN ('ACTIVE', 'ISSUED')) THEN 'LAPSED'
           ELSE br.state
       END AS effective_state
  FROM blood_request br;
--rollback DROP VIEW blood_request_status;
