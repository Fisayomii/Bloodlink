package africa.tesa.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two people act on one approved request at the same instant: the bank issues it while the hospital cancels it.
 * Exactly one wins, the other gets a clear 409, and the request, the unit and the hold agree with the winner.
 * Both paths lock the request row first (SELECT ... FOR UPDATE), so they run one after the other.
 */
class IssueOrCancelRaceIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    @RepeatedTest(5)
    void anIssueAndACancellationAtTheSameMomentHaveOneWinner() throws Exception {
        TestBank bank = TestBank.create(jdbc);
        String bankToken = signIn(bank.staffEmail());
        String hospitalToken = signIn(YABA_HOSPITAL_STAFF);
        UUID unit = bank.addUnit(10);
        String id = createRequest(YABA_HOSPITAL_STAFF, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT");
        assertThat(postJson("/requests/" + id + "/approval", "", bankToken).statusCode()).isEqualTo(201);

        CountDownLatch start = new CountDownLatch(1);
        HttpResponse<String> issue;
        HttpResponse<String> cancel;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> issuing = pool.submit(() -> {
                start.await();
                return postJson("/requests/" + id + "/issue", "", bankToken);
            });
            Future<HttpResponse<String>> cancelling = pool.submit(() -> {
                start.await();
                return postJson("/requests/" + id + "/cancellation", "{\"reason\":\"No longer needed.\"}", hospitalToken);
            });
            start.countDown();
            issue = issuing.get(30, TimeUnit.SECONDS);
            cancel = cancelling.get(30, TimeUnit.SECONDS);
        }

        assertThat(List.of(issue.statusCode(), cancel.statusCode())).as("one winner, one refusal")
            .containsExactlyInAnyOrder(201, 409);
        String requestState = jdbc.sql("SELECT state FROM blood_request WHERE id = CAST(? AS uuid)").param(id)
            .query(String.class).single();
        String unitState = jdbc.sql("SELECT state FROM unit WHERE id = ?").param(unit).query(String.class).single();
        if (issue.statusCode() == 201) {
            assertThat((String) json(cancel.body(), "$.code")).isEqualTo("REQUEST_CLOSED");
            assertThat(requestState).isEqualTo("FULFILLED");
            assertThat(unitState).isEqualTo("ISSUED");
        } else {
            assertThat((String) json(issue.body(), "$.code")).isEqualTo("REQUEST_NOT_APPROVED");
            assertThat(requestState).isEqualTo("CANCELLED");
            assertThat(unitState).isEqualTo("AVAILABLE");
        }
        assertThat(bank.activeHolds(unit)).as("no hold left either way").isZero();
    }
}
