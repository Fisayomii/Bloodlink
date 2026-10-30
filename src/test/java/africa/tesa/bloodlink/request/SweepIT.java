package africa.tesa.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The background job writes down what reads already show (ADR-003): every read is right before it runs, and the same
 * after. Run directly on a bank of the test's own; the schedule is off in tests.
 */
class SweepIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HoldAndExpirySweep sweep;

    @Autowired
    DataSource dataSource;

    private TestBank bank;
    private String bankToken;

    @BeforeEach
    void aBankOfItsOwn() throws Exception {
        bank = TestBank.create(jdbc);
        bankToken = signIn(bank.staffEmail());
    }

    @Test
    void aLapsedHoldIsWrittenDownAndWhatUsersSeeDoesNotChange() throws Exception {
        UUID unit = bank.addUnit(10);
        String request = approvedRequest();
        holdEndedAMinuteAgo(request);
        String shownBefore = json(get("/requests/" + request, bankToken).body(), "$.state");

        HoldAndExpirySweep.Result result = sweep.runOnce(bank.id());

        assertThat(shownBefore).isEqualTo("LAPSED");
        assertThat((String) json(get("/requests/" + request, bankToken).body(), "$.state")).isEqualTo("LAPSED");
        assertThat(result.holdsLapsed()).isEqualTo(1);
        assertThat(stored("blood_request", request)).isEqualTo("LAPSED");
        assertThat(storedUnit(unit)).isEqualTo("AVAILABLE");
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE unit_id = ?").param(unit).query(String.class).single())
            .isEqualTo("LAPSED");
        assertThat(systemEvents(UUID.fromString(request))).contains("HOLD_LAPSED");
        assertThat(systemEvents(unit)).contains("RETURNED_TO_STOCK");
    }

    @Test
    void aReservedUnitPastItsExpiryEndsAsExpiredWithItsRequestUnitExpired() throws Exception {
        UUID unit = bank.addUnit(10);
        String request = approvedRequest();
        jdbc.sql("UPDATE unit SET expiry_date = current_date - 1 WHERE id = ?").param(unit).update();

        sweep.runOnce(bank.id());

        assertThat((String) json(get("/requests/" + request, bankToken).body(), "$.state")).isEqualTo("UNIT_EXPIRED");
        assertThat(stored("blood_request", request)).isEqualTo("UNIT_EXPIRED");
        assertThat(storedUnit(unit)).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE unit_id = ?").param(unit).query(String.class).single())
            .isEqualTo("UNIT_EXPIRED");
        assertThat(systemEvents(UUID.fromString(request))).contains("UNIT_EXPIRED");
    }

    @Test
    void anAvailableUnitPastItsExpiryIsMarkedExpired() {
        UUID unit = bank.addUnit(10);
        jdbc.sql("UPDATE unit SET expiry_date = current_date - 1 WHERE id = ?").param(unit).update();

        HoldAndExpirySweep.Result result = sweep.runOnce(bank.id());

        assertThat(result.unitsExpired()).isEqualTo(1);
        assertThat(storedUnit(unit)).isEqualTo("EXPIRED");
        assertThat(systemEvents(unit)).contains("EXPIRED");
    }

    @Test
    void aRequestSomeoneIsActingOnIsLeftForTheNextRun() throws Exception {
        UUID unit = bank.addUnit(10);
        String request = approvedRequest();
        holdEndedAMinuteAgo(request);

        // Another transaction holds the request row, as an issue or a cancellation would.
        try (Connection busy = dataSource.getConnection()) {
            busy.setAutoCommit(false);
            try (PreparedStatement lock = busy.prepareStatement("SELECT id FROM blood_request WHERE id = ?::uuid FOR UPDATE")) {
                lock.setString(1, request);
                lock.executeQuery();
            }
            HoldAndExpirySweep.Result skipped = sweep.runOnce(bank.id());
            assertThat(skipped.holdsLapsed()).as("skipped, not waited for").isZero();
            busy.rollback();
        }

        sweep.runOnce(bank.id());
        assertThat(storedUnit(unit)).as("done on the next run").isEqualTo("AVAILABLE");
        assertThat(stored("blood_request", request)).isEqualTo("LAPSED");
    }

    private String approvedRequest() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");
        assertThat(postJson("/requests/" + id + "/approval", "", bankToken).statusCode()).isEqualTo(201);
        return id;
    }

    private void holdEndedAMinuteAgo(String requestId) {
        jdbc.sql("""
                UPDATE reservation SET created_at = now() - interval '3 hours', hold_expires_at = now() - interval '1 minute'
                 WHERE request_id = CAST(? AS uuid)""").param(requestId).update();
    }

    private String stored(String table, String id) {
        return jdbc.sql("SELECT state FROM " + table + " WHERE id = CAST(? AS uuid)").param(id).query(String.class).single();
    }

    private String storedUnit(UUID unit) {
        return jdbc.sql("SELECT state FROM unit WHERE id = ?").param(unit).query(String.class).single();
    }

    private List<String> systemEvents(UUID entity) {
        return jdbc.sql("SELECT event FROM activity WHERE entity_id = ? AND actor_id IS NULL ORDER BY at, id")
            .param(entity).query(String.class).list();
    }
}
