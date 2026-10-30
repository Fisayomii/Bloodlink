--liquibase formatted sql

-- Demonstration donation slots (context "demo"). Slots are dated, so a fixed list would be in the past within two weeks.
-- This changeset runs on every deploy instead (runAlways) and tops up: each demo bank gets a 09:00 slot (4 places) and a
-- 14:00 slot (6 places, 2 hours) on each of the next 14 days, Lagos time, wherever one is missing. Rows are marked with
-- payload_hash 'seed'. SIMULATED: the demo bookings are made by the demo donors, not by real people.

--changeset team4:008-demo-donor context:demo
-- A second donor, so two donors can be shown booking the same slot. Password Demo-Password-1, like every demo account.
INSERT INTO app_user (id, email, display_name, password_hash, role, organisation_id) VALUES
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000009', 'donor2@bloodlink.demo', 'Tunde Bakare',
     '$2a$10$QHHCWP8tvihWUqIGkO5DT.aYIs0WJ2/BMM.LVet824qkUHxaBiw4K', 'DONOR', NULL);
--rollback DELETE FROM app_user WHERE id = '9a8b7c6d-5e4f-4a3b-8c2d-000000000009';

--changeset team4:008-demo-slots context:demo runAlways:true runOnChange:true
INSERT INTO donation_slot (bank_id, starts_at, ends_at, capacity, created_by, idempotency_key, payload_hash)
SELECT b.id, t.starts_at, t.starts_at + interval '2 hours', h.capacity, staff.id, gen_random_uuid(), 'seed'
  FROM organisation b
  CROSS JOIN LATERAL (SELECT u.id FROM app_user u
                       WHERE u.organisation_id = b.id AND u.role = 'BANK_STAFF' ORDER BY u.email LIMIT 1) staff
  CROSS JOIN generate_series(0, 14) AS d(day)
  CROSS JOIN (VALUES (9, 4), (14, 6)) AS h(hour, capacity)
  CROSS JOIN LATERAL (SELECT (((now() AT TIME ZONE 'Africa/Lagos')::date + d.day)::timestamp
                              + make_interval(hours => h.hour)) AT TIME ZONE 'Africa/Lagos' AS starts_at) t
 WHERE b.id IN ('0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b', '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6c',
                '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6d')
   AND t.starts_at > now()
   AND NOT EXISTS (SELECT 1 FROM donation_slot s WHERE s.bank_id = b.id AND s.starts_at = t.starts_at);

-- The first demo donor has a place in the next Lagos Central 09:00 slot, unless they already have an upcoming booking.
INSERT INTO booking (slot_id, slot_capacity, place_no, donor_id)
SELECT s.id, s.capacity, 1, '9a8b7c6d-5e4f-4a3b-8c2d-000000000008'
  FROM donation_slot s
 WHERE s.bank_id = '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b' AND s.payload_hash = 'seed' AND s.starts_at > now()
   AND extract(hour FROM s.starts_at AT TIME ZONE 'Africa/Lagos') = 9
   AND NOT EXISTS (SELECT 1 FROM booking b JOIN donation_slot x ON x.id = b.slot_id
                    WHERE b.donor_id = '9a8b7c6d-5e4f-4a3b-8c2d-000000000008' AND b.status = 'BOOKED' AND x.starts_at > now())
   AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.slot_id = s.id AND b.place_no = 1 AND b.status IN ('BOOKED', 'ATTENDED'))
 ORDER BY s.starts_at
 LIMIT 1;
--rollback DELETE FROM booking WHERE slot_id IN (SELECT id FROM donation_slot WHERE payload_hash = 'seed');
--rollback DELETE FROM donation_slot WHERE payload_hash = 'seed';
