--liquibase formatted sql

-- Index analysis (docs/data-model.md, 30 October), two fixes for "a bank's requests, newest first" (GET /requests).
--
-- 1. blood_request_status asked reservation_status for each hold's effective status. That is a CASE PostgreSQL cannot
--    see through, so it worked out the status of every reservation of every unit before answering: 50,000 units read
--    to show 20 requests, growing with the whole history. The same two questions are now asked of the tables directly,
--    one request at a time through reservation_request_idx (scalar subqueries, which PostgreSQL never turns into a scan
--    of everything):
--      * UNIT_EXPIRED: an ACTIVE hold on this request whose unit is past its expiry date.
--      * LAPSED: no hold on this request is ISSUED, or ACTIVE with time left. Equivalent to the old "no hold whose
--        effective status is ACTIVE or ISSUED": the first branch already ruled out an ACTIVE hold on an expired unit,
--        and an effective ISSUED is a stored ISSUED. Same columns and results; ViewEquivalenceIT compares every row.
-- 2. blood_request_bank_idx led with (bank, state), but the list filters on the computed state, never the stored one, so
--    that column never helped and every request of the bank was read and sorted to show 20. (bank, newest first) lets
--    PostgreSQL read just the first page.

--changeset team4:009-blood-request-status-indexable
CREATE OR REPLACE VIEW blood_request_status AS
SELECT br.*,
       CASE
           WHEN br.state = 'APPROVED' AND (SELECT count(*) FROM reservation r
                 WHERE r.request_id = br.id AND r.status = 'ACTIVE'
                   AND (SELECT u.expiry_date FROM unit u WHERE u.id = r.unit_id) < current_date) > 0 THEN 'UNIT_EXPIRED'
           WHEN br.state = 'APPROVED' AND (SELECT count(*) FROM reservation r
                 WHERE r.request_id = br.id
                   AND (r.status = 'ISSUED' OR (r.status = 'ACTIVE' AND r.hold_expires_at > now()))) = 0 THEN 'LAPSED'
           ELSE br.state
       END AS effective_state
  FROM blood_request br;
--rollback CREATE OR REPLACE VIEW blood_request_status AS SELECT br.*, CASE WHEN br.state = 'APPROVED' AND EXISTS (SELECT 1 FROM reservation_status rs WHERE rs.request_id = br.id AND rs.effective_status = 'UNIT_EXPIRED') THEN 'UNIT_EXPIRED' WHEN br.state = 'APPROVED' AND NOT EXISTS (SELECT 1 FROM reservation_status rs WHERE rs.request_id = br.id AND rs.effective_status IN ('ACTIVE', 'ISSUED')) THEN 'LAPSED' ELSE br.state END AS effective_state FROM blood_request br;

--changeset team4:009-blood-request-bank-newest-idx
CREATE INDEX blood_request_bank_newest_idx ON blood_request (bank_id, created_at DESC, id);
DROP INDEX blood_request_bank_idx;
--rollback CREATE INDEX blood_request_bank_idx ON blood_request (bank_id, state, created_at DESC);
--rollback DROP INDEX blood_request_bank_newest_idx;
