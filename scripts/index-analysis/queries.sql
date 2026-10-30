-- Index analysis, step 2: the five most important queries, exactly as the service sends them, with EXPLAIN ANALYZE.
--   psql -f scripts/index-analysis/queries.sql   (after generate.sql)
-- "Rows examined" is read from the plan: rows read from each index or table node (plus rows removed by a filter).
\set ON_ERROR_STOP on
SET TIME ZONE 'Africa/Lagos';
-- The busiest bank and a hospital, so each query runs against the largest share of the data.
SELECT bank_id AS bank FROM unit GROUP BY bank_id ORDER BY count(*) DESC LIMIT 1 \gset

\echo '=== Q1 availability search: GET /availability?bloodGroup=O&rhesus=NEGATIVE&component=RED_CELLS (AvailabilityController)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT o.id, o.name, o.type, o.area, u.blood_group, u.rhesus, u.component,
       count(*) AS available_units, min(u.expiry_date) AS earliest_expiry
  FROM unit u
  JOIN organisation o ON o.id = u.bank_id
 WHERE u.expiry_date >= current_date
   AND (u.state = 'AVAILABLE'
        OR (u.state = 'RESERVED' AND NOT EXISTS (
               SELECT 1 FROM reservation r
                WHERE r.unit_id = u.id AND r.status = 'ACTIVE' AND r.hold_expires_at > now())))
   AND (CAST('O' AS text) IS NULL OR u.blood_group = 'O')
   AND (CAST('NEGATIVE' AS text) IS NULL OR u.rhesus = 'NEGATIVE')
   AND (CAST('RED_CELLS' AS text) IS NULL OR u.component = 'RED_CELLS')
   AND (CAST(NULL AS uuid) IS NULL OR u.bank_id = NULL)
   AND (CAST(NULL AS text) IS NULL OR lower(o.area) = lower(NULL))
 GROUP BY o.id, o.name, o.type, o.area, u.blood_group, u.rhesus, u.component
 ORDER BY u.blood_group, u.rhesus, u.component, o.name;

\echo '=== Q2 approval picks units: POST /requests/{id}/approval (RequestService.approve), 2 units, locked'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT u.id
  FROM unit u
 WHERE u.bank_id = :'bank' AND u.blood_group = 'A' AND u.rhesus = 'POSITIVE' AND u.component = 'PLATELETS'
   AND u.expiry_date >= current_date
   AND (u.state = 'AVAILABLE'
        OR (u.state = 'RESERVED' AND NOT EXISTS (
               SELECT 1 FROM reservation r
                WHERE r.unit_id = u.id AND r.status = 'ACTIVE' AND r.hold_expires_at > now())))
 ORDER BY u.expiry_date, u.id
 LIMIT 2
   FOR UPDATE OF u SKIP LOCKED;
ROLLBACK;

\echo '=== Q3 a bank s requests, newest first: GET /requests as bank staff (RequestReader.page), first page of 20'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT br.id, br.reference, br.effective_state, br.created_at, h.name, b.name
  FROM blood_request_status br
  JOIN organisation h ON h.id = br.hospital_id
  JOIN organisation b ON b.id = br.bank_id
 WHERE (CAST(NULL AS uuid) IS NULL OR br.hospital_id = NULL)
   AND (CAST(:'bank' AS uuid) IS NULL OR br.bank_id = :'bank')
   AND (CAST(NULL AS text) IS NULL OR br.effective_state = NULL)
 ORDER BY br.created_at DESC, br.id LIMIT 20 OFFSET 0;

\echo '=== Q4 holds ending in the next 60 minutes: GET /dashboard (DashboardService.holdsEndingSoon), every bank'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT r.request_id, br.reference, r.hold_expires_at, h.name, u.reference, u.expiry_date
  FROM reservation r
  JOIN unit u ON u.id = r.unit_id
  JOIN blood_request br ON br.id = r.request_id
  JOIN organisation h ON h.id = br.hospital_id
 WHERE r.status = 'ACTIVE' AND r.hold_expires_at > now()
   AND r.hold_expires_at <= now() + make_interval(mins => 60)
   AND u.expiry_date >= current_date
   AND (CAST(NULL AS uuid) IS NULL OR br.bank_id = NULL)
 ORDER BY r.hold_expires_at, u.reference
 LIMIT 100;

\echo '=== Q5 donation slots in the next 14 days with places left: GET /donation-slots (DonationService.list), every bank'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT s.id, s.starts_at, s.capacity, o.name,
       (SELECT count(*) FROM booking x WHERE x.slot_id = s.id AND x.status IN ('BOOKED', 'ATTENDED')) AS booked
  FROM donation_slot s
  JOIN organisation o ON o.id = s.bank_id
 WHERE s.starts_at >= current_date::timestamptz AND s.starts_at < (current_date + 15)::timestamptz
   AND (CAST(NULL AS uuid) IS NULL OR s.bank_id = NULL)
 ORDER BY s.starts_at, o.name;

\echo '=== Q1 again as the driver sends it after 5 runs: a prepared statement. Generic plan, then the plan the service forces'
PREPARE availability(text, text, text, uuid, text) AS
SELECT o.id, u.blood_group, u.rhesus, u.component, count(*) AS available_units, min(u.expiry_date) AS earliest_expiry
  FROM unit u
  JOIN organisation o ON o.id = u.bank_id
 WHERE u.expiry_date >= current_date
   AND (u.state = 'AVAILABLE'
        OR (u.state = 'RESERVED' AND NOT EXISTS (
               SELECT 1 FROM reservation r
                WHERE r.unit_id = u.id AND r.status = 'ACTIVE' AND r.hold_expires_at > now())))
   AND ($1 IS NULL OR u.blood_group = $1)
   AND ($2 IS NULL OR u.rhesus = $2)
   AND ($3 IS NULL OR u.component = $3)
   AND ($4 IS NULL OR u.bank_id = $4)
   AND ($5 IS NULL OR lower(o.area) = lower($5))
 GROUP BY o.id, u.blood_group, u.rhesus, u.component;
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE availability('O', 'NEGATIVE', 'RED_CELLS', NULL, NULL);
SET plan_cache_mode = force_custom_plan;   -- what application.yml sets on every connection
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE availability('O', 'NEGATIVE', 'RED_CELLS', NULL, NULL);
DEALLOCATE availability;
