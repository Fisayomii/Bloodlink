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
 * POST /requests/{id}/hold-release (ADR-002): bank staff release a routine request's hold so an emergency request can
 * have the unit. Never automatic, only for a matching emergency, only when no free unit could meet it, and all or
 * nothing together with the emergency's approval.
 */
class HoldReleaseIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    private TestBank bank;
    private String bankToken;
    private UUID unit;
    private String routine;

    @BeforeEach
    void aRoutineRequestHoldsTheOnlyUnit() throws Exception {
        bank = TestBank.create(jdbc);
        bankToken = signIn(bank.staffEmail());
        unit = bank.addUnit(10);
        routine = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");
        assertThat(postJson("/requests/" + routine + "/approval", "", bankToken).statusCode()).isEqualTo(201);
    }

    @Test
    void releasingARoutineHoldGivesTheUnitToTheEmergencyAndKeepsTheRoutineRequestInTheQueue() throws Exception {
        String emergency = emergencyFor(1, "NEGATIVE");

        HttpResponse<String> response = release(routine, emergency);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) json(response.body(), "$.id")).isEqualTo(emergency);
        assertThat((String) json(response.body(), "$.state")).isEqualTo("APPROVED");
        assertThat((String) json(response.body(), "$.reservations[0].unit.id")).isEqualTo(unit.toString());
        assertThat(bank.activeHolds(unit)).isEqualTo(1);

        String routineNow = get("/requests/" + routine, bankToken).body();
        assertThat((String) json(routineNow, "$.state")).as("back in the queue, not lost").isEqualTo("PENDING");
        assertThat((String) json(routineNow, "$.reservations[0].status")).isEqualTo("RELEASED");
        List<String> notes = json(routineNow, "$.history[?(@.event == 'HOLD_RELEASED')].note");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).contains("courier has not left");
    }

    @Test
    void onlyARoutineHoldCanBeReleased() throws Exception {
        TestBank other = TestBank.create(jdbc);
        String otherToken = signIn(other.staffEmail());
        other.addUnit(10);
        String urgent = createRequest(YABA_HOSPITAL_STAFF, other.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");
        postJson("/requests/" + urgent + "/approval", "", otherToken);
        String emergency = createRequest(IKORODU_HOSPITAL_STAFF, other.id(), "O", "NEGATIVE", "RED_CELLS", 1, "EMERGENCY");

        HttpResponse<String> response = postJson("/requests/" + urgent + "/hold-release", body(emergency), otherToken);

        assertRefused(response, "HOLD_NOT_RELEASABLE");
    }

    @Test
    void notWhenAFreeUnitCouldMeetTheEmergency() throws Exception {
        bank.addUnit(12);
        String emergency = emergencyFor(1, "NEGATIVE");

        assertRefused(release(routine, emergency), "HOLD_NOT_RELEASABLE");
        assertThat((String) json(get("/requests/" + routine, bankToken).body(), "$.state")).isEqualTo("APPROVED");
    }

    @Test
    void onlyForAnEmergencyOfTheSameGroupAndComponent() throws Exception {
        String otherGroup = emergencyFor(1, "POSITIVE");

        assertRefused(release(routine, otherGroup), "HOLD_NOT_RELEASABLE");
        assertThat(bank.activeHolds(unit)).isEqualTo(1);
    }

    @Test
    void allOrNothingWhenTheEmergencyStillCannotBeMet() throws Exception {
        String needsTwo = emergencyFor(2, "NEGATIVE");

        assertRefused(release(routine, needsTwo), "UNITS_NO_LONGER_AVAILABLE");

        // The release was undone with the failed approval: the routine request still holds its unit.
        String routineNow = get("/requests/" + routine, bankToken).body();
        assertThat((String) json(routineNow, "$.state")).isEqualTo("APPROVED");
        assertThat((String) json(routineNow, "$.reservations[0].status")).isEqualTo("ACTIVE");
        assertThat(bank.activeHolds(unit)).isEqualTo(1);
    }

    @Test
    void anotherBanksStaffCannotReleaseAndAreToldItDoesNotExist() throws Exception {
        String emergency = emergencyFor(1, "NEGATIVE");

        HttpResponse<String> response = postJson("/requests/" + routine + "/hold-release", body(emergency),
            signIn(TestBank.create(jdbc).staffEmail()));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(bank.activeHolds(unit)).isEqualTo(1);
    }

    @Test
    void aReleaseNeedsAReason() throws Exception {
        String emergency = emergencyFor(1, "NEGATIVE");

        HttpResponse<String> response = postJson("/requests/" + routine + "/hold-release",
            "{\"forRequestId\":\"" + emergency + "\"}", bankToken);

        assertThat(response.statusCode()).isEqualTo(422);
    }

    private String emergencyFor(int quantity, String rhesus) throws Exception {
        return createRequest(IKORODU_HOSPITAL_STAFF, bank.id(), "O", rhesus, "RED_CELLS", quantity, "EMERGENCY");
    }

    private HttpResponse<String> release(String from, String to) throws Exception {
        return postJson("/requests/" + from + "/hold-release", body(to), bankToken);
    }

    private static String body(String forRequestId) {
        return "{\"forRequestId\":\"" + forRequestId + "\",\"reason\":\"Ikorodu emergency; Yaba confirmed its courier has not left.\"}";
    }

    private static void assertRefused(HttpResponse<String> response, String code) {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) json(response.body(), "$.code")).isEqualTo(code);
    }
}
