-- Index analysis, step 1: fill a migrated BloodLink database with realistic volumes.
--   psql -v scale=1 -f scripts/index-analysis/generate.sql     (scale 1: 50,000 units; scale 10: 500,000 units)
-- Run on a THROWAWAY database migrated with the demo context, never on the live one. Every row is marked GEN- or 'gen'.
\set ON_ERROR_STOP on
SET TIME ZONE 'Africa/Lagos';
SELECT setseed(0.26);

-- 27 more banks and 27 more hospitals, 30 of each with the demo ones, each bank with a staff member.
INSERT INTO organisation (name, type, area)
SELECT 'GEN ' || t || ' ' || g, t, 'Area ' || (g % 12)
  FROM generate_series(1, 27) g, (VALUES ('BLOOD_BANK'), ('HOSPITAL')) AS v(t);
INSERT INTO app_user (email, display_name, password_hash, role, organisation_id)
SELECT 'gen-' || o.id || '@gen.demo', 'GEN staff', 'x', CASE o.type WHEN 'BLOOD_BANK' THEN 'BANK_STAFF' ELSE 'HOSPITAL_STAFF' END, o.id
  FROM organisation o WHERE o.name LIKE 'GEN %';
INSERT INTO app_user (email, display_name, password_hash, role)
SELECT 'gen-donor-' || g || '@gen.demo', 'GEN donor ' || g, 'x', 'DONOR' FROM generate_series(1, 2000) g;

CREATE TEMP TABLE banks AS
SELECT o.id, row_number() OVER (ORDER BY o.id) AS n,
       (SELECT u.id FROM app_user u WHERE u.organisation_id = o.id AND u.role = 'BANK_STAFF' LIMIT 1) AS staff
  FROM organisation o WHERE o.type = 'BLOOD_BANK';
CREATE TEMP TABLE hospitals AS
SELECT o.id, row_number() OVER (ORDER BY o.id) AS n,
       (SELECT u.id FROM app_user u WHERE u.organisation_id = o.id AND u.role = 'HOSPITAL_STAFF' LIMIT 1) AS staff
  FROM organisation o WHERE o.type = 'HOSPITAL';

-- Units: 50,000 x scale. About 25% available, 2% reserved, 55% issued, 15% expired, 3% discarded, as a bank's history
-- piles up issued and expired units over time. Expiry dates spread from 400 days ago to 45 days ahead.
INSERT INTO unit (bank_id, reference, blood_group, rhesus, component, collection_date, expiry_date, state, recorded_by, recorded_at)
SELECT b.id, 'GEN-' || g,
       (ARRAY['A','B','AB','O'])[1 + (g % 4)],
       CASE WHEN g % 7 = 0 THEN 'NEGATIVE' ELSE 'POSITIVE' END,
       (ARRAY['WHOLE_BLOOD','RED_CELLS','PLATELETS','PLASMA','CRYOPRECIPITATE'])[1 + (g % 5)],
       e - 35, e,
       CASE WHEN e >= current_date AND r < 0.92 THEN (CASE WHEN r < 0.08 THEN 'RESERVED' ELSE 'AVAILABLE' END)
            WHEN e >= current_date THEN 'ISSUED'
            WHEN r < 0.70 THEN 'ISSUED' WHEN r < 0.96 THEN 'EXPIRED' ELSE 'DISCARDED' END,
       b.staff, (e - 35)::timestamptz
  FROM generate_series(1, 50000 * :scale) g
  -- g * 0 ties the random values to each row; without it the subquery runs once and every row gets the same values.
  CROSS JOIN LATERAL (SELECT random() + g * 0 AS r, current_date + (45 - floor(random() * 445) + g * 0)::int AS e) x
  JOIN banks b ON b.n = 1 + (g % (SELECT count(*) FROM banks));

-- Requests: 10,000 x scale over the past year, mostly finished.
INSERT INTO blood_request (hospital_id, bank_id, blood_group, rhesus, component, quantity, urgency, needed_by, state,
                           decline_reason, created_by, created_at, idempotency_key, payload_hash)
SELECT h.id, b.id, (ARRAY['A','B','AB','O'])[1 + (g % 4)], CASE WHEN g % 7 = 0 THEN 'NEGATIVE' ELSE 'POSITIVE' END,
       (ARRAY['WHOLE_BLOOD','RED_CELLS','PLATELETS','PLASMA','CRYOPRECIPITATE'])[1 + (g % 5)], 1 + (g % 3),
       (ARRAY['ROUTINE','URGENT','EMERGENCY'])[1 + (g % 3)], c + interval '6 hours',
       s, CASE WHEN s = 'DECLINED' THEN 'No stock (generated)' END, h.staff, c, gen_random_uuid(), 'gen'
  FROM generate_series(1, 10000 * :scale) g
  CROSS JOIN LATERAL (SELECT now() - (random() + g * 0) * interval '365 days' AS c, random() + g * 0 AS r) x
  CROSS JOIN LATERAL (SELECT CASE WHEN r < 0.70 THEN 'FULFILLED' WHEN r < 0.80 THEN 'DECLINED' WHEN r < 0.88 THEN 'CANCELLED'
                                  WHEN r < 0.95 THEN 'LAPSED' WHEN r < 0.98 THEN 'PENDING' ELSE 'APPROVED' END AS s) st
  JOIN hospitals h ON h.n = 1 + (g % (SELECT count(*) FROM hospitals))
  JOIN banks b ON b.n = 1 + ((g * 7) % (SELECT count(*) FROM banks));

-- Holds: each RESERVED generated unit held by an APPROVED generated request at its bank, ending within the next 4 hours.
INSERT INTO reservation (request_id, unit_id, status, hold_expires_at, created_at)
SELECT DISTINCT ON (u.id) br.id, u.id, 'ACTIVE', now() + random() * interval '4 hours', now() - interval '10 minutes'
  FROM unit u JOIN blood_request br ON br.bank_id = u.bank_id AND br.state = 'APPROVED' AND br.payload_hash = 'gen'
 WHERE u.state = 'RESERVED' AND u.reference LIKE 'GEN-%'
 ORDER BY u.id, br.id;
-- Past holds on issued units (ISSUED reservations), about one per issued unit, for realistic reservation volume.
INSERT INTO reservation (request_id, unit_id, status, hold_expires_at, created_at, ended_at)
SELECT br.id, u.id, 'ISSUED', br.created_at + interval '4 hours', br.created_at, br.created_at + interval '1 hour'
  FROM (SELECT id, bank_id, row_number() OVER (PARTITION BY bank_id ORDER BY id) AS k FROM unit
         WHERE state = 'ISSUED' AND reference LIKE 'GEN-%') u
  JOIN (SELECT id, bank_id, created_at, row_number() OVER (PARTITION BY bank_id ORDER BY id) AS k FROM blood_request
         WHERE state = 'FULFILLED' AND payload_hash = 'gen') br ON br.bank_id = u.bank_id AND br.k = u.k;

-- History: one RECORDED entry per unit, CREATED and a decision per request.
INSERT INTO activity (entity_type, entity_id, event, actor_id, note, at)
SELECT 'UNIT', id, 'RECORDED', recorded_by, NULL, recorded_at FROM unit WHERE reference LIKE 'GEN-%';
INSERT INTO activity (entity_type, entity_id, event, actor_id, note, at)
SELECT 'REQUEST', id, e.event, created_by, NULL, created_at + e.after
  FROM blood_request, (VALUES ('CREATED', interval '0'), ('DECIDED', interval '20 minutes')) AS e(event, after)
 WHERE payload_hash = 'gen';

-- Donation slots: two a day at every bank for 365 x scale days (a year of history per scale step), plus 14 days ahead.
INSERT INTO donation_slot (bank_id, starts_at, ends_at, capacity, created_by, idempotency_key, payload_hash)
SELECT b.id, t, t + interval '2 hours', 6, b.staff, gen_random_uuid(), 'gen'
  FROM banks b
  CROSS JOIN generate_series(current_date - (365 * :scale), current_date + 14, interval '1 day') AS d(day)
  CROSS JOIN (VALUES (9), (14)) AS h(hour)
  CROSS JOIN LATERAL (SELECT (d.day::date + make_interval(hours => h.hour))::timestamptz AS t) x;
-- Bookings: about 4 of 6 places taken per slot.
INSERT INTO booking (slot_id, slot_capacity, place_no, donor_id, status, created_at, status_changed_at)
SELECT s.id, s.capacity, p, d.id,
       CASE WHEN s.starts_at > now() THEN 'BOOKED' WHEN random() < 0.85 THEN 'ATTENDED' ELSE 'NO_SHOW' END,
       s.starts_at - interval '3 days',
       CASE WHEN s.starts_at > now() THEN NULL ELSE s.starts_at END
  FROM donation_slot s
  CROSS JOIN generate_series(1, 4) p
  JOIN LATERAL (SELECT id FROM app_user WHERE role = 'DONOR' AND email LIKE 'gen-donor-%'
                 ORDER BY email OFFSET ((abs(hashtext(s.id::text)) + p) % 2000) LIMIT 1) d ON true
 WHERE s.payload_hash = 'gen';

ANALYZE;
SELECT 'units' AS table_name, count(*) FROM unit UNION ALL SELECT 'blood_request', count(*) FROM blood_request
UNION ALL SELECT 'reservation', count(*) FROM reservation UNION ALL SELECT 'activity', count(*) FROM activity
UNION ALL SELECT 'donation_slot', count(*) FROM donation_slot UNION ALL SELECT 'booking', count(*) FROM booking;
