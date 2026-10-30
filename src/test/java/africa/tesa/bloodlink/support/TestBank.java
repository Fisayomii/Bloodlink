package africa.tesa.bloodlink.support;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A blood bank of its own for one test, with a staff account that can sign in (password Demo-Password-1) and
 * whatever units the test puts in it. Because nothing else uses this bank, counts and races are exact even though
 * every test shares one database.
 */
public final class TestBank {

    /** BCrypt hash of Demo-Password-1 (the same password as every demo account). */
    static final String DEMO_PASSWORD_HASH = "$2a$10$iEsVOuGpIQHFvUAdn6Kroe.v5cWJBSGtEY.cNQbHKIL2UQw0M0QXC";
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final JdbcClient jdbc;
    private final UUID id;
    private final UUID staffId;
    private final String staffEmail;

    private TestBank(JdbcClient jdbc, UUID id, UUID staffId, String staffEmail) {
        this.jdbc = jdbc;
        this.id = id;
        this.staffId = staffId;
        this.staffEmail = staffEmail;
    }

    public static TestBank create(JdbcClient jdbc) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID bank = jdbc.sql("INSERT INTO organisation (name, type, area) VALUES (?, 'BLOOD_BANK', ?) RETURNING id")
            .params("Test Bank " + tag, "Area-" + tag).query(UUID.class).single();
        String email = "staff-" + tag + "@test-bank.demo";
        UUID staff = jdbc.sql("""
                INSERT INTO app_user (email, display_name, password_hash, role, organisation_id)
                VALUES (?, ?, ?, 'BANK_STAFF', ?) RETURNING id""")
            .params(email, "Test staff " + tag, DEMO_PASSWORD_HASH, bank).query(UUID.class).single();
        return new TestBank(jdbc, bank, staff, email);
    }

    public UUID id() {
        return id;
    }

    /** Sign in with this and AbstractPostgresIT.signIn to act as this bank's staff. */
    public String staffEmail() {
        return staffEmail;
    }

    /** Adds an AVAILABLE O-negative red-cell unit expiring in the given number of days. Returns its id. */
    public UUID addUnit(int expiresInDays) {
        return addUnit("O", "NEGATIVE", "RED_CELLS", "AVAILABLE", expiresInDays);
    }

    public UUID addUnit(String group, String rhesus, String component, String state, int expiresInDays) {
        LocalDate today = LocalDate.now(LAGOS);
        return jdbc.sql("""
                INSERT INTO unit (bank_id, reference, blood_group, rhesus, component, collection_date, expiry_date, state, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
            .params(id, "TB-" + UUID.randomUUID().toString().substring(0, 13), group, rhesus, component,
                today.minusDays(20), today.plusDays(expiresInDays), state, staffId)
            .query(UUID.class).single();
    }

    /**
     * Opens a donation slot at this bank starting the given number of minutes from now (negative = already started),
     * lasting one hour. Written straight to the database, so a test can make a slot that has already started.
     */
    public UUID addSlot(long startsInMinutes, int capacity) {
        return jdbc.sql("""
                INSERT INTO donation_slot (bank_id, starts_at, ends_at, capacity, created_by, idempotency_key, payload_hash)
                VALUES (?, now() + make_interval(mins => ?), now() + make_interval(mins => ?) + interval '1 hour', ?, ?,
                        gen_random_uuid(), 'test')
                RETURNING id""")
            .params(id, (int) startsInMinutes, (int) startsInMinutes, capacity, staffId)
            .query(UUID.class).single();
    }

    /** Active bookings (BOOKED or ATTENDED) on a slot, straight from the database. */
    public int activeBookings(UUID slot) {
        return jdbc.sql("SELECT count(*) FROM booking WHERE slot_id = ? AND status IN ('BOOKED', 'ATTENDED')")
            .param(slot).query(Integer.class).single();
    }

    /** Number of ACTIVE reservations on a unit, straight from the database. */
    public int activeHolds(UUID unit) {
        return jdbc.sql("SELECT count(*) FROM reservation WHERE unit_id = ? AND status = 'ACTIVE'")
            .param(unit).query(Integer.class).single();
    }
}
