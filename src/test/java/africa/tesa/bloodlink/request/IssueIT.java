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
 * POST /requests/{id}/issue and /issue/reversal. The brief's rules: an expired unit cannot be issued; an issue recorded
 * in error can be reversed; the unit returns to available only if it has not expired; both entries stay in the history.
 */
class IssueIT extends AbstractPostgresIT {

    private static final String REASON = "{\"reason\":\"Issued in error; the units never left the bank.\"}";

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
    void issuingAnApprovedRequestFulfilsItAndMarksTheUnitIssued() throws Exception {
        UUID unit = bank.addUnit(10);
        String id = approvedRequest();

        HttpResponse<String> response = postJson("/requests/" + id + "/issue", "", bankToken);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) json(response.body(), "$.state")).isEqualTo("FULFILLED");
        assertThat((String) json(response.body(), "$.reservations[0].status")).isEqualTo("ISSUED");
        List<String> requestEvents = json(response.body(), "$.history[*].event");
        assertThat(requestEvents).containsExactly("CREATED", "APPROVED", "ISSUED");
        String unitNow = get("/units/" + unit, bankToken).body();
        assertThat((String) json(unitNow, "$.state")).isEqualTo("ISSUED");
        List<String> unitEvents = json(unitNow, "$.history[*].event");
        assertThat(unitEvents).containsExactly("RESERVED", "ISSUED");
        assertThat(bank.activeHolds(unit)).isZero();
    }

    @Test
    void aPendingRequestCannotBeIssued() throws Exception {
        bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");

        assertRefused(postJson("/requests/" + id + "/issue", "", bankToken), "REQUEST_NOT_APPROVED");
    }

    @Test
    void aRequestWhoseHoldLapsedCannotBeIssued() throws Exception {
        UUID unit = bank.addUnit(10);
        String id = approvedRequest();
        holdEndedAMinuteAgo(id);

        assertRefused(postJson("/requests/" + id + "/issue", "", bankToken), "REQUEST_NOT_APPROVED");

        assertThat(storedState(unit)).as("nothing issued").isEqualTo("RESERVED");
        assertThat((String) json(get("/requests/" + id, bankToken).body(), "$.state")).isEqualTo("LAPSED");
    }

    @Test
    void anExpiredUnitCanNeverBeIssued() throws Exception {
        UUID unit = bank.addUnit(10);
        String id = approvedRequest();
        expiredYesterday(unit);

        assertRefused(postJson("/requests/" + id + "/issue", "", bankToken), "REQUEST_NOT_APPROVED");

        assertThat(storedState(unit)).isNotEqualTo("ISSUED");
        assertThat((String) json(get("/requests/" + id, bankToken).body(), "$.state")).isEqualTo("UNIT_EXPIRED");
    }

    @Test
    void issuingTwiceIsRefused() throws Exception {
        bank.addUnit(10);
        String id = approvedRequest();
        postJson("/requests/" + id + "/issue", "", bankToken);

        assertRefused(postJson("/requests/" + id + "/issue", "", bankToken), "REQUEST_NOT_APPROVED");
    }

    @Test
    void anotherBanksStaffCannotIssueAndAreToldItDoesNotExist() throws Exception {
        bank.addUnit(10);
        String id = approvedRequest();
        TestBank other = TestBank.create(jdbc);

        HttpResponse<String> response = postJson("/requests/" + id + "/issue", "", signIn(other.staffEmail()));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) json(get("/requests/" + id, bankToken).body(), "$.state")).isEqualTo("APPROVED");
    }

    @Test
    void reversingAnIssueReturnsAnUnexpiredUnitToStockAndKeepsBothEntries() throws Exception {
        UUID unit = bank.addUnit(10);
        String id = approvedRequest();
        postJson("/requests/" + id + "/issue", "", bankToken);

        HttpResponse<String> response = postJson("/requests/" + id + "/issue/reversal", REASON, bankToken);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) json(response.body(), "$.state")).as("back to the bank to decide").isEqualTo("PENDING");
        assertThat((String) json(response.body(), "$.reservations[0].status")).isEqualTo("REVERSED");
        List<String> requestEvents = json(response.body(), "$.history[*].event");
        assertThat(requestEvents).containsSubsequence("ISSUED", "ISSUE_REVERSED");
        String unitNow = get("/units/" + unit, bankToken).body();
        assertThat((String) json(unitNow, "$.state")).isEqualTo("AVAILABLE");
        List<String> unitEvents = json(unitNow, "$.history[*].event");
        assertThat(unitEvents).as("both entries stay visible").containsSubsequence("ISSUED", "ISSUE_REVERSED");

        // The request can be approved again, with the very same unit.
        HttpResponse<String> again = postJson("/requests/" + id + "/approval", "", bankToken);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(bank.activeHolds(unit)).isEqualTo(1);
    }

    @Test
    void aReversedIssueDoesNotReturnAnExpiredUnitToStock() throws Exception {
        UUID unit = bank.addUnit(10);
        String id = approvedRequest();
        postJson("/requests/" + id + "/issue", "", bankToken);
        expiredYesterday(unit);

        HttpResponse<String> response = postJson("/requests/" + id + "/issue/reversal", REASON, bankToken);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedState(unit)).isEqualTo("EXPIRED");
        assertThat((Integer) json(get("/availability?bankId=" + bank.id(), bankToken).body(), "$.items.length()")).isZero();
        List<String> notes = json(get("/units/" + unit, bankToken).body(), "$.history[?(@.event == 'ISSUE_REVERSED')].note");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).contains("not returned to stock");
    }

    @Test
    void thereIsNothingToReverseUntilUnitsAreIssued() throws Exception {
        bank.addUnit(10);
        String id = approvedRequest();

        assertRefused(postJson("/requests/" + id + "/issue/reversal", REASON, bankToken), "REQUEST_NOT_FULFILLED");
    }

    @Test
    void aReversalNeedsAReason() throws Exception {
        bank.addUnit(10);
        String id = approvedRequest();
        postJson("/requests/" + id + "/issue", "", bankToken);

        HttpResponse<String> response = postJson("/requests/" + id + "/issue/reversal", "{}", bankToken);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(storedState(id)).isEqualTo("FULFILLED");
    }

    @Test
    void aUnitsHistoryIsHiddenFromOtherBanksButVisibleToTheAdministrator() throws Exception {
        UUID unit = bank.addUnit(10);

        assertThat(get("/units/" + unit, bankToken).statusCode()).isEqualTo(200);
        assertThat(get("/units/" + unit, signIn(TestBank.create(jdbc).staffEmail())).statusCode()).isEqualTo(404);
        assertThat(get("/units/" + unit, signIn(ADMIN)).statusCode()).isEqualTo(200);
    }

    /** A request from Yaba for one O-negative red-cell unit, approved by this bank. */
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

    private void expiredYesterday(UUID unit) {
        jdbc.sql("UPDATE unit SET expiry_date = current_date - 1 WHERE id = ?").param(unit).update();
    }

    private String storedState(UUID unit) {
        return jdbc.sql("SELECT state FROM unit WHERE id = ?").param(unit).query(String.class).single();
    }

    private String storedState(String requestId) {
        return jdbc.sql("SELECT state FROM blood_request WHERE id = CAST(? AS uuid)").param(requestId).query(String.class).single();
    }

    private static void assertRefused(HttpResponse<String> response, String code) {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) json(response.body(), "$.code")).isEqualTo(code);
    }
}
