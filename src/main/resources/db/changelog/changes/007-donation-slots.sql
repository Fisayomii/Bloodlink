--liquibase formatted sql

-- Donation slots and bookings (contract v0.2: /donation-slots, /bookings).
--
-- The rule (brief): "A donation slot cannot be booked beyond its capacity, including when two donors book the last place
-- in the same second." It is enforced here, so it holds whatever the service does:
--
-- * Every active booking (BOOKED or ATTENDED) holds a place number from 1 to the slot's capacity. The capacity is copied
--   onto the booking and pinned to the slot by a foreign key, so the CHECK can compare against it.
-- * A partial unique index allows each place of a slot to be held by one active booking at a time.
--   So a slot with 4 places can never hold a 5th active booking: there is no 5th place number to take.
--
-- The same shape as the reservation rule (ADR-001): a partial unique index decides, not a count read in application code.

--changeset team4:007-donation-slot
CREATE TABLE donation_slot (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_id          uuid        NOT NULL,
    bank_type        text        NOT NULL DEFAULT 'BLOOD_BANK' CHECK (bank_type = 'BLOOD_BANK'),
    starts_at        timestamptz NOT NULL,
    ends_at          timestamptz NOT NULL,
    capacity         integer     NOT NULL CHECK (capacity BETWEEN 1 AND 50),
    created_by       uuid        NOT NULL REFERENCES app_user (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- Safe repeat submission, as for blood_request: the client's key and a hash of the body it was first used with.
    idempotency_key  uuid        NOT NULL,
    payload_hash     text        NOT NULL,
    CONSTRAINT donation_slot_bank_fk FOREIGN KEY (bank_id, bank_type) REFERENCES organisation (id, type),
    CONSTRAINT donation_slot_times_check CHECK (ends_at > starts_at AND ends_at <= starts_at + interval '8 hours'),
    CONSTRAINT donation_slot_idempotency_key UNIQUE (created_by, idempotency_key),
    -- Lets a booking carry the slot's capacity through a foreign key (see booking_slot_fk).
    CONSTRAINT donation_slot_id_capacity_key UNIQUE (id, capacity)
);
-- Serves "slots in the next 14 days", optionally at one bank.
CREATE INDEX donation_slot_starts_idx ON donation_slot (starts_at, bank_id);
--rollback DROP TABLE donation_slot;

--changeset team4:007-booking
CREATE TABLE booking (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slot_id            uuid        NOT NULL,
    -- Always the slot's capacity: the foreign key below refuses any other value.
    slot_capacity      integer     NOT NULL,
    place_no           integer     NOT NULL,
    donor_id           uuid        NOT NULL REFERENCES app_user (id),
    status             text        NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED', 'CANCELLED', 'ATTENDED', 'NO_SHOW')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    status_changed_at  timestamptz,
    status_changed_by  uuid        REFERENCES app_user (id),
    CONSTRAINT booking_slot_fk FOREIGN KEY (slot_id, slot_capacity) REFERENCES donation_slot (id, capacity),
    -- A place number exists only up to the slot's capacity.
    CONSTRAINT booking_place_within_capacity_check CHECK (place_no BETWEEN 1 AND slot_capacity),
    -- A booking still BOOKED has not changed; every other status records when it did.
    CONSTRAINT booking_status_changed_check CHECK ((status = 'BOOKED') = (status_changed_at IS NULL))
);
-- THE capacity rule: each place of a slot is held by at most one active booking. With places 1..capacity, a slot can
-- never have more active bookings than its capacity, however many donors book at the same moment.
CREATE UNIQUE INDEX booking_one_active_per_place_idx ON booking (slot_id, place_no) WHERE status IN ('BOOKED', 'ATTENDED');
-- One active booking per donor per slot, so a double tap records one booking.
CREATE UNIQUE INDEX booking_one_active_per_donor_idx ON booking (slot_id, donor_id) WHERE status IN ('BOOKED', 'ATTENDED');
-- Serves "my bookings" for a donor.
CREATE INDEX booking_donor_idx ON booking (donor_id, created_at DESC);
--rollback DROP TABLE booking;
