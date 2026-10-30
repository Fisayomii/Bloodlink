package africa.tesa.bloodlink.donation;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import africa.tesa.bloodlink.support.TestDonors;
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
 * The brief: "A donation slot cannot be booked beyond its capacity, including when two donors book the last place in the
 * same second." Donors book through the real HTTP service, released at the same instant. Exactly as many succeed as
 * there are places; the rest get 409 SLOT_FULL, and the database never holds more active bookings than the capacity.
 *
 * <p>Protection: the slot row lock in DonationService.book and the place index booking_one_active_per_place_idx.
 * Remove both and this test fails (docs/evidence.md).
 */
class ConcurrentBookingIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    @RepeatedTest(5)
    void twoDonorsBookingTheLastPlaceInTheSameSecondGetOnePlace() throws Exception {
        TestBank bank = TestBank.create(jdbc);
        UUID slot = bank.addSlot(24 * 60, 3);
        List<String> donors = TestDonors.create(jdbc, 4);
        // Two of the three places are taken first, one after the other.
        for (String donor : donors.subList(0, 2)) {
            assertThat(postJson("/donation-slots/" + slot + "/bookings", "", signIn(donor)).statusCode()).isEqualTo(201);
        }

        List<HttpResponse<String>> responses = bookAtTheSameInstant(slot, donors.subList(2, 4));

        assertThat(statuses(responses)).as("one gets the last place, one is told it is full")
            .containsExactlyInAnyOrder(201, 409);
        assertThat(codesOfRefusals(responses)).containsExactly("SLOT_FULL");
        assertThat(bank.activeBookings(slot)).as("never more than the capacity").isEqualTo(3);
    }

    @RepeatedTest(5)
    void tenDonorsForThreePlacesGetExactlyThree() throws Exception {
        TestBank bank = TestBank.create(jdbc);
        UUID slot = bank.addSlot(24 * 60, 3);

        List<HttpResponse<String>> responses = bookAtTheSameInstant(slot, TestDonors.create(jdbc, 10));

        assertThat(statuses(responses)).filteredOn(status -> status == 201).hasSize(3);
        assertThat(codesOfRefusals(responses)).hasSize(7).containsOnly("SLOT_FULL");
        assertThat(bank.activeBookings(slot)).isEqualTo(3);
        Integer distinctPlaces = jdbc.sql("""
                SELECT count(DISTINCT place_no) FROM booking WHERE slot_id = ? AND status IN ('BOOKED', 'ATTENDED')""")
            .param(slot).query(Integer.class).single();
        assertThat(distinctPlaces).as("each place held once").isEqualTo(3);
    }

    /** Signs every donor in first, then releases all their bookings at the same instant. */
    private List<HttpResponse<String>> bookAtTheSameInstant(UUID slot, List<String> donors) throws Exception {
        List<String> tokens = new ArrayList<>();
        for (String donor : donors) {
            tokens.add(signIn(donor));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> bookings = new ArrayList<>();
            for (String token : tokens) {
                bookings.add(pool.submit(() -> {
                    start.await();
                    return postJson("/donation-slots/" + slot + "/bookings", "", token);
                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> booking : bookings) {
                responses.add(booking.get(30, TimeUnit.SECONDS));
            }
        }
        return responses;
    }

    private static List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).toList();
    }

    private static List<String> codesOfRefusals(List<HttpResponse<String>> responses) {
        return responses.stream().filter(response -> response.statusCode() == 409)
            .map(response -> (String) json(response.body(), "$.code")).toList();
    }
}
