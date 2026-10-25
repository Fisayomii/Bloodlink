"""Generates 005-demo-data.sql: the BloodLink demonstration data required by the brief.

Deterministic: ids come from uuid5 of a name, dates are relative to the moment the migration runs (current_date / now()),
so the data is fresh whenever a database is first set up. Every row is marked: unit references start with SEED-,
request payload_hash is 'seed'.
"""
import pathlib
import uuid

# Run from the repository root: python scripts/gen_seed.py
OUTPUT = pathlib.Path("src/main/resources/db/changelog/changes/005-demo-data.sql")

NS = uuid.UUID("5eed0000-0000-4000-8000-000000000000")
uid = lambda name: str(uuid.uuid5(NS, name))

BANKS = {
    "LAGOS": ("0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b", "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"),
    "LEKKI": ("0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6c", "9a8b7c6d-5e4f-4a3b-8c2d-000000000003"),
    "SURULERE": ("0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6d", "9a8b7c6d-5e4f-4a3b-8c2d-000000000004"),
}
HOSPITALS = {
    "YABA": ("6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b", "9a8b7c6d-5e4f-4a3b-8c2d-000000000005"),
    "IKORODU": ("6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9c", "9a8b7c6d-5e4f-4a3b-8c2d-000000000006"),
    "VI": ("6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9d", "9a8b7c6d-5e4f-4a3b-8c2d-000000000007"),
}
GROUPS = [(g, r) for g in ["A", "B", "AB", "O"] for r in ["POSITIVE", "NEGATIVE"]]
COMPONENTS = ["RED_CELLS", "WHOLE_BLOOD", "PLATELETS", "PLASMA", "CRYOPRECIPITATE"]
# Shelf life in days per component, roughly realistic, so expiry dates look plausible.
SHELF = {"RED_CELLS": 42, "WHOLE_BLOOD": 35, "PLATELETS": 5, "PLASMA": 365, "CRYOPRECIPITATE": 365}

units = []      # dicts: key, bank, group, rhesus, component, collected (days from today), expires, state
requests = []   # dicts with everything needed
reservations = []
unit_events = []
request_events = []
counter = {"n": 0}


def unit(bank, group, rhesus, component, state="AVAILABLE", expires=None, collected=None, key=None):
    counter["n"] += 1
    n = counter["n"]
    if collected is None:
        collected = -(1 + n % 4)
    if expires is None:
        expires = collected + SHELF[component] if SHELF[component] < 60 else 30 + n % 60
    key = key or f"u{n}"
    u = dict(key=key, id=uid("unit-" + key), ref=f"SEED-{bank[:3]}-{n:03d}", bank=bank, group=group, rhesus=rhesus,
             component=component, collected=collected, expires=expires, state=state)
    assert expires > collected, u
    units.append(u)
    unit_events.append((u["id"], "RECORDED", BANKS[bank][1], None, f"now() - interval '{-collected} days'"))
    return u


# 1. Everyday stock: every bank holds every one of the eight groups, in varied components.
#    Lagos Central gets NO O-negative red cells here: it gets exactly one below, "the last unit" for the demo.
for b_index, bank in enumerate(BANKS):
    for g_index, (group, rhesus) in enumerate(GROUPS):
        count = 1 if (group, rhesus) == ("AB", "NEGATIVE") else 2   # AB-negative runs low everywhere
        for k in range(count):
            component = COMPONENTS[(g_index + k + b_index) % len(COMPONENTS)]
            if bank == "LAGOS" and (group, rhesus, component) == ("O", "NEGATIVE", "RED_CELLS"):
                component = "PLASMA"
            unit(bank, group, rhesus, component)

# 2. Awkward stock states.
last_unit = unit("LAGOS", "O", "NEGATIVE", "RED_CELLS", expires=25, key="last-o-neg")   # the race demo
for bank in BANKS:                                                                     # expiring soon (dashboard)
    unit(bank, "O", "POSITIVE", "RED_CELLS", expires=2)
for bank, group, rhesus in [("LAGOS", "A", "POSITIVE"), ("LEKKI", "B", "NEGATIVE"), ("SURULERE", "O", "POSITIVE")]:
    d = unit(bank, group, rhesus, "WHOLE_BLOOD", state="DISCARDED", expires=20)      # discarded (e.g. damaged bag)
    unit_events.append((d["id"], "DISCARDED", BANKS[bank][1], "Bag damaged in storage", "now() - interval '1 day'"))
for bank, group, rhesus in [("LAGOS", "B", "POSITIVE"), ("LEKKI", "A", "NEGATIVE"), ("SURULERE", "AB", "POSITIVE")]:
    e = unit(bank, group, rhesus, "PLATELETS", state="EXPIRED", collected=-9, expires=-4)   # expired, already marked
    unit_events.append((e["id"], "EXPIRED", None, "Passed its expiry date", "now() - interval '3 days'"))
# Past its expiry date but still stored AVAILABLE: the expiry job has not run. Reads must still show EXPIRED.
unit("SURULERE", "A", "NEGATIVE", "RED_CELLS", collected=-44, expires=-1, key="expired-no-job")

# 3. Requests in every state, from all three hospitals to all three banks.
r_counter = {"n": 0}


def request(hospital, bank, group, rhesus, component, quantity, urgency, state, days_ago, needed_in_hours,
            decline=None):
    r_counter["n"] += 1
    key = f"r{r_counter['n']}"
    r = dict(key=key, id=uid("request-" + key), hospital=hospital, bank=bank, group=group, rhesus=rhesus,
             component=component, quantity=quantity, urgency=urgency, state=state, days_ago=days_ago,
             needed_in_hours=needed_in_hours, decline=decline)
    requests.append(r)
    request_events.append((r["id"], "CREATED", HOSPITALS[hospital][1], None, f"now() - interval '{days_ago} days'"))
    return r


def reserve(r, u, status, created_sql, hold_sql, ended_sql=None):
    reservations.append(dict(id=uid(f"res-{r['key']}-{u['key']}"), request=r["id"], unit=u["id"], status=status,
                             created=created_sql, hold=hold_sql, ended=ended_sql))
    unit_events.append((u["id"], "RESERVED", BANKS[r["bank"]][1], "Reserved for a request", created_sql))


# PENDING (8): including all three hospitals asking Lagos Central for O-negative red cells, its last unit.
for hospital in HOSPITALS:
    request(hospital, "LAGOS", "O", "NEGATIVE", "RED_CELLS", 1, "EMERGENCY", "PENDING", 0, 6)
request("YABA", "LEKKI", "A", "POSITIVE", "PLASMA", 2, "ROUTINE", "PENDING", 1, 48)
request("IKORODU", "SURULERE", "B", "POSITIVE", "PLATELETS", 1, "URGENT", "PENDING", 0, 12)
request("VI", "LEKKI", "O", "POSITIVE", "RED_CELLS", 3, "URGENT", "PENDING", 0, 10)
request("YABA", "SURULERE", "AB", "NEGATIVE", "PLASMA", 1, "ROUTINE", "PENDING", 2, 72)
request("IKORODU", "LAGOS", "B", "NEGATIVE", "WHOLE_BLOOD", 2, "ROUTINE", "PENDING", 1, 30)

# APPROVED with a running hold (1). SIMULATED: the hold is set to 10 days so the RESERVED state stays visible during
# the assessment; real holds last 1 to 4 hours (README, "Simulated").
approved = request("VI", "SURULERE", "A", "POSITIVE", "RED_CELLS", 2, "URGENT", "APPROVED", 0, 20)
for i in range(2):
    u = unit("SURULERE", "A", "POSITIVE", "RED_CELLS", state="RESERVED", expires=30, key=f"held-{i}")
    reserve(approved, u, "ACTIVE", "now() - interval '1 hour'", "now() + interval '10 days'")
request_events.append((approved["id"], "APPROVED", BANKS["SURULERE"][1], "Hold 14400 minutes (demonstration)",
                       "now() - interval '1 hour'"))

# DECLINED (4), each with its reason.
for hospital, bank, g, rh, comp, reason, days in [
        ("YABA", "LAGOS", "AB", "NEGATIVE", "PLATELETS", "No AB-negative platelets in stock until next week.", 3),
        ("IKORODU", "LEKKI", "O", "NEGATIVE", "WHOLE_BLOOD", "Whole blood not held at this bank; try Lagos Central.", 5),
        ("VI", "LAGOS", "B", "NEGATIVE", "CRYOPRECIPITATE", "Remaining stock reserved for an emergency transfer.", 2),
        ("YABA", "SURULERE", "O", "NEGATIVE", "PLASMA", "Needed-by time cannot be met; courier unavailable today.", 6)]:
    d = request(hospital, bank, g, rh, comp, 1, "ROUTINE", "DECLINED", days, -24, decline=reason)
    request_events.append((d["id"], "DECLINED", BANKS[bank][1], reason, f"now() - interval '{days} days' + interval '2 hours'"))

# LAPSED (3): approved, but the hold ran out before the units were collected; units went back to stock.
for hospital, bank, g, rh, comp, days in [("IKORODU", "LAGOS", "A", "NEGATIVE", "PLASMA", 4),
                                          ("YABA", "LEKKI", "B", "POSITIVE", "RED_CELLS", 3),
                                          ("VI", "SURULERE", "O", "POSITIVE", "WHOLE_BLOOD", 2)]:
    l = request(hospital, bank, g, rh, comp, 1, "ROUTINE", "LAPSED", days, -12)
    u = unit(bank, g, rh, comp, expires=40, key=f"lapsed-{l['key']}")
    created = f"now() - interval '{days} days' + interval '1 hour'"
    reserve(l, u, "LAPSED", created, f"now() - interval '{days} days' + interval '5 hours'",
            f"now() - interval '{days} days' + interval '5 hours'")
    request_events.append((l["id"], "APPROVED", BANKS[bank][1], "Hold 240 minutes", created))
    request_events.append((l["id"], "HOLD_LAPSED", None, "Hold ended before the units were issued; units returned to stock",
                           f"now() - interval '{days} days' + interval '5 hours'"))
    unit_events.append((u["id"], "RETURNED_TO_STOCK", None, "Hold lapsed", f"now() - interval '{days} days' + interval '5 hours'"))

# UNIT_EXPIRED (2): the brief's "units that expired while reserved". Each unit reached EXPIRED, its reservation
# UNIT_EXPIRED, and the request UNIT_EXPIRED with a System entry saying what happened.
for hospital, bank, g, rh, days in [("YABA", "LEKKI", "B", "NEGATIVE", 2), ("VI", "LAGOS", "AB", "POSITIVE", 3)]:
    x = request(hospital, bank, g, rh, "PLATELETS", 1, "ROUTINE", "UNIT_EXPIRED", days + 1, -24)
    u = unit(bank, g, rh, "PLATELETS", state="EXPIRED", collected=-(days + 5), expires=-days,
             key=f"expired-while-reserved-{x['key']}")
    # Approved at 22:00 Lagos time the evening before the expiry date; the 4-hour hold ran past midnight,
    # so the unit expired while still reserved.
    approved_at = f"(current_date - {days + 1})::timestamp + interval '22 hours'"
    reserve(x, u, "UNIT_EXPIRED", approved_at, f"(current_date - {days + 1})::timestamp + interval '26 hours'",
            f"(current_date - {days})::timestamp + interval '5 minutes'")
    request_events.append((x["id"], "APPROVED", BANKS[bank][1], "Hold 240 minutes", approved_at))
    request_events.append((x["id"], "UNIT_EXPIRED", None, "A reserved unit passed its expiry date before it was issued",
                           f"(current_date - {days})::timestamp + interval '5 minutes'"))
    unit_events.append((u["id"], "EXPIRED", None, "Passed its expiry date while reserved",
                        f"(current_date - {days})::timestamp + interval '5 minutes'"))

# FULFILLED (4): units issued to the hospital.
for hospital, bank, g, rh, comp, qty, days in [("IKORODU", "SURULERE", "O", "NEGATIVE", "RED_CELLS", 2, 6),
                                               ("YABA", "LAGOS", "A", "POSITIVE", "PLASMA", 1, 5),
                                               ("VI", "LEKKI", "B", "POSITIVE", "PLATELETS", 1, 4),
                                               ("IKORODU", "LAGOS", "O", "POSITIVE", "RED_CELLS", 1, 7)]:
    f = request(hospital, bank, g, rh, comp, qty, "URGENT", "FULFILLED", days, -6)
    created = f"now() - interval '{days} days' + interval '30 minutes'"
    issued = f"now() - interval '{days} days' + interval '90 minutes'"
    for i in range(qty):
        u = unit(bank, g, rh, comp, state="ISSUED", collected=-(days + 3), expires=30, key=f"issued-{f['key']}-{i}")
        reserve(f, u, "ISSUED", created, f"now() - interval '{days} days' + interval '150 minutes'", issued)
        unit_events.append((u["id"], "ISSUED", BANKS[bank][1], None, issued))
    request_events.append((f["id"], "APPROVED", BANKS[bank][1], "Hold 120 minutes", created))
    request_events.append((f["id"], "FULFILLED", BANKS[bank][1], "All units issued", issued))

# CANCELLED (2): withdrawn by the hospital before a decision.
for hospital, bank, g, rh, comp, days in [("YABA", "LAGOS", "O", "POSITIVE", "PLATELETS", 2),
                                          ("VI", "LEKKI", "AB", "POSITIVE", "PLASMA", 1)]:
    c = request(hospital, bank, g, rh, comp, 1, "ROUTINE", "CANCELLED", days, 24)
    request_events.append((c["id"], "CANCELLED", HOSPITALS[hospital][1], "Patient transferred to another facility",
                           f"now() - interval '{days} days' + interval '3 hours'"))

# ---------- checks on the generated data (the brief's definition of done) ----------
assert len(units) >= 60, len(units)
assert len(requests) >= 20, len(requests)
for bank in BANKS:
    assert {(u["group"], u["rhesus"]) for u in units if u["bank"] == bank} == set(GROUPS), bank
assert {u["state"] for u in units} >= {"AVAILABLE", "RESERVED", "ISSUED", "EXPIRED", "DISCARDED"}
assert {r["state"] for r in requests} == {"PENDING", "APPROVED", "DECLINED", "LAPSED", "UNIT_EXPIRED", "FULFILLED", "CANCELLED"}
assert len([u for u in units if u["key"].startswith("expired-while-reserved")]) == 2
lagos_o_neg_rc = [u for u in units if u["bank"] == "LAGOS" and (u["group"], u["rhesus"], u["component"]) == ("O", "NEGATIVE", "RED_CELLS")
                  and u["state"] == "AVAILABLE" and u["expires"] >= 0]
assert len(lagos_o_neg_rc) == 1, lagos_o_neg_rc

# ---------- write the SQL ----------
q = lambda s: "NULL" if s is None else "'" + s.replace("'", "''") + "'"
out = []
out.append("--liquibase formatted sql\n")
out.append(f"""-- BloodLink demonstration data (context "demo"). GENERATED by scripts/gen_seed.py: edit the generator, not this file.
--
-- {len(units)} units across all eight groups at each of the three banks, in every state; {len(requests)} requests in every
-- state from all three hospitals; two units that expired while reserved, each in its final state (unit EXPIRED,
-- reservation UNIT_EXPIRED, request UNIT_EXPIRED). Lagos Central holds exactly one O-negative red-cell unit, and all
-- three hospitals have a pending emergency request for it: the race in the brief.
--
-- Dates are relative to the moment this runs. Rows are marked: unit references start with SEED-, requests have
-- payload_hash 'seed'. SIMULATED: the one APPROVED request holds its units for 10 days so the RESERVED state stays
-- visible during the assessment; real holds last 1 to 4 hours.
""")
out.append("--changeset team4:005-demo-units context:demo")
rows = []
for u in units:
    rows.append(f"    ({q(u['id'])}, {q(BANKS[u['bank']][0])}, {q(u['ref'])}, {q(u['group'])}, {q(u['rhesus'])}, {q(u['component'])}, "
                f"current_date + {u['collected']}, current_date + {u['expires']}, {q(u['state'])}, {q(BANKS[u['bank']][1])}, "
                f"now() - interval '{-u['collected']} days')")
out.append("INSERT INTO unit (id, bank_id, reference, blood_group, rhesus, component, collection_date, expiry_date, state, recorded_by, recorded_at) VALUES\n"
           + ",\n".join(rows) + ";")
out.append("--rollback DELETE FROM unit WHERE reference LIKE 'SEED-%';\n")

out.append("--changeset team4:005-demo-requests context:demo")
rows = []
for r in requests:
    rows.append(f"    ({q(r['id'])}, {q(HOSPITALS[r['hospital']][0])}, {q(BANKS[r['bank']][0])}, {q(r['group'])}, {q(r['rhesus'])}, "
                f"{q(r['component'])}, {r['quantity']}, {q(r['urgency'])}, now() + interval '{r['needed_in_hours'] - 24 * r['days_ago']} hours', "
                f"{q(r['state'])}, {q(r['decline'])}, {q(HOSPITALS[r['hospital']][1])}, now() - interval '{r['days_ago']} days', "
                f"{q(uid('key-' + r['key']))}, 'seed')")
out.append("INSERT INTO blood_request (id, hospital_id, bank_id, blood_group, rhesus, component, quantity, urgency, needed_by, state, "
           "decline_reason, created_by, created_at, idempotency_key, payload_hash) VALUES\n" + ",\n".join(rows) + ";")
out.append("--rollback DELETE FROM blood_request WHERE payload_hash = 'seed';\n")

out.append("--changeset team4:005-demo-reservations context:demo")
rows = []
for s in reservations:
    rows.append(f"    ({q(s['id'])}, {q(s['request'])}, {q(s['unit'])}, {q(s['status'])}, {s['hold']}, {s['created']}, "
                f"{s['ended'] if s['ended'] else 'NULL'})")
out.append("INSERT INTO reservation (id, request_id, unit_id, status, hold_expires_at, created_at, ended_at) VALUES\n" + ",\n".join(rows) + ";")
out.append("--rollback DELETE FROM reservation WHERE request_id IN (SELECT id FROM blood_request WHERE payload_hash = 'seed');\n")

out.append("--changeset team4:005-demo-history context:demo")
rows = []
for (entity, event, actor, note, at) in sorted(unit_events, key=lambda e: e[0]):
    rows.append(f"    ('UNIT', {q(entity)}, {q(event)}, {q(actor)}, {q(note)}, {at})")
for (entity, event, actor, note, at) in request_events:
    rows.append(f"    ('REQUEST', {q(entity)}, {q(event)}, {q(actor)}, {q(note)}, {at})")
out.append("INSERT INTO activity (entity_type, entity_id, event, actor_id, note, at) VALUES\n" + ",\n".join(rows) + ";")
out.append("""-- History is append-only (a trigger refuses deletes). Rolling back the demo data is the one deliberate exception.
--rollback ALTER TABLE activity DISABLE TRIGGER activity_append_only;
--rollback DELETE FROM activity WHERE entity_id IN (SELECT id FROM unit WHERE reference LIKE 'SEED-%' UNION SELECT id FROM blood_request WHERE payload_hash = 'seed');
--rollback ALTER TABLE activity ENABLE TRIGGER activity_append_only;
""")
open(OUTPUT, "w").write("\n".join(out))
states = {}
for u in units:
    states[u["state"]] = states.get(u["state"], 0) + 1
rstates = {}
for r in requests:
    rstates[r["state"]] = rstates.get(r["state"], 0) + 1
print(f"units={len(units)} {states}")
print(f"requests={len(requests)} {rstates}")
print(f"reservations={len(reservations)} history rows={len(unit_events) + len(request_events)}")
