package africa.tesa.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * POST /requests/{id}/cancellation. The brief: "Only the requesting hospital can cancel its own request. Enforced by
 * the service, not by the interface." Banks and the administrator are refused in RoleRulesIT; other hospitals here.
 */
class CancellationIT extends AbstractPostgresIT {

    private static final String REASON = "{\"reason\":\"Patient transferred to another hospital.\"}";

    @Autowired
    JdbcClient jdbc;

    private TestBank bank;
    private String bankToken;
    private String yaba;

    @BeforeEach
    void setUp() throws Exception {
        bank = TestBank.create(jdbc);
        bankToken = signIn(bank.staffEmail());
        yaba = signIn(YABA_HOSPITAL_STAFF);
    }

    @Test
    void aHospitalCancelsItsOwnPendingRequest() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");

        HttpResponse<String> response = postJson("/requests/" + id + "/cancellation", REASON, yaba);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) json(response.body(), "$.state")).isEqualTo("CANCELLED");
        List<String> notes = json(response.body(), "$.history[?(@.event == 'CANCELLED')].note");
        assertThat(notes).containsExactly("Patient transferred to another hospital.");
    }

    @Test
    void cancellingAnApprovedRequestPutsItsUnitBackInStockStraightAway() throws Exception {
        UUID unit = bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");
        postJson("/requests/" + id + "/approval", "", bankToken);

        HttpResponse<String> response = postJson("/requests/" + id + "/cancellation", REASON, yaba);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) json(response.body(), "$.reservations[0].status")).isEqualTo("RELEASED");
        assertThat(bank.activeHolds(unit)).isZero();
        String unitNow = get("/units/" + unit, bankToken).body();
        assertThat((String) json(unitNow, "$.state")).isEqualTo("AVAILABLE");
        List<String> unitEvents = json(unitNow, "$.history[*].event");
        assertThat(unitEvents).containsExactly("RESERVED", "HOLD_RELEASED");
        assertThat((Integer) json(get("/availability?bankId=" + bank.id(), bankToken).body(), "$.items[0].availableUnits"))
            .isEqualTo(1);
    }

    @Test
    void anotherHospitalCannotCancelAndIsToldItDoesNotExist() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");

        HttpResponse<String> response = postJson("/requests/" + id + "/cancellation", REASON, signIn(IKORODU_HOSPITAL_STAFF));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) json(get("/requests/" + id, yaba).body(), "$.state")).isEqualTo("PENDING");
    }

    @Test
    void cancellingTwiceIsRefused() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");
        postJson("/requests/" + id + "/cancellation", REASON, yaba);

        HttpResponse<String> response = postJson("/requests/" + id + "/cancellation", REASON, yaba);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("REQUEST_CLOSED");
    }

    @Test
    void aFulfilledRequestCannotBeCancelled() throws Exception {
        bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");
        postJson("/requests/" + id + "/approval", "", bankToken);
        postJson("/requests/" + id + "/issue", "", bankToken);

        HttpResponse<String> response = postJson("/requests/" + id + "/cancellation", REASON, yaba);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("REQUEST_CLOSED");
    }

    @Test
    void aCancellationNeedsAReason() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");

        assertThat(postJson("/requests/" + id + "/cancellation", "{}", yaba).statusCode()).isEqualTo(422);
    }
}
