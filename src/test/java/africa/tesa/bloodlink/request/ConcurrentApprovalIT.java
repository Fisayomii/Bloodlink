package africa.tesa.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
 * THE key rule, from the brief: "Three hospitals requesting the last available unit of a group in the same second
 * produce exactly one reservation, demonstrated by an automated test, and the test fails when the protection is
 * removed."
 *
 * <p>Each round: a bank with exactly one O-negative red-cell unit; Yaba, Ikorodu and Victoria Island each request it;
 * then the bank's three approvals are released at the same instant by a latch, through the real HTTP service.
 * Exactly one must win (201), the other two must get a clear 409 UNITS_NO_LONGER_AVAILABLE with their request still
 * PENDING, and the unit must have exactly one ACTIVE reservation. Repeated five times, because a race that passes once
 * by luck proves little.
 *
 * <p>Evidence that it fails without protection: docs/evidence.md.
 */
class ConcurrentApprovalIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    @RepeatedTest(5)
    void threeHospitalsRaceForTheLastUnitAndExactlyOneWins() throws Exception {
        TestBank bank = TestBank.create(jdbc);
        UUID lastUnit = bank.addUnit(30);
        List<String> requestIds = new ArrayList<>();
        for (String hospitalStaff : List.of(YABA_HOSPITAL_STAFF, IKORODU_HOSPITAL_STAFF, VI_HOSPITAL_STAFF)) {
            requestIds.add(createRequest(hospitalStaff, bank.id(), "O", "NEGATIVE", "RED_CELLS", 1, "EMERGENCY"));
        }
        String bankToken = signIn(bank.staffEmail());

        CountDownLatch start = new CountDownLatch(1);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (String requestId : requestIds) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return postJson("/requests/" + requestId + "/approval", "", bankToken);
                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> call : calls) {
                responses.add(call.get(30, TimeUnit.SECONDS));
            }
        }

        List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();
        assertThat(statuses).as("one winner, two clear refusals").containsExactlyInAnyOrder(201, 409, 409);
        assertThat(bank.activeHolds(lastUnit)).as("active reservations on the last unit").isEqualTo(1);
        for (HttpResponse<String> response : responses) {
            if (response.statusCode() == 409) {
                assertThat((String) json(response.body(), "$.code")).isEqualTo("UNITS_NO_LONGER_AVAILABLE");
            }
        }
        List<String> states = jdbc.sql("SELECT state FROM blood_request WHERE id IN (:ids)")
            .param("ids", requestIds.stream().map(UUID::fromString).toList())
            .query(String.class).list();
        assertThat(states).as("the losers' requests stay pending").containsExactlyInAnyOrder("APPROVED", "PENDING", "PENDING");
    }
}
