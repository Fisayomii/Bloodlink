--liquibase formatted sql

-- Demonstration organisations and accounts. All fictional: "(demo)" in every name, ".demo" in every email.
-- Runs only with the Liquibase context "demo" (the default; see spring.liquibase.contexts).
-- Every account's password is Demo-Password-1, stored only as a BCrypt hash. Listed in the README.
-- Ids for Lagos Central, Yaba and Adaeze match the examples in contract/openapi.yaml, so Swagger's
-- example values work against the real service.

--changeset team4:002-demo-organisations context:demo
INSERT INTO organisation (id, name, type, area) VALUES
    ('0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b', 'Lagos Central Blood Bank (demo)',     'BLOOD_BANK', 'Ikeja'),
    ('0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6c', 'Lekki Regional Blood Centre (demo)',  'BLOOD_BANK', 'Lekki'),
    ('0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6d', 'Surulere Community Blood Bank (demo)', 'BLOOD_BANK', 'Surulere'),
    ('6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b', 'Yaba General Hospital (demo)',        'HOSPITAL',   'Yaba'),
    ('6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9c', 'Ikorodu Children''s Hospital (demo)', 'HOSPITAL',   'Ikorodu'),
    ('6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9d', 'Victoria Island Medical Centre (demo)', 'HOSPITAL', 'Victoria Island');
--rollback DELETE FROM organisation WHERE id IN ('0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b', '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6c', '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6d', '6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b', '6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9c', '6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9d');

--changeset team4:002-demo-users context:demo
INSERT INTO app_user (id, email, display_name, password_hash, role, organisation_id) VALUES
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000001', 'admin@bloodlink.demo', 'Demo Administrator',
     '$2a$10$K/hcRGashMin6uzICG2xsu6Q4DbjUKZMPCAOYycl7z4ukRBRDztfa', 'ADMIN', NULL),
    ('9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d', 'staff@lagos-central.bank.demo', 'Adaeze Okafor',
     '$2a$10$iEsVOuGpIQHFvUAdn6Kroe.v5cWJBSGtEY.cNQbHKIL2UQw0M0QXC', 'BANK_STAFF', '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b'),
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000003', 'staff@lekki.bank.demo', 'Ibrahim Musa',
     '$2a$10$KDk5bB4npZud9fNLlLu37Od3P7Issc0uDykughtl0o5kuOBQj2Jz2', 'BANK_STAFF', '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6c'),
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000004', 'staff@surulere.bank.demo', 'Funke Adeyemi',
     '$2a$10$J8qh0gmHeK9.GPcDoDfOa.TZaLUgV942TpyFa.9C6OP1Z2bnEcLqm', 'BANK_STAFF', '0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6d'),
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000005', 'staff@yaba.hospital.demo', 'Tunde Bello',
     '$2a$10$hwETTrJ8A7FDn.JRnV2OY.2rZt0ycyRl1xzPslK1Coz4wtWPl8fXy', 'HOSPITAL_STAFF', '6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b'),
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000006', 'staff@ikorodu.hospital.demo', 'Chiamaka Eze',
     '$2a$10$Q765ZcOQSu.H.sKoPhwNr.2ELDzy6vS2csXn5vr4E4w00YLbZ1cla', 'HOSPITAL_STAFF', '6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9c'),
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000007', 'staff@vi.hospital.demo', 'Segun Alabi',
     '$2a$10$zO5weezIXVPFIe76T9WN4uUYt/1uroUGQORhj.imLj5B3lIsVLNl6', 'HOSPITAL_STAFF', '6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9d'),
    ('9a8b7c6d-5e4f-4a3b-8c2d-000000000008', 'donor@bloodlink.demo', 'Kemi Ojo',
     '$2a$10$QHHCWP8tvihWUqIGkO5DT.aYIs0WJ2/BMM.LVet824qkUHxaBiw4K', 'DONOR', NULL);
--rollback DELETE FROM app_user WHERE id IN ('9a8b7c6d-5e4f-4a3b-8c2d-000000000001', '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d', '9a8b7c6d-5e4f-4a3b-8c2d-000000000003', '9a8b7c6d-5e4f-4a3b-8c2d-000000000004', '9a8b7c6d-5e4f-4a3b-8c2d-000000000005', '9a8b7c6d-5e4f-4a3b-8c2d-000000000006', '9a8b7c6d-5e4f-4a3b-8c2d-000000000007', '9a8b7c6d-5e4f-4a3b-8c2d-000000000008');
