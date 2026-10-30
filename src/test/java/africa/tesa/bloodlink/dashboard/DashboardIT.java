package africa.tesa.bloodlink.dashboard;

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
 * Brief: "Dashboard figures match the underlying records and show when they were computed." Each test uses a bank of
 * its own, so every figure can be compared exactly with what the test put in the database.
 */
class DashboardIT extends AbstractPostgresIT {

    private static final String LAGOS_CENTRAL = "0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b";

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
    void stockFiguresMatchTheRecordsAndSayWhenTheyWereComputed() throws Exception {
        bank.addUnit("O", "NEGATIVE", "RED_CELLS", "AVAILABLE", 10);
        bank.addUnit("O", "NEGATIVE", "PLASMA", "AVAILABLE", 10);
        UUID soon = bank.addUnit("A", "POSITIVE", "PLATELETS", "AVAILABLE", 2);
        UUID expired = bank.addUnit("B", "NEGATIVE", "RED_CELLS", "AVAILABLE", 10);
        jdbc.sql("UPDATE unit SET expiry_date = current_date - 1 WHERE id = ?").param(expired).update();

        String body = dashboard(bankToken, "");

        assertThat((String) json(body, "$.computedAt")).isNotBlank();
        assertThat((String) json(body, "$.bank.id")).isEqualTo(bank.id().toString());
        assertThat((Integer) json(body, "$.lowStockThreshold")).isEqualTo(3);
        assertThat((Integer) json(body, "$.holdsEndingWithinMinutes")).isEqualTo(60);
        assertThat((Integer) json(body, "$.expiringWithinDays")).isEqualTo(3);
        assertThat((List<Object>) json(body, "$.availableByGroup")).hasSize(8);
        assertThat(available(body, "O", "NEGATIVE")).as("both components counted").isEqualTo(2);
        assertThat(available(body, "A", "POSITIVE")).isEqualTo(1);
        assertThat(available(body, "B", "NEGATIVE")).as("an expired unit is never available").isZero();
        assertThat(available(body, "AB", "POSITIVE")).as("groups with none are listed too").isZero();
        assertThat((List<Object>) json(body, "$.lowGroups")).as("every group is below 3 here").hasSize(8);
        assertThat((List<String>) json(body, "$.unitsExpiringSoon[*].id")).containsExactly(soon.toString());

        int inDatabase = jdbc.sql("SELECT count(*) FROM unit_status WHERE bank_id = ? AND effective_state = 'AVAILABLE'")
            .param(bank.id()).query(Integer.class).single();
        List<Integer> shown = json(body, "$.availableByGroup[*].availableUnits");
        assertThat(shown.stream().mapToInt(Integer::intValue).sum()).as("the figures add up to the records").isEqualTo(inDatabase);
    }

    @Test
    void openRequestsAndHoldsEndingSoonComeFromTheRequests() throws Exception {
        bank.addUnit(10);
        String approved = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "EMERGENCY");
        createRequest(IKORODU_HOSPITAL_STAFF, bank.id(), "A", "POSITIVE", "RED_CELLS", 1, "ROUTINE");
        assertThat(postJson("/requests/" + approved + "/approval", "", bankToken).statusCode()).isEqualTo(201);

        String body = dashboard(bankToken, "");

        assertThat((Integer) json(body, "$.openRequests.pending")).isEqualTo(1);
        assertThat((Integer) json(body, "$.openRequests.approved")).isEqualTo(1);
        // An emergency hold lasts 1 hour, so it is inside the 60-minute window.
        assertThat((List<String>) json(body, "$.holdsEndingSoon[*].requestId")).containsExactly(approved);
        assertThat((String) json(body, "$.holdsEndingSoon[0].hospital.name")).isEqualTo("Yaba General Hospital (demo)");
    }

    @Test
    void recentActivityIsThisBanksNewestFirst() throws Exception {
        bank.addUnit(10);
        String request = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");
        postJson("/requests/" + request + "/approval", "", bankToken);
        String reference = json(get("/requests/" + request, bankToken).body(), "$.reference");

        String body = dashboard(bankToken, "");

        List<String> references = json(body, "$.recentActivity[*].reference");
        assertThat(references).isNotEmpty();
        assertThat(references).allMatch(ref -> ref.equals(reference) || ref.startsWith("TB-"));
        assertThat((String) json(body, "$.recentActivity[0].event")).isIn("APPROVED", "RESERVED");
        assertThat((String) json(body, "$.recentActivity[0].actor")).startsWith("Test staff");
    }

    @Test
    void bankStaffAlwaysSeeTheirOwnBankEvenIfTheyAskForAnother() throws Exception {
        String body = dashboard(bankToken, "?bankId=" + LAGOS_CENTRAL);

        assertThat((String) json(body, "$.bank.id")).isEqualTo(bank.id().toString());
    }

    @Test
    void theAdministratorSeesEveryBankOrOne() throws Exception {
        String token = signIn(ADMIN);

        String all = dashboard(token, "");
        String one = dashboard(token, "?bankId=" + bank.id());

        assertThat((Object) json(all, "$.bank")).as("every bank").isNull();
        assertThat((String) json(one, "$.bank.id")).isEqualTo(bank.id().toString());
    }

    @Test
    void anUnknownBankIsA400ForTheAdministrator() throws Exception {
        HttpResponse<String> response = get("/dashboard?bankId=" + UUID.randomUUID(), signIn(ADMIN));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    private String dashboard(String token, String query) throws Exception {
        HttpResponse<String> response = get("/dashboard" + query, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private static int available(String body, String group, String rhesus) {
        List<Integer> count = json(body, "$.availableByGroup[?(@.bloodGroup == '" + group + "' && @.rhesus == '" + rhesus
            + "')].availableUnits");
        return count.get(0);
    }
}
