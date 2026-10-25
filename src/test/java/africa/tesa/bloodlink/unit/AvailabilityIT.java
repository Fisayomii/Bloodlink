package africa.tesa.bloodlink.unit;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * GET /availability. Each test builds its own bank in its own area, so counts are exact whatever else is in the
 * shared test database. The bank holds six AB-negative platelet units:
 *
 * <pre>
 *   T1  AVAILABLE, expires in 5 days                    -> counted
 *   T2  AVAILABLE, expires in 2 days                    -> counted (earliest expiry)
 *   T3  AVAILABLE, expired yesterday                    -> not counted
 *   T4  RESERVED, hold still running                    -> not counted
 *   T5  RESERVED, hold lapsed 10 minutes ago, no job    -> counted: a lapsed hold is never shown as held
 *   T6  DISCARDED                                       -> not counted
 * </pre>
 */
class AvailabilityIT extends AbstractPostgresIT {

    private static final String YABA_HOSPITAL = "6e7f8a9b-0c1d-4e2f-9a3b-4c5d6e7f8a9b";
    private static final String YABA_STAFF_ID = "9a8b7c6d-5e4f-4a3b-8c2d-000000000005";

    @Autowired
    JdbcClient jdbc;

    private UUID bank;
    private String area;

    @BeforeEach
    void buildABankWithUnitsInEverySituation() {
        area = "Area-" + UUID.randomUUID().toString().substring(0, 8);
        bank = jdbc.sql("INSERT INTO organisation (name, type, area) VALUES (?, 'BLOOD_BANK', ?) RETURNING id")
            .params("Availability Test Bank " + area, area).query(UUID.class).single();
        UUID staff = jdbc.sql("""
                INSERT INTO app_user (email, display_name, password_hash, role, organisation_id)
                VALUES (?, 'Test staff', 'not-a-real-hash', 'BANK_STAFF', ?) RETURNING id""")
            .params(UUID.randomUUID() + "@test.demo", bank).query(UUID.class).single();

        insert("T1", "AVAILABLE", 5, staff);
        insert("T2", "AVAILABLE", 2, staff);
        insert("T3", "AVAILABLE", -1, staff);
        UUID t4 = insert("T4", "RESERVED", 4, staff);
        UUID t5 = insert("T5", "RESERVED", 6, staff);
        insert("T6", "DISCARDED", 7, staff);

        UUID request = jdbc.sql("""
                INSERT INTO blood_request (hospital_id, bank_id, blood_group, rhesus, component, quantity, urgency, needed_by,
                                           created_by, idempotency_key, payload_hash)
                VALUES (CAST(? AS uuid), ?, 'AB', 'NEGATIVE', 'PLATELETS', 2, 'URGENT', now() + interval '1 day',
                        CAST(? AS uuid), gen_random_uuid(), 'it')
                RETURNING id""")
            .params(YABA_HOSPITAL, bank, YABA_STAFF_ID).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO reservation (request_id, unit_id, hold_expires_at, created_at) VALUES
                    (?, ?, now() + interval '1 hour', now() - interval '1 minute'),
                    (?, ?, now() - interval '10 minutes', now() - interval '2 hours')""")
            .params(request, t4, request, t5).update();
    }

    @Test
    void countsOnlyUnitsThatCanReallyBeReserved() throws Exception {
        HttpResponse<String> response = get("/availability?bankId=" + bank, signIn(YABA_HOSPITAL_STAFF));

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat((Integer) json(body, "$.items.length()")).isEqualTo(1);
        assertThat((Integer) json(body, "$.items[0].availableUnits")).isEqualTo(3);
        assertThat((String) json(body, "$.items[0].earliestExpiryDate")).isEqualTo(UnitsIT.today().plusDays(2).toString());
        assertThat((String) json(body, "$.items[0].bloodGroup")).isEqualTo("AB");
        assertThat((String) json(body, "$.items[0].rhesus")).isEqualTo("NEGATIVE");
        assertThat((String) json(body, "$.items[0].component")).isEqualTo("PLATELETS");
    }

    @Test
    void readingAvailabilityNeverChangesStoredData() throws Exception {
        get("/availability?bankId=" + bank, signIn(YABA_HOSPITAL_STAFF));

        List<String> stored = jdbc.sql("SELECT state FROM unit WHERE bank_id = ? ORDER BY reference").param(bank)
            .query(String.class).list();
        assertThat(stored).containsExactly("AVAILABLE", "AVAILABLE", "AVAILABLE", "RESERVED", "RESERVED", "DISCARDED");
    }

    @Test
    void agreesWithTheUnitStatusViewUsedEverywhereElse() throws Exception {
        HttpResponse<String> response = get("/availability?bankId=" + bank, signIn(YABA_HOSPITAL_STAFF));
        int fromView = jdbc.sql("SELECT count(*) FROM unit_status WHERE bank_id = ? AND effective_state = 'AVAILABLE'")
            .param(bank).query(Integer.class).single();

        assertThat((Integer) json(response.body(), "$.items[0].availableUnits")).isEqualTo(fromView);
    }

    @Test
    void figuresSayWhenTheyWereComputedAndHowMatchingWorks() throws Exception {
        Instant before = Instant.now();
        HttpResponse<String> response = get("/availability?bankId=" + bank, signIn(YABA_HOSPITAL_STAFF));

        Instant computedAt = Instant.parse(json(response.body(), "$.computedAt"));
        assertThat(computedAt).isBetween(before.minus(Duration.ofSeconds(1)), Instant.now().plus(Duration.ofSeconds(1)));
        assertThat((String) json(response.body(), "$.matching"))
            .isEqualTo("Exact group, rhesus type and component only. No substitution.");
    }

    @Test
    void filtersCombineAndAreaIsNotCaseSensitive() throws Exception {
        String token = signIn(YABA_HOSPITAL_STAFF);

        String match = get("/availability?area=" + area.toUpperCase() + "&bloodGroup=AB&rhesus=NEGATIVE&component=PLATELETS",
            token).body();
        String wrongRhesus = get("/availability?area=" + area + "&rhesus=POSITIVE", token).body();
        String wrongComponent = get("/availability?area=" + area + "&component=PLASMA", token).body();

        assertThat((Integer) json(match, "$.items[0].availableUnits")).isEqualTo(3);
        assertThat((Integer) json(wrongRhesus, "$.items.length()")).isZero();
        assertThat((Integer) json(wrongComponent, "$.items.length()")).isZero();
    }

    @Test
    void noMatchIsAnEmptyListNotAnError() throws Exception {
        HttpResponse<String> response = get("/availability?area=Nowhere-" + UUID.randomUUID(), signIn(DONOR));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) json(response.body(), "$.items.length()")).isZero();
    }

    @Test
    void anUnknownComponentIsMalformed() throws Exception {
        HttpResponse<String> response = get("/availability?component=BLOOD_ORANGE", signIn(YABA_HOSPITAL_STAFF));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    private UUID insert(String reference, String state, int expiresInDays, UUID staff) {
        LocalDate today = UnitsIT.today();
        return jdbc.sql("""
                INSERT INTO unit (bank_id, reference, blood_group, rhesus, component, collection_date, expiry_date, state, recorded_by)
                VALUES (?, ?, 'AB', 'NEGATIVE', 'PLATELETS', ?, ?, ?, ?) RETURNING id""")
            .params(bank, reference, today.minusDays(20), today.plusDays(expiresInDays), state, staff)
            .query(UUID.class).single();
    }
}
