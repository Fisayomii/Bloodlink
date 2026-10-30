package africa.tesa.bloodlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The business rules the database enforces on its own, whatever the service code does.
 * Each test writes straight to the database (no service in between) and checks the database refuses.
 * The concurrent HTTP test for the reservation rule comes with the approval endpoint.
 */
class SchemaRulesIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    private UUID bank;
    private UUID hospital;
    private UUID bankUser;
    private UUID hospitalUser;

    @BeforeEach
    void createOrganisationsAndUsers() {
        bank = organisation("BLOOD_BANK");
        hospital = organisation("HOSPITAL");
        bankUser = user("BANK_STAFF", bank);
        hospitalUser = user("HOSPITAL_STAFF", hospital);
    }

    @Test
    void aUnitCanBeHeldByOnlyOneActiveReservation() {
        UUID unit = unit(bank, LocalDate.now().plusDays(30));
        reserve(request(), unit);

        assertThatThrownBy(() -> reserve(request(), unit)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void onceAHoldEndsAnotherRequestCanHoldTheUnit() {
        UUID unit = unit(bank, LocalDate.now().plusDays(30));
        reserve(request(), unit);
        jdbc.sql("UPDATE reservation SET status = 'LAPSED', ended_at = now() WHERE unit_id = ? AND status = 'ACTIVE'")
            .param(unit).update();

        reserve(request(), unit);

        assertThat(activeHolds(unit)).isEqualTo(1);
    }

    @Test
    void aUnitHasExactlyOneKnownState() {
        UUID unit = unit(bank, LocalDate.now().plusDays(30));

        assertThatThrownBy(() -> jdbc.sql("UPDATE unit SET state = 'LOST' WHERE id = ?").param(unit).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aUnitCanOnlyBelongToABloodBank() {
        assertThatThrownBy(() -> unit(hospital, LocalDate.now().plusDays(30)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aUnitCannotExpireBeforeItWasCollected() {
        assertThatThrownBy(() -> unit(bank, LocalDate.now().minusDays(5)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameIdempotencyKeyFromTheSameUserCreatesOnlyOneRequest() {
        UUID key = UUID.randomUUID();
        insertRequest(key);

        assertThatThrownBy(() -> insertRequest(key)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aDeclinedRequestAlwaysHasItsReason() {
        UUID request = request();

        assertThatThrownBy(() -> jdbc.sql("UPDATE blood_request SET state = 'DECLINED' WHERE id = ?").param(request).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historyIsAppendOnly() {
        UUID unit = unit(bank, LocalDate.now().plusDays(30));
        jdbc.sql("INSERT INTO activity (entity_type, entity_id, event, actor_id) VALUES ('UNIT', ?, 'RECORDED', ?)")
            .params(unit, bankUser).update();

        // The trigger's own message, so this cannot pass for some unrelated database error.
        assertThatThrownBy(() -> jdbc.sql("UPDATE activity SET note = 'edited' WHERE entity_id = ?").param(unit).update())
            .isInstanceOf(DataAccessException.class).hasMessageContaining("cannot be changed or deleted");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM activity WHERE entity_id = ?").param(unit).update())
            .isInstanceOf(DataAccessException.class).hasMessageContaining("cannot be changed or deleted");
    }

    @Test
    void requestsGetAReadableReference() {
        UUID request = request();

        String reference = jdbc.sql("SELECT reference FROM blood_request WHERE id = ?").param(request).query(String.class).single();

        assertThat(reference).matches("^REQ-\\d{4}-\\d{6}$");
    }

    @Test
    void aSlotCanNeverHoldMoreActiveBookingsThanItHasPlaces() {
        UUID slot = slot(2);
        book(slot, 1, user("DONOR", null));
        book(slot, 2, user("DONOR", null));

        // A third active booking has nowhere to go: place 1 is taken, and there is no place 3 in a 2-place slot.
        assertThatThrownBy(() -> book(slot, 1, user("DONOR", null))).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> book(slot, 3, user("DONOR", null))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aBookingCannotClaimABiggerCapacityThanItsSlot() {
        UUID slot = slot(2);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO booking (slot_id, slot_capacity, place_no, donor_id) VALUES (?, 10, 3, ?)""")
            .params(slot, user("DONOR", null)).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCancelledPlaceCanBeTakenAgainButADonorHoldsOnePlacePerSlot() {
        UUID slot = slot(3);
        UUID donor = user("DONOR", null);
        book(slot, 1, donor);
        jdbc.sql("UPDATE booking SET status = 'CANCELLED', status_changed_at = now() WHERE slot_id = ? AND place_no = 1")
            .param(slot).update();

        book(slot, 1, user("DONOR", null));
        book(slot, 2, donor);

        assertThatThrownBy(() -> book(slot, 3, donor)).isInstanceOf(DuplicateKeyException.class);
    }

    // --- fixtures: every test makes its own rows, because the database is shared across the test run ---

    private UUID organisation(String type) {
        return jdbc.sql("INSERT INTO organisation (name, type, area) VALUES (?, ?, 'Ikeja') RETURNING id")
            .params("Test " + type + " " + UUID.randomUUID(), type).query(UUID.class).single();
    }

    private UUID user(String role, UUID organisation) {
        return jdbc.sql("""
                INSERT INTO app_user (email, display_name, password_hash, role, organisation_id)
                VALUES (?, 'Test user', 'not-a-real-hash', ?, ?) RETURNING id""")
            .params(UUID.randomUUID() + "@test.demo", role, organisation).query(UUID.class).single();
    }

    private UUID unit(UUID bankId, LocalDate expiry) {
        return jdbc.sql("""
                INSERT INTO unit (bank_id, reference, blood_group, rhesus, component, collection_date, expiry_date, recorded_by)
                VALUES (?, ?, 'O', 'NEGATIVE', 'RED_CELLS', ?, ?, ?) RETURNING id""")
            .params(bankId, "T-" + UUID.randomUUID().toString().substring(0, 8), LocalDate.now().minusDays(1), expiry, bankUser)
            .query(UUID.class).single();
    }

    private UUID request() {
        return insertRequest(UUID.randomUUID());
    }

    private UUID insertRequest(UUID idempotencyKey) {
        return jdbc.sql("""
                INSERT INTO blood_request (hospital_id, bank_id, blood_group, rhesus, component, quantity, urgency, needed_by,
                                           created_by, idempotency_key, payload_hash)
                VALUES (?, ?, 'O', 'NEGATIVE', 'RED_CELLS', 1, 'EMERGENCY', now() + interval '4 hours', ?, ?, 'test')
                RETURNING id""")
            .params(hospital, bank, hospitalUser, idempotencyKey).query(UUID.class).single();
    }

    private void reserve(UUID request, UUID unit) {
        jdbc.sql("INSERT INTO reservation (request_id, unit_id, hold_expires_at) VALUES (?, ?, now() + interval '1 hour')")
            .params(request, unit).update();
    }

    private int activeHolds(UUID unit) {
        return jdbc.sql("SELECT count(*) FROM reservation WHERE unit_id = ? AND status = 'ACTIVE'")
            .param(unit).query(Integer.class).single();
    }

    private UUID slot(int capacity) {
        return jdbc.sql("""
                INSERT INTO donation_slot (bank_id, starts_at, ends_at, capacity, created_by, idempotency_key, payload_hash)
                VALUES (?, now() + interval '1 day', now() + interval '1 day 1 hour', ?, ?, gen_random_uuid(), 'test')
                RETURNING id""")
            .params(bank, capacity, bankUser).query(UUID.class).single();
    }

    private void book(UUID slot, int place, UUID donor) {
        jdbc.sql("""
                INSERT INTO booking (slot_id, slot_capacity, place_no, donor_id)
                SELECT id, capacity, ?, ? FROM donation_slot WHERE id = ?""")
            .params(place, donor, slot).update();
    }
}
