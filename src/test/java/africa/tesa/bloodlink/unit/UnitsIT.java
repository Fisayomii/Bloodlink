package africa.tesa.bloodlink.unit;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** POST /units and GET /units: recording units, and who sees which bank's units. */
class UnitsIT extends AbstractPostgresIT {

    static final String LAGOS_BANK = "0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6b";
    static final String LEKKI_BANK = "0d1f6a3e-2b4c-4e5f-8a9b-1c2d3e4f5a6c";
    static final String LAGOS_STAFF_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Autowired
    JdbcClient jdbc;

    @Test
    void bankStaffRecordAUnitAtTheirOwnBank() throws Exception {
        String reference = newReference();

        HttpResponse<String> response = postJson("/units", unitJson(reference, "O", "NEGATIVE", "RED_CELLS", -2, 30),
            signIn(LAGOS_BANK_STAFF));

        assertThat(response.statusCode()).isEqualTo(201);
        String body = response.body();
        assertThat((String) json(body, "$.reference")).isEqualTo(reference);
        assertThat((String) json(body, "$.state")).isEqualTo("AVAILABLE");
        assertThat((String) json(body, "$.bank.id")).isEqualTo(LAGOS_BANK);
        assertThat((String) json(body, "$.expiryDate")).isEqualTo(today().plusDays(30).toString());
        assertThat(response.headers().firstValue("Location")).contains("/units/" + json(body, "$.id"));
    }

    @Test
    void recordingAUnitWritesItsHistoryInTheSameTransaction() throws Exception {
        HttpResponse<String> response = postJson("/units", unitJson(newReference(), "A", "POSITIVE", "PLASMA", -1, 300),
            signIn(LAGOS_BANK_STAFF));
        UUID unit = UUID.fromString(json(response.body(), "$.id"));

        List<String> events = jdbc.sql("SELECT event FROM activity WHERE entity_type = 'UNIT' AND entity_id = ?")
            .param(unit).query(String.class).list();

        assertThat(events).containsExactly("RECORDED");
    }

    @Test
    void theSameReferenceTwiceAtOneBankIsRefusedButAnotherBankMayUseIt() throws Exception {
        String reference = newReference();
        String body = unitJson(reference, "B", "POSITIVE", "WHOLE_BLOOD", -1, 30);
        assertThat(postJson("/units", body, signIn(LAGOS_BANK_STAFF)).statusCode()).isEqualTo(201);

        HttpResponse<String> again = postJson("/units", body, signIn(LAGOS_BANK_STAFF));
        HttpResponse<String> otherBank = postJson("/units", body, signIn(LEKKI_BANK_STAFF));

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat((String) json(again.body(), "$.code")).isEqualTo("DUPLICATE_UNIT_REFERENCE");
        assertThat(otherBank.statusCode()).isEqualTo(201);
    }

    @Test
    void theBankCannotBeChosenInTheBody() throws Exception {
        String body = unitJson(newReference(), "O", "NEGATIVE", "RED_CELLS", -1, 30)
            .replace("{", "{\"bankId\":\"" + LEKKI_BANK + "\",");

        HttpResponse<String> response = postJson("/units", body, signIn(LAGOS_BANK_STAFF));

        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    void expiryMustBeAfterCollection() throws Exception {
        HttpResponse<String> response = postJson("/units", unitJson(newReference(), "O", "NEGATIVE", "RED_CELLS", -1, -2),
            signIn(LAGOS_BANK_STAFF));

        assertValidationError(response, "expiryDate");
    }

    @Test
    void collectionCannotBeInTheFuture() throws Exception {
        HttpResponse<String> response = postJson("/units", unitJson(newReference(), "O", "NEGATIVE", "RED_CELLS", 1, 30),
            signIn(LAGOS_BANK_STAFF));

        assertValidationError(response, "collectionDate");
    }

    @Test
    void aDateSentAsANumberOrFarOutsideTheRangeIsRefusedNotStored() throws Exception {
        // Found by the contract tests (30 Oct): a number was read as days since 1970, landed a million years ago, was
        // stored as "minus infinity" and broke every later read of the unit.
        String reference = newReference();
        String asNumber = unitJson(reference, "O", "NEGATIVE", "RED_CELLS", -1, 30)
            .replaceAll("\"collectionDate\":\"[^\"]+\"", "\"collectionDate\":-518849502");
        String tooEarly = unitJson(newReference(), "O", "NEGATIVE", "RED_CELLS", -1, 30)
            .replaceAll("\"collectionDate\":\"[^\"]+\"", "\"collectionDate\":\"1999-12-31\"");
        String token = signIn(LAGOS_BANK_STAFF);

        HttpResponse<String> number = postJson("/units", asNumber, token);
        HttpResponse<String> early = postJson("/units", tooEarly, token);

        assertThat(number.statusCode()).as(number.body()).isIn(400, 422);
        assertValidationError(early, "collectionDate");
        assertThat(get("/units?size=100", token).statusCode()).as("the list still reads").isEqualTo(200);
    }

    @Test
    void anUnknownBloodGroupNamesTheField() throws Exception {
        HttpResponse<String> response = postJson("/units", unitJson(newReference(), "C", "NEGATIVE", "RED_CELLS", -1, 30),
            signIn(LAGOS_BANK_STAFF));

        assertValidationError(response, "bloodGroup");
    }

    @Test
    void aReferenceWithSpacesIsRefused() throws Exception {
        HttpResponse<String> response = postJson("/units", unitJson("has space", "O", "NEGATIVE", "RED_CELLS", -1, 30),
            signIn(LAGOS_BANK_STAFF));

        assertValidationError(response, "reference");
    }

    @Test
    void bankStaffSeeOnlyTheirOwnBankEvenIfTheyAskForAnother() throws Exception {
        postJson("/units", unitJson(newReference(), "O", "NEGATIVE", "RED_CELLS", -1, 30), signIn(LAGOS_BANK_STAFF));

        HttpResponse<String> response = get("/units?bankId=" + LAGOS_BANK + "&size=100", signIn(LEKKI_BANK_STAFF));

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> banks = json(response.body(), "$.items[*].bank.id");
        assertThat(banks).doesNotContain(LAGOS_BANK);
    }

    @Test
    void theAdministratorCanListAnyBank() throws Exception {
        postJson("/units", unitJson(newReference(), "O", "NEGATIVE", "RED_CELLS", -1, 30), signIn(LAGOS_BANK_STAFF));

        HttpResponse<String> response = get("/units?bankId=" + LAGOS_BANK + "&size=100", signIn(ADMIN));

        List<String> banks = json(response.body(), "$.items[*].bank.id");
        assertThat(banks).isNotEmpty().containsOnly(LAGOS_BANK);
    }

    @Test
    void aUnitPastItsExpiryShowsAsExpiredBeforeAnyJobHasRun() throws Exception {
        String reference = insertUnit("AVAILABLE", -1);

        assertThat(stateShownFor(reference)).isEqualTo("EXPIRED");
    }

    @Test
    void aReservedUnitWhoseHoldLapsedShowsAsAvailableBeforeAnyJobHasRun() throws Exception {
        String reference = insertUnit("RESERVED", 10);
        holdUnit(reference, "now() - interval '5 minutes'");

        assertThat(stateShownFor(reference)).isEqualTo("AVAILABLE");
    }

    @Test
    void aReservedUnitWithARunningHoldShowsAsReserved() throws Exception {
        String reference = insertUnit("RESERVED", 10);
        holdUnit(reference, "now() + interval '1 hour'");

        assertThat(stateShownFor(reference)).isEqualTo("RESERVED");
    }

    @Test
    void filtersCombineAndPagesAreCounted() throws Exception {
        String token = signIn(LAGOS_BANK_STAFF);
        for (int i = 0; i < 3; i++) {
            postJson("/units", unitJson(newReference(), "AB", "NEGATIVE", "CRYOPRECIPITATE", -1, 30), token);
        }

        HttpResponse<String> response = get("/units?bloodGroup=AB&rhesus=NEGATIVE&component=CRYOPRECIPITATE&size=2", token);

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> components = json(response.body(), "$.items[*].component");
        assertThat(components).hasSize(2).containsOnly("CRYOPRECIPITATE");
        assertThat(((Number) json(response.body(), "$.totalItems")).longValue()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aPageSizeOver100IsMalformed() throws Exception {
        HttpResponse<String> response = get("/units?size=101", signIn(LAGOS_BANK_STAFF));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    // --- helpers ---

    static LocalDate today() {
        return LocalDate.now(LAGOS);
    }

    static String newReference() {
        return "IT-" + UUID.randomUUID().toString().substring(0, 13);
    }

    static String unitJson(String reference, String group, String rhesus, String component, int collectedInDays,
                           int expiresInDays) {
        return """
            {"reference":"%s","bloodGroup":"%s","rhesus":"%s","component":"%s","collectionDate":"%s","expiryDate":"%s"}"""
            .formatted(reference, group, rhesus, component, today().plusDays(collectedInDays), today().plusDays(expiresInDays));
    }

    private static void assertValidationError(HttpResponse<String> response, String field) {
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        List<String> fields = json(response.body(), "$.details[*].field");
        assertThat(fields).contains(field);
    }

    /** Puts a unit straight into the database at Lagos Central, in a given stored state. Returns its reference. */
    private String insertUnit(String state, int expiresInDays) {
        String reference = newReference();
        jdbc.sql("""
                INSERT INTO unit (bank_id, reference, blood_group, rhesus, component, collection_date, expiry_date, state, recorded_by)
                VALUES (CAST(? AS uuid), ?, 'B', 'NEGATIVE', 'PLATELETS', ?, ?, ?, CAST(? AS uuid))""")
            .params(LAGOS_BANK, reference, today().minusDays(20), today().plusDays(expiresInDays), state, LAGOS_STAFF_ID)
            .update();
        return reference;
    }

    /** Holds the unit for a new Yaba request, with the given hold end (an SQL expression). */
    private void holdUnit(String reference, String holdEndsSql) {
        UUID request = jdbc.sql("""
                INSERT INTO blood_request (hospital_id, bank_id, blood_group, rhesus, component, quantity, urgency, needed_by,
                                           created_by, idempotency_key, payload_hash)
                VALUES ('6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b', CAST(? AS uuid), 'B', 'NEGATIVE', 'PLATELETS', 1, 'ROUTINE',
                        now() + interval '1 day', '9a8b7c6d-5e4f-4a3b-8c2d-000000000005', gen_random_uuid(), 'it')
                RETURNING id""")
            .param(LAGOS_BANK).query(UUID.class).single();
        jdbc.sql("INSERT INTO reservation (request_id, unit_id, hold_expires_at, created_at) "
                + "SELECT ?, id, " + holdEndsSql + ", now() - interval '2 hours' FROM unit WHERE reference = ?")
            .params(request, reference).update();
    }

    private String stateShownFor(String reference) throws Exception {
        HttpResponse<String> response = get("/units?size=100&bloodGroup=B&rhesus=NEGATIVE&component=PLATELETS",
            signIn(LAGOS_BANK_STAFF));
        List<String> states = json(response.body(), "$.items[?(@.reference == '" + reference + "')].state");
        assertThat(states).as("unit %s should be on the first page", reference).hasSize(1);
        return states.get(0);
    }
}
