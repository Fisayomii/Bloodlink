package africa.tesa.bloodlink.request;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** POST /requests (including safe repeat submission), GET /requests and GET /requests/{id}. */
class RequestsIT extends AbstractPostgresIT {

    static final UUID LAGOS_BANK = UUID.fromString("0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b");
    static final UUID YABA_HOSPITAL = UUID.fromString("6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b");

    @Autowired
    JdbcClient jdbc;

    @Test
    void aHospitalRequestsUnitsAndTheRequestStartsPending() throws Exception {
        HttpResponse<String> response = submit(YABA_HOSPITAL_STAFF, UUID.randomUUID(),
            requestJson(LAGOS_BANK, "O", "NEGATIVE", "RED_CELLS", 2, "URGENT"));

        assertThat(response.statusCode()).isEqualTo(201);
        String body = response.body();
        assertThat((String) json(body, "$.state")).isEqualTo("PENDING");
        assertThat((String) json(body, "$.reference")).matches("^REQ-\\d{4}-\\d{6}$");
        assertThat((String) json(body, "$.hospital.id")).isEqualTo(YABA_HOSPITAL.toString());
        assertThat((String) json(body, "$.bank.id")).isEqualTo(LAGOS_BANK.toString());
        assertThat((Integer) json(body, "$.reservations.length()")).as("creating a request reserves nothing").isZero();
        List<String> events = json(body, "$.history[*].event");
        assertThat(events).containsExactly("CREATED");
        assertThat(response.headers().firstValue("Location")).contains("/requests/" + json(body, "$.id"));
    }

    @Test
    void submittingTwiceWithTheSameKeyRecordsOneRequest() throws Exception {
        UUID key = UUID.randomUUID();
        String body = requestJson(LAGOS_BANK, "A", "POSITIVE", "PLASMA", 1, "ROUTINE");

        HttpResponse<String> first = submit(YABA_HOSPITAL_STAFF, key, body);
        HttpResponse<String> retry = submit(YABA_HOSPITAL_STAFF, key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat((String) json(retry.body(), "$.id")).isEqualTo(json(first.body(), "$.id"));
        assertThat(jdbc.sql("SELECT count(*) FROM blood_request WHERE idempotency_key = ?").param(key)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefused() throws Exception {
        UUID key = UUID.randomUUID();
        submit(YABA_HOSPITAL_STAFF, key, requestJson(LAGOS_BANK, "A", "POSITIVE", "PLASMA", 1, "ROUTINE"));

        HttpResponse<String> different = submit(YABA_HOSPITAL_STAFF, key,
            requestJson(LAGOS_BANK, "A", "POSITIVE", "PLASMA", 3, "ROUTINE"));

        assertThat(different.statusCode()).isEqualTo(422);
        assertThat((String) json(different.body(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void theIdempotencyKeyIsRequired() throws Exception {
        HttpResponse<String> response = postJson("/requests", requestJson(LAGOS_BANK, "O", "NEGATIVE", "RED_CELLS", 1, "URGENT"),
            signIn(YABA_HOSPITAL_STAFF));

        assertThat(response.statusCode()).isEqualTo(422);
        List<String> fields = json(response.body(), "$.details[*].field");
        assertThat(fields).contains("Idempotency-Key");
    }

    @Test
    void aPatientNameIsRefused() throws Exception {
        String withPatient = requestJson(LAGOS_BANK, "O", "NEGATIVE", "RED_CELLS", 1, "URGENT")
            .replace("{", "{\"patientName\":\"Ada Obi\",");

        UUID key = UUID.randomUUID();

        HttpResponse<String> response = submit(YABA_HOSPITAL_STAFF, key, withPatient);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM blood_request WHERE idempotency_key = ?").param(key)
            .query(Integer.class).single()).as("nothing was recorded").isZero();
    }

    @Test
    void theBankMustBeABloodBank() throws Exception {
        HttpResponse<String> toAHospital = submit(YABA_HOSPITAL_STAFF, UUID.randomUUID(),
            requestJson(YABA_HOSPITAL, "O", "NEGATIVE", "RED_CELLS", 1, "URGENT"));
        HttpResponse<String> toNowhere = submit(YABA_HOSPITAL_STAFF, UUID.randomUUID(),
            requestJson(UUID.randomUUID(), "O", "NEGATIVE", "RED_CELLS", 1, "URGENT"));

        assertThat(toAHospital.statusCode()).isEqualTo(404);
        assertThat(toNowhere.statusCode()).isEqualTo(404);
    }

    @Test
    void neededByMustBeInTheFuture() throws Exception {
        String past = requestJson(LAGOS_BANK, "O", "NEGATIVE", "RED_CELLS", 1, "URGENT")
            .replaceAll("\"neededBy\":\"[^\"]+\"", "\"neededBy\":\"" + Instant.now().minusSeconds(60) + "\"");

        HttpResponse<String> response = submit(YABA_HOSPITAL_STAFF, UUID.randomUUID(), past);

        assertThat(response.statusCode()).isEqualTo(422);
        List<String> fields = json(response.body(), "$.details[*].field");
        assertThat(fields).contains("neededBy");
    }

    @Test
    void neededByAfter2100IsRefused() throws Exception {
        String farAway = requestJson(LAGOS_BANK, "O", "NEGATIVE", "RED_CELLS", 1, "URGENT")
            .replaceAll("\"neededBy\":\"[^\"]+\"", "\"neededBy\":\"2101-01-01T09:00:00Z\"");

        HttpResponse<String> response = submit(YABA_HOSPITAL_STAFF, UUID.randomUUID(), farAway);

        assertThat(response.statusCode()).isEqualTo(422);
        List<String> fields = json(response.body(), "$.details[*].field");
        assertThat(fields).contains("neededBy");
    }

    @Test
    void quantityMustBeBetween1And20() throws Exception {
        HttpResponse<String> zero = submit(YABA_HOSPITAL_STAFF, UUID.randomUUID(),
            requestJson(LAGOS_BANK, "O", "NEGATIVE", "RED_CELLS", 0, "URGENT"));
        HttpResponse<String> tooMany = submit(YABA_HOSPITAL_STAFF, UUID.randomUUID(),
            requestJson(LAGOS_BANK, "O", "NEGATIVE", "RED_CELLS", 21, "URGENT"));

        assertThat(zero.statusCode()).isEqualTo(422);
        assertThat(tooMany.statusCode()).isEqualTo(422);
    }

    @Test
    void onlyTheRequestingHospitalTheAddressedBankAndTheAdministratorCanSeeARequest() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, LAGOS_BANK, "B", "POSITIVE", "PLATELETS", 1, "ROUTINE");

        assertThat(get("/requests/" + id, signIn(YABA_HOSPITAL_STAFF)).statusCode()).isEqualTo(200);
        assertThat(get("/requests/" + id, signIn(LAGOS_BANK_STAFF)).statusCode()).isEqualTo(200);
        assertThat(get("/requests/" + id, signIn(ADMIN)).statusCode()).isEqualTo(200);
        assertThat(get("/requests/" + id, signIn(IKORODU_HOSPITAL_STAFF)).statusCode()).as("another hospital").isEqualTo(404);
        assertThat(get("/requests/" + id, signIn(LEKKI_BANK_STAFF)).statusCode()).as("another bank").isEqualTo(404);
        assertThat(get("/requests/" + id, signIn(DONOR)).statusCode()).as("a donor").isEqualTo(404);
    }

    @Test
    void listsShowEachSideOnlyItsOwnRequests() throws Exception {
        String id = createRequest(YABA_HOSPITAL_STAFF, LAGOS_BANK, "AB", "POSITIVE", "PLASMA", 1, "ROUTINE");

        List<String> yabaSees = json(get("/requests?size=100", signIn(YABA_HOSPITAL_STAFF)).body(), "$.items[*].hospital.id");
        List<String> lagosSees = json(get("/requests?size=100", signIn(LAGOS_BANK_STAFF)).body(), "$.items[*].bank.id");
        List<String> ikoroduSees = json(get("/requests?size=100", signIn(IKORODU_HOSPITAL_STAFF)).body(), "$.items[*].id");

        assertThat(yabaSees).isNotEmpty().containsOnly(YABA_HOSPITAL.toString());
        assertThat(lagosSees).isNotEmpty().containsOnly(LAGOS_BANK.toString());
        assertThat(ikoroduSees).doesNotContain(id);
    }

    @Test
    void anUnknownStateFilterIsMalformed() throws Exception {
        assertThat(get("/requests?state=MAYBE", signIn(YABA_HOSPITAL_STAFF)).statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> submit(String account, UUID key, String body) throws Exception {
        return send(request("/requests")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + signIn(account))
            .header("Idempotency-Key", key.toString())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
    }
}
