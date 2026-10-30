-- Index analysis, step 3: Q3 and Q4 as they were before migration 009 and the dashboard change, for comparison.
-- Everything runs inside a transaction that is rolled back, so the database is left as it was.
--   psql -f scripts/index-analysis/before.sql   (after generate.sql)
\set ON_ERROR_STOP on
SET TIME ZONE 'Africa/Lagos';
SELECT bank_id AS bank FROM unit GROUP BY bank_id ORDER BY count(*) DESC LIMIT 1 \gset
BEGIN;
CREATE OR REPLACE VIEW blood_request_status AS SELECT br.*, CASE WHEN br.state = 'APPROVED' AND EXISTS (SELECT 1 FROM reservation_status rs WHERE rs.request_id = br.id AND rs.effective_status = 'UNIT_EXPIRED') THEN 'UNIT_EXPIRED' WHEN br.state = 'APPROVED' AND NOT EXISTS (SELECT 1 FROM reservation_status rs WHERE rs.request_id = br.id AND rs.effective_status IN ('ACTIVE', 'ISSUED')) THEN 'LAPSED' ELSE br.state END AS effective_state FROM blood_request br;
CREATE INDEX blood_request_bank_idx ON blood_request (bank_id, state, created_at DESC);
DROP INDEX blood_request_bank_newest_idx;

\echo '=== Q3 BEFORE: the old view, through reservation_status, with (bank, state, created_at)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT br.id, br.reference, br.effective_state, br.created_at, h.name, b.name
  FROM blood_request_status br
  JOIN organisation h ON h.id = br.hospital_id
  JOIN organisation b ON b.id = br.bank_id
 WHERE (CAST(NULL AS uuid) IS NULL OR br.hospital_id = NULL)
   AND (CAST(:'bank' AS uuid) IS NULL OR br.bank_id = :'bank')
   AND (CAST(NULL AS text) IS NULL OR br.effective_state = NULL)
 ORDER BY br.created_at DESC, br.id LIMIT 20 OFFSET 0;

\echo '=== Q4 BEFORE: holds ending soon read through reservation_status'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT rs.request_id, br.reference, rs.hold_expires_at, h.name, u.reference, u.expiry_date
  FROM reservation_status rs
  JOIN unit u ON u.id = rs.unit_id
  JOIN blood_request br ON br.id = rs.request_id
  JOIN organisation h ON h.id = br.hospital_id
 WHERE rs.effective_status = 'ACTIVE'
   AND rs.hold_expires_at <= now() + make_interval(mins => 60)
   AND (CAST(NULL AS uuid) IS NULL OR br.bank_id = NULL)
 ORDER BY rs.hold_expires_at, u.reference
 LIMIT 100;
ROLLBACK;
