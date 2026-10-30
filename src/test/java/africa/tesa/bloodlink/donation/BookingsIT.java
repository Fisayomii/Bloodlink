package africa.tesa.bloodlink.donation;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import africa.tesa.bloodlink.support.TestDonors;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Booking a place, cancelling it, and recording attendance, each with the refusals the contract lists. */
class BookingsIT extends AbstractPostgresIT {

    private static final long TOMORROW = 24 * 60;

    @Autowired
    JdbcClient jdbc;

    private TestBank bank;
    private List<String> donors;

    @BeforeEach
    void aBankAndDonorsOfItsOwn() {
        bank = TestBank.create(jdbc);
        donors = TestDonors.create(jdbc, 3);
    }

    @Test
    void aDonorBooksAPlaceAndPlacesLeftGoesDown() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 4);

        HttpResponse<String> booked = book(slot, donors.get(0));

        assertThat(booked.statusCode()).isEqualTo(201);
        assertThat((String) json(booked.body(), "$.status")).isEqualTo("BOOKED");
        assertThat((Integer) json(booked.body(), "$.slot.booked")).isEqualTo(1);
        assertThat((Integer) json(booked.body(), "$.slot.placesLeft")).isEqualTo(3);
        assertThat((String) json(booked.body(), "$.donor.displayName")).startsWith("Test donor");
    }

    @Test
    void bookingTheSameSlotAgainRecordsOneBooking() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 4);
        book(slot, donors.get(0));

        assertRefused(book(slot, donors.get(0)), "ALREADY_BOOKED");
        assertThat(bank.activeBookings(slot)).isEqualTo(1);
    }

    @Test
    void aFullSlotRefusesTheNextDonor() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 1);
        book(slot, donors.get(0));

        assertRefused(book(slot, donors.get(1)), "SLOT_FULL");
    }

    @Test
    void aSlotThatHasStartedCannotBeBooked() throws Exception {
        UUID slot = bank.addSlot(-10, 4);

        assertRefused(book(slot, donors.get(0)), "SLOT_CLOSED");
    }

    @Test
    void cancellingFreesThePlaceForAnotherDonor() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 1);
        String id = json(book(slot, donors.get(0)).body(), "$.id");

        HttpResponse<String> cancelled = postJson("/bookings/" + id + "/cancellation", "", signIn(donors.get(0)));

        assertThat(cancelled.statusCode()).isEqualTo(201);
        assertThat((String) json(cancelled.body(), "$.status")).isEqualTo("CANCELLED");
        assertThat(book(slot, donors.get(1)).statusCode()).as("the freed place").isEqualTo(201);
    }

    @Test
    void onlyTheDonorWhoBookedCanCancelAndOthersAreToldItDoesNotExist() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 4);
        String id = json(book(slot, donors.get(0)).body(), "$.id");

        HttpResponse<String> byAnother = postJson("/bookings/" + id + "/cancellation", "", signIn(donors.get(1)));

        assertThat(byAnother.statusCode()).isEqualTo(404);
        assertThat(bank.activeBookings(slot)).isEqualTo(1);
    }

    @Test
    void cancellingTwiceIsRefused() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 4);
        String id = json(book(slot, donors.get(0)).body(), "$.id");
        String token = signIn(donors.get(0));
        postJson("/bookings/" + id + "/cancellation", "", token);

        assertRefused(postJson("/bookings/" + id + "/cancellation", "", token), "BOOKING_NOT_ACTIVE");
    }

    @Test
    void attendanceIsRecordedOnceTheSlotHasStartedAndOnlyOnce() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 4);
        String id = json(book(slot, donors.get(0)).body(), "$.id");
        String staff = signIn(bank.staffEmail());

        assertRefused(postJson("/bookings/" + id + "/attendance", "{\"outcome\":\"ATTENDED\"}", staff), "SLOT_NOT_STARTED");

        startedTenMinutesAgo(slot);
        HttpResponse<String> recorded = postJson("/bookings/" + id + "/attendance", "{\"outcome\":\"ATTENDED\"}", staff);
        assertThat(recorded.statusCode()).isEqualTo(201);
        assertThat((String) json(recorded.body(), "$.status")).isEqualTo("ATTENDED");

        assertRefused(postJson("/bookings/" + id + "/attendance", "{\"outcome\":\"NO_SHOW\"}", staff), "BOOKING_NOT_ACTIVE");
    }

    @Test
    void anotherBanksStaffCannotRecordAttendanceAndAreToldItDoesNotExist() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 4);
        String id = json(book(slot, donors.get(0)).body(), "$.id");
        startedTenMinutesAgo(slot);

        HttpResponse<String> response = postJson("/bookings/" + id + "/attendance", "{\"outcome\":\"NO_SHOW\"}",
            signIn(LAGOS_BANK_STAFF));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void donorsSeeTheirOwnBookingsAndBankStaffSeeTheirBanks() throws Exception {
        UUID slot = bank.addSlot(TOMORROW, 4);
        String mine = json(book(slot, donors.get(0)).body(), "$.id");
        String theirs = json(book(slot, donors.get(1)).body(), "$.id");

        List<String> forDonor = json(get("/bookings", signIn(donors.get(0))).body(), "$.items[*].id");
        List<String> forBank = json(get("/bookings?size=100", signIn(bank.staffEmail())).body(), "$.items[*].id");
        List<String> forOtherBank = json(get("/bookings?size=100", signIn(LAGOS_BANK_STAFF)).body(), "$.items[*].id");

        assertThat(forDonor).containsExactly(mine);
        assertThat(forBank).containsExactlyInAnyOrder(mine, theirs);
        assertThat(forOtherBank).doesNotContain(mine, theirs);
    }

    private HttpResponse<String> book(UUID slot, String donor) throws Exception {
        return postJson("/donation-slots/" + slot + "/bookings", "", signIn(donor));
    }

    private void startedTenMinutesAgo(UUID slot) {
        jdbc.sql("UPDATE donation_slot SET starts_at = now() - interval '10 minutes', ends_at = now() + interval '50 minutes'"
            + " WHERE id = ?").param(slot).update();
    }

    private static void assertRefused(HttpResponse<String> response, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat((String) json(response.body(), "$.code")).isEqualTo(code);
    }
}
