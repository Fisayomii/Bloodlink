package africa.tesa.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** POST /requests/{id}/approval and /declination, one request at a time. The race is in ConcurrentApprovalIT. */
class ApprovalIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    private TestBank bank;
    private String bankToken;

    @BeforeEach
    void aBankOfItsOwn() throws Exception {
        bank = TestBank.create(jdbc);
        bankToken = signIn(bank.staffEmail());
    }

    @Test
    void approvalReservesTheSoonestExpiringUnitsWithAHold() throws Exception {
        UUID later = bank.addUnit(40);
        UUID soonest = bank.addUnit(10);
        UUID middle = bank.addUnit(20);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 2, "EMERGENCY");

        HttpResponse<String> response = postJson("/requests/" + id + "/approval", "", bankToken);

        assertThat(response.statusCode()).isEqualTo(201);
        String body = response.body();
        assertThat((String) json(body, "$.state")).isEqualTo("APPROVED");
        List<String> reserved = json(body, "$.reservations[*].unit.id");
        assertThat(reserved).containsExactlyInAnyOrder(soonest.toString(), middle.toString()).doesNotContain(later.toString());
        List<String> statuses = json(body, "$.reservations[*].status");
        assertThat(statuses).containsOnly("ACTIVE");
        Instant holdEnds = Instant.parse(json(body, "$.reservations[0].holdExpiresAt"));
        assertThat(holdEnds).as("emergency hold is about one hour")
            .isBetween(Instant.now().plus(Duration.ofMinutes(55)), Instant.now().plus(Duration.ofMinutes(65)));
        List<String> events = json(body, "$.history[*].event");
        assertThat(events).containsExactly("CREATED", "APPROVED");
        assertThat(bank.activeHolds(soonest)).isEqualTo(1);
    }

    @Test
    void reservedUnitsShowAsReservedAndLeaveAvailability() throws Exception {
        bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");

        postJson("/requests/" + id + "/approval", "", bankToken);

        List<String> states = json(get("/units", bankToken).body(), "$.items[*].state");
        assertThat(states).containsExactly("RESERVED");
        assertThat((Integer) json(get("/availability?bankId=" + bank.id(), bankToken).body(), "$.items.length()")).isZero();
    }

    @Test
    void approvalIsAllOrNothing() throws Exception {
        UUID onlyUnit = bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 2, "URGENT");

        HttpResponse<String> response = postJson("/requests/" + id + "/approval", "", bankToken);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("UNITS_NO_LONGER_AVAILABLE");
        assertThat(bank.activeHolds(onlyUnit)).as("nothing reserved").isZero();
        assertThat((String) json(get("/requests/" + id, bankToken).body(), "$.state")).isEqualTo("PENDING");
    }

    @Test
    void anExpiredUnitIsNeverReserved() throws Exception {
        UUID expired = bank.addUnit("O", "NEGATIVE", "RED_CELLS", "AVAILABLE", -1);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "EMERGENCY");

        HttpResponse<String> response = postJson("/requests/" + id + "/approval", "", bankToken);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(bank.activeHolds(expired)).isZero();
    }

    @Test
    void matchingIsExactNoSubstitution() throws Exception {
        bank.addUnit("O", "POSITIVE", "RED_CELLS", "AVAILABLE", 10);
        bank.addUnit("O", "NEGATIVE", "WHOLE_BLOOD", "AVAILABLE", 10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "EMERGENCY");

        assertThat(postJson("/requests/" + id + "/approval", "", bankToken).statusCode()).isEqualTo(409);
    }

    @Test
    void aUnitWhoseHoldLapsedCanBeReservedForAnotherRequestAndTheFirstRequestShowsLapsed() throws Exception {
        UUID unit = bank.addUnit(10);
        String first = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");
        postJson("/requests/" + first + "/approval", "", bankToken);
        // Make the first hold end in the past, as if four hours had gone by and no job had run.
        jdbc.sql("""
                UPDATE reservation SET created_at = now() - interval '5 hours', hold_expires_at = now() - interval '1 minute'
                 WHERE request_id = CAST(? AS uuid)""").param(first).update();
        String second = createRequest(IKORODU_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "EMERGENCY");

        HttpResponse<String> response = postJson("/requests/" + second + "/approval", "", bankToken);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(bank.activeHolds(unit)).isEqualTo(1);
        String firstNow = get("/requests/" + first, bankToken).body();
        assertThat((String) json(firstNow, "$.state")).isEqualTo("LAPSED");
        assertThat((String) json(firstNow, "$.reservations[0].status")).isEqualTo("LAPSED");
        List<String> events = json(firstNow, "$.history[*].event");
        assertThat(events).contains("HOLD_LAPSED");
    }

    @Test
    void aLapsedHoldShowsAsLapsedBeforeAnythingReleasesIt() throws Exception {
        bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");
        postJson("/requests/" + id + "/approval", "", bankToken);
        jdbc.sql("""
                UPDATE reservation SET created_at = now() - interval '5 hours', hold_expires_at = now() - interval '1 minute'
                 WHERE request_id = CAST(? AS uuid)""").param(id).update();

        String body = get("/requests/" + id, signIn(YABA_HOSPITAL_STAFF)).body();

        assertThat((String) json(body, "$.state")).isEqualTo("LAPSED");
        assertThat((String) json(body, "$.reservations[0].status")).isEqualTo("LAPSED");
    }

    @Test
    void approvingTwiceIsRefused() throws Exception {
        bank.addUnit(10);
        bank.addUnit(11);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");
        postJson("/requests/" + id + "/approval", "", bankToken);

        HttpResponse<String> again = postJson("/requests/" + id + "/approval", "", bankToken);

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat((String) json(again.body(), "$.code")).isEqualTo("REQUEST_NOT_PENDING");
    }

    @Test
    void anotherBanksStaffCannotDecideAndAreToldItDoesNotExist() throws Exception {
        bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");

        HttpResponse<String> approve = postJson("/requests/" + id + "/approval", "", signIn(LAGOS_BANK_STAFF));
        HttpResponse<String> decline = postJson("/requests/" + id + "/declination", "{\"reason\":\"Not ours\"}",
            signIn(LAGOS_BANK_STAFF));

        assertThat(approve.statusCode()).isEqualTo(404);
        assertThat(decline.statusCode()).isEqualTo(404);
        assertThat((String) json(get("/requests/" + id, bankToken).body(), "$.state")).isEqualTo("PENDING");
    }

    @Test
    void theAdministratorCanApprove() throws Exception {
        bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");

        assertThat(postJson("/requests/" + id + "/approval", "", signIn(ADMIN)).statusCode()).isEqualTo(201);
    }

    @Test
    void declineRecordsTheReasonForTheHospitalToSee() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");

        HttpResponse<String> response = postJson("/requests/" + id + "/declination",
            "{\"reason\":\"No O-negative red cells until Thursday.\"}", bankToken);

        assertThat(response.statusCode()).isEqualTo(201);
        String seenByHospital = get("/requests/" + id, signIn(YABA_HOSPITAL_STAFF)).body();
        assertThat((String) json(seenByHospital, "$.state")).isEqualTo("DECLINED");
        assertThat((String) json(seenByHospital, "$.declineReason")).isEqualTo("No O-negative red cells until Thursday.");
        List<String> events = json(seenByHospital, "$.history[*].event");
        assertThat(events).containsExactly("CREATED", "DECLINED");
    }

    @Test
    void declineNeedsAReasonAndOnlyWhilePending() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "ROUTINE");

        HttpResponse<String> noReason = postJson("/requests/" + id + "/declination", "{\"reason\":\"\"}", bankToken);
        postJson("/requests/" + id + "/declination", "{\"reason\":\"No stock\"}", bankToken);
        HttpResponse<String> again = postJson("/requests/" + id + "/declination", "{\"reason\":\"Still no stock\"}", bankToken);

        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat((String) json(again.body(), "$.code")).isEqualTo("REQUEST_NOT_PENDING");
    }
}
