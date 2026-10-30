package africa.tesa.bloodlink.donation;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import africa.tesa.bloodlink.support.TestBank;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** POST and GET /donation-slots: bank staff open slots at their own bank; anyone signed in sees places left. */
class DonationSlotsIT extends AbstractPostgresIT {

    private static final ZoneOffset LAGOS = ZoneOffset.ofHours(1);

    @Autowired
    JdbcClient jdbc;

    private TestBank bank;
    private String staffToken;

    @BeforeEach
    void aBankOfItsOwn() throws Exception {
        bank = TestBank.create(jdbc);
        staffToken = signIn(bank.staffEmail());
    }

    @Test
    void bankStaffOpenASlotAtTheirOwnBankAndAnyoneSeesItsPlacesLeft() throws Exception {
        HttpResponse<String> created = open(UUID.randomUUID(), slotJson(2, 1, 4));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat((String) json(created.body(), "$.bank.id")).isEqualTo(bank.id().toString());
        assertThat((Integer) json(created.body(), "$.placesLeft")).isEqualTo(4);

        HttpResponse<String> listed = get("/donation-slots?bankId=" + bank.id(), signIn(DONOR));
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat((List<String>) json(listed.body(), "$.items[*].id")).containsExactly((String) json(created.body(), "$.id"));
        assertThat((String) json(listed.body(), "$.computedAt")).isNotBlank();
    }

    @Test
    void openingTwiceWithTheSameKeyOpensOneSlot() throws Exception {
        UUID key = UUID.randomUUID();
        String body = slotJson(3, 2, 5);

        HttpResponse<String> first = open(key, body);
        HttpResponse<String> again = open(key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat((String) json(again.body(), "$.id")).isEqualTo(json(first.body(), "$.id"));
        assertThat(jdbc.sql("SELECT count(*) FROM donation_slot WHERE bank_id = ?").param(bank.id()).query(Integer.class).single())
            .isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentSlotIsRefused() throws Exception {
        UUID key = UUID.randomUUID();
        open(key, slotJson(3, 2, 5));

        HttpResponse<String> different = open(key, slotJson(3, 2, 6));

        assertThat(different.statusCode()).isEqualTo(422);
        assertThat((String) json(different.body(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aSlotStartsInTheFutureAndLastsAtMostEightHours() throws Exception {
        HttpResponse<String> past = open(UUID.randomUUID(), slotJson(-1, 1, 4));
        HttpResponse<String> tooLong = open(UUID.randomUUID(), slotJson(2, 9, 4));

        assertThat(past.statusCode()).isEqualTo(422);
        assertThat((String) json(past.body(), "$.details[0].field")).isEqualTo("startsAt");
        assertThat(tooLong.statusCode()).isEqualTo(422);
        assertThat((String) json(tooLong.body(), "$.details[0].field")).isEqualTo("endsAt");
    }

    @Test
    void theListShowsTheNextFourteenDaysUnlessAskedOtherwise() throws Exception {
        UUID soon = bank.addSlot(24 * 60, 4);
        UUID later = bank.addSlot(20 * 24 * 60, 4);
        String token = signIn(DONOR);

        List<String> byDefault = json(get("/donation-slots?bankId=" + bank.id(), token).body(), "$.items[*].id");
        LocalDate today = LocalDate.now(LAGOS);
        List<String> wider = json(get("/donation-slots?bankId=" + bank.id() + "&from=" + today + "&to=" + today.plusDays(25),
            token).body(), "$.items[*].id");

        assertThat(byDefault).containsExactly(soon.toString());
        assertThat(wider).containsExactly(soon.toString(), later.toString());
    }

    @Test
    void anEndBeforeTheStartMoreThan31DaysOrAFarDateIsA400() throws Exception {
        LocalDate today = LocalDate.now(LAGOS);
        String token = signIn(DONOR);

        assertThat(get("/donation-slots?from=" + today + "&to=" + today.minusDays(1), token).statusCode()).isEqualTo(400);
        assertThat(get("/donation-slots?from=" + today + "&to=" + today.plusDays(32), token).statusCode()).isEqualTo(400);
        assertThat(get("/donation-slots?from=1999-12-25", token).statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> open(UUID key, String body) throws Exception {
        return send(request("/donation-slots")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + staffToken)
            .header("Idempotency-Key", key.toString())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
    }

    /** A slot starting in the given number of days (Lagos time, on the hour), lasting the given hours. */
    private static String slotJson(int startsInDays, int hours, int capacity) {
        OffsetDateTime start = OffsetDateTime.now(LAGOS).plusDays(startsInDays).truncatedTo(ChronoUnit.HOURS);
        return """
            {"startsAt":"%s","endsAt":"%s","capacity":%d}""".formatted(start, start.plusHours(hours), capacity);
    }
}
