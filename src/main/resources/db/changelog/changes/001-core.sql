--liquibase formatted sql

-- BloodLink core schema. One changeset per object, each with its rollback.
-- Business rules live here as constraints wherever the database can enforce them, so they hold
-- even if the service has a bug or someone calls the database directly.
-- Allowed values match the enums in contract/openapi.yaml exactly.

--changeset team4:001-organisation
CREATE TABLE organisation (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 160),
    type        text        NOT NULL CHECK (type IN ('BLOOD_BANK', 'HOSPITAL')),
    area        text        NOT NULL CHECK (length(trim(area)) BETWEEN 1 AND 80),
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Lets other tables require "this organisation is a blood bank" (or a hospital) through a foreign key.
    CONSTRAINT organisation_id_type_key UNIQUE (id, type)
);
--rollback DROP TABLE organisation;

--changeset team4:001-app-user
CREATE TABLE app_user (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email            text        NOT NULL CHECK (email = lower(email) AND position('@' IN email) > 1),
    display_name     text        NOT NULL CHECK (length(trim(display_name)) BETWEEN 1 AND 120),
    password_hash    text        NOT NULL,
    role             text        NOT NULL CHECK (role IN ('BANK_STAFF', 'HOSPITAL_STAFF', 'DONOR', 'ADMIN')),
    organisation_id  uuid        REFERENCES organisation (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_email_key UNIQUE (email),
    -- Staff belong to an organisation; donors and the administrator do not.
    CONSTRAINT app_user_role_organisation_check CHECK (
        (role IN ('BANK_STAFF', 'HOSPITAL_STAFF')) = (organisation_id IS NOT NULL)
    )
);
--rollback DROP TABLE app_user;

--changeset team4:001-unit
CREATE TABLE unit (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_id          uuid        NOT NULL,
    -- Always 'BLOOD_BANK'; with the foreign key below, a unit can only belong to a blood bank.
    bank_type        text        NOT NULL DEFAULT 'BLOOD_BANK' CHECK (bank_type = 'BLOOD_BANK'),
    reference        text        NOT NULL CHECK (reference ~ '^[A-Za-z0-9-]{1,40}$'),
    blood_group      text        NOT NULL CHECK (blood_group IN ('A', 'B', 'AB', 'O')),
    rhesus           text        NOT NULL CHECK (rhesus IN ('POSITIVE', 'NEGATIVE')),
    component        text        NOT NULL CHECK (component IN ('WHOLE_BLOOD', 'RED_CELLS', 'PLATELETS', 'PLASMA', 'CRYOPRECIPITATE')),
    collection_date  date        NOT NULL,
    expiry_date      date        NOT NULL,
    -- Rule: a unit has exactly one state at a time.
    state            text        NOT NULL DEFAULT 'AVAILABLE'
                                 CHECK (state IN ('AVAILABLE', 'RESERVED', 'ISSUED', 'EXPIRED', 'DISCARDED')),
    recorded_by      uuid        NOT NULL REFERENCES app_user (id),
    recorded_at      timestamptz NOT NULL DEFAULT now(),
    -- Bumped on every state change; lets staff edits detect that someone else changed the unit first.
    version          integer     NOT NULL DEFAULT 0,
    CONSTRAINT unit_bank_fk FOREIGN KEY (bank_id, bank_type) REFERENCES organisation (id, type),
    CONSTRAINT unit_bank_reference_key UNIQUE (bank_id, reference),
    CONSTRAINT unit_expiry_after_collection_check CHECK (expiry_date > collection_date)
);
-- Serves availability search: only AVAILABLE units, narrowed by the filters, soonest expiry first.
CREATE INDEX unit_available_idx ON unit (blood_group, rhesus, component, bank_id, expiry_date) WHERE state = 'AVAILABLE';
-- Serves a bank's own unit list (ordered by expiry) and the expiry sweep.
CREATE INDEX unit_bank_expiry_idx ON unit (bank_id, expiry_date);
--rollback DROP TABLE unit;

--changeset team4:001-request-reference-seq
CREATE SEQUENCE blood_request_reference_seq;
--rollback DROP SEQUENCE blood_request_reference_seq;

--changeset team4:001-blood-request
-- Named blood_request because "request" is a reserved word in some SQL tools.
-- Rule: never a patient name or any patient detail. There is deliberately no column for one.
CREATE TABLE blood_request (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    reference        text        NOT NULL DEFAULT (
                                     'REQ-' || to_char(now() AT TIME ZONE 'UTC', 'YYYY') || '-'
                                     || lpad(nextval('blood_request_reference_seq')::text, 6, '0')),
    hospital_id      uuid        NOT NULL,
    hospital_type    text        NOT NULL DEFAULT 'HOSPITAL' CHECK (hospital_type = 'HOSPITAL'),
    bank_id          uuid        NOT NULL,
    bank_type        text        NOT NULL DEFAULT 'BLOOD_BANK' CHECK (bank_type = 'BLOOD_BANK'),
    blood_group      text        NOT NULL CHECK (blood_group IN ('A', 'B', 'AB', 'O')),
    rhesus           text        NOT NULL CHECK (rhesus IN ('POSITIVE', 'NEGATIVE')),
    component        text        NOT NULL CHECK (component IN ('WHOLE_BLOOD', 'RED_CELLS', 'PLATELETS', 'PLASMA', 'CRYOPRECIPITATE')),
    quantity         integer     NOT NULL CHECK (quantity BETWEEN 1 AND 20),
    urgency          text        NOT NULL CHECK (urgency IN ('ROUTINE', 'URGENT', 'EMERGENCY')),
    needed_by        timestamptz NOT NULL,
    state            text        NOT NULL DEFAULT 'PENDING'
                                 CHECK (state IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED', 'FULFILLED', 'LAPSED', 'UNIT_EXPIRED')),
    decline_reason   text        CHECK (decline_reason IS NULL OR length(trim(decline_reason)) BETWEEN 3 AND 500),
    created_by       uuid        NOT NULL REFERENCES app_user (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- Safe repeat submission: the client's key, and a hash of the body it was first used with.
    idempotency_key  uuid        NOT NULL,
    payload_hash     text        NOT NULL,
    CONSTRAINT blood_request_reference_key UNIQUE (reference),
    CONSTRAINT blood_request_hospital_fk FOREIGN KEY (hospital_id, hospital_type) REFERENCES organisation (id, type),
    CONSTRAINT blood_request_bank_fk FOREIGN KEY (bank_id, bank_type) REFERENCES organisation (id, type),
    -- The same user submitting the same key twice can only ever create one request.
    CONSTRAINT blood_request_idempotency_key UNIQUE (created_by, idempotency_key),
    -- A declined request always has its reason; nothing else has one.
    CONSTRAINT blood_request_declined_has_reason_check CHECK ((state = 'DECLINED') = (decline_reason IS NOT NULL))
);
-- Serves "my hospital's requests, newest first".
CREATE INDEX blood_request_hospital_idx ON blood_request (hospital_id, created_at DESC);
-- Serves "requests addressed to my bank, by state, newest first".
CREATE INDEX blood_request_bank_idx ON blood_request (bank_id, state, created_at DESC);
--rollback DROP TABLE blood_request;

--changeset team4:001-reservation
CREATE TABLE reservation (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id       uuid        NOT NULL REFERENCES blood_request (id),
    unit_id          uuid        NOT NULL REFERENCES unit (id),
    status           text        NOT NULL DEFAULT 'ACTIVE'
                                 CHECK (status IN ('ACTIVE', 'LAPSED', 'ISSUED', 'UNIT_EXPIRED', 'RELEASED')),
    hold_expires_at  timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    ended_at         timestamptz,
    CONSTRAINT reservation_hold_after_creation_check CHECK (hold_expires_at > created_at),
    -- An ACTIVE reservation has not ended; every other status has.
    CONSTRAINT reservation_ended_check CHECK ((status = 'ACTIVE') = (ended_at IS NULL)),
    -- A request reserves a given unit at most once.
    CONSTRAINT reservation_request_unit_key UNIQUE (request_id, unit_id)
);
-- THE key rule: a unit is held by at most one request at a time. Two hospitals holding the last unit
-- is impossible here, whatever the service code does and however many requests arrive at once.
CREATE UNIQUE INDEX reservation_one_active_hold_idx ON reservation (unit_id) WHERE status = 'ACTIVE';
-- Serves a request's reservations (request detail page).
CREATE INDEX reservation_request_idx ON reservation (request_id);
-- Serves the hold-lapse sweep and "reservations about to lapse" on the dashboard.
CREATE INDEX reservation_active_hold_expiry_idx ON reservation (hold_expires_at) WHERE status = 'ACTIVE';
--rollback DROP TABLE reservation;

--changeset team4:001-activity
-- History for units and requests. Rows are only ever added: a correction is a new row, never an edit,
-- so both the mistake and its reversal stay visible.
CREATE TABLE activity (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type  text        NOT NULL CHECK (entity_type IN ('UNIT', 'REQUEST')),
    entity_id    uuid        NOT NULL,
    event        text        NOT NULL CHECK (event ~ '^[A-Z_]{1,40}$'),
    -- NULL means the system did it (for example the job that releases lapsed holds).
    actor_id     uuid        REFERENCES app_user (id),
    note         text        CHECK (note IS NULL OR length(note) <= 500),
    at           timestamptz NOT NULL DEFAULT now()
);
-- Serves "history of this unit/request, oldest first".
CREATE INDEX activity_entity_idx ON activity (entity_type, entity_id, at);
-- Serves "recent activity" on the dashboard.
CREATE INDEX activity_at_idx ON activity (at DESC);
--rollback DROP TABLE activity;

--changeset team4:001-activity-append-only-function splitStatements:false
-- Enforces "history is never edited or deleted" in the database itself.
-- splitStatements:false keeps the function body in one piece (it contains semicolons).
CREATE FUNCTION activity_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'activity rows cannot be changed or deleted; add a new row instead'
        USING ERRCODE = 'restrict_violation';
END;
$$;
--rollback DROP FUNCTION activity_is_append_only();

--changeset team4:001-activity-append-only-trigger
CREATE TRIGGER activity_append_only
    BEFORE UPDATE OR DELETE ON activity
    FOR EACH ROW EXECUTE FUNCTION activity_is_append_only();
--rollback DROP TRIGGER activity_append_only ON activity;
