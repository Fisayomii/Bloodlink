package africa.tesa.bloodlink.auth;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** POST /donors (contract v0.2.6): a voluntary donor creates their own account and is signed in. */
class DonorSignUpIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void aNewDonorIsSignedInAndCanUseTheDonorScreensStraightAway() throws Exception {
        String email = uniqueEmail();

        HttpResponse<String> response = signUp(email, "Amaka Obi", "A-good-password-1");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) json(response.body(), "$.user.role")).isEqualTo("DONOR");
        assertThat((String) json(response.body(), "$.user.email")).isEqualTo(email);
        assertThat((String) json(response.body(), "$.user.displayName")).isEqualTo("Amaka Obi");
        assertThat((Object) json(response.body(), "$.user.organisation")).isNull();
        assertThat(response.body()).doesNotContain("password");

        String token = json(response.body(), "$.accessToken");
        assertThat(get("/donation-slots", token).statusCode()).isEqualTo(200);
        assertThat(get("/bookings", token).statusCode()).isEqualTo(200);
        // Still a donor, nothing more.
        assertThat(get("/units", token).statusCode()).isEqualTo(403);
    }

    @Test
    void theNewAccountSignsInLaterWithItsPassword() throws Exception {
        String email = uniqueEmail();
        signUp(email, "Tunde Bello", "Another-password-2");

        HttpResponse<String> signIn = postJson("/sessions",
            "{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"Another-password-2\"}");

        assertThat(signIn.statusCode()).isEqualTo(201);
        String storedHash = jdbc.sql("SELECT password_hash FROM app_user WHERE email = ?").param(email)
            .query(String.class).single();
        assertThat(storedHash).startsWith("$2a$10$").doesNotContain("Another-password-2");
    }

    @Test
    void anEmailAlreadyInUseIsRefusedWhateverItsCase() throws Exception {
        String email = uniqueEmail();
        signUp(email, "First Donor", "First-password-1");

        HttpResponse<String> again = signUp(email.toUpperCase(), "Second Donor", "Second-password-2");
        HttpResponse<String> staffEmail = signUp(LAGOS_BANK_STAFF, "Not Staff", "Some-password-3");

        for (HttpResponse<String> response : List.of(again, staffEmail)) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat((String) json(response.body(), "$.code")).isEqualTo("EMAIL_ALREADY_REGISTERED");
        }
        // The first account is untouched: its password still works, the second one does not.
        assertThat(postJson("/sessions", "{\"email\":\"" + email + "\",\"password\":\"First-password-1\"}").statusCode())
            .isEqualTo(201);
        assertThat(postJson("/sessions", "{\"email\":\"" + email + "\",\"password\":\"Second-password-2\"}").statusCode())
            .isEqualTo(401);
    }

    @Test
    void twoSignUpsWithTheSameEmailAtTheSameMomentCreateOneAccount() throws Exception {
        String email = uniqueEmail();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String password = "Same-moment-" + i + "-pw";
            Callable<Integer> call = () -> {
                start.await();
                return signUp(email, "Racing Donor", password).statusCode();
            };
            results.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email = ?").param(email).query(Integer.class).single())
            .isEqualTo(1);
    }

    @Test
    void nobodyCanSignUpAsStaffOrAdministrator() throws Exception {
        String email = uniqueEmail();

        HttpResponse<String> withRole = postJson("/donors", "{\"email\":\"" + email
            + "\",\"displayName\":\"Wants Admin\",\"password\":\"A-password-123\",\"role\":\"ADMIN\"}");

        assertThat(withRole.statusCode()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email = ?").param(email).query(Integer.class).single())
            .isZero();
    }

    @Test
    void eachFieldIsCheckedAndNamed() throws Exception {
        assertInvalid(signUp("not-an-email", "Valid Name", "A-password-123"), "email");
        assertInvalid(signUp(uniqueEmail(), " A ", "A-password-123"), "displayName");
        assertInvalid(signUp(uniqueEmail(), "x".repeat(121), "A-password-123"), "displayName");
        assertInvalid(signUp(uniqueEmail(), "Valid Name", "short"), "password");
        // 40 characters but 80 bytes: bcrypt would ignore everything after byte 72, so it is refused, not cut short.
        assertInvalid(signUp(uniqueEmail(), "Valid Name", "é".repeat(40)), "password");
    }

    @Test
    void anEmojiNameIsCountedAsTheDatabaseCountsIt() throws Exception {
        // Two emoji are two characters to the database (four to Java): long enough, and stored as typed.
        HttpResponse<String> response = signUp(uniqueEmail(), "🩸🩸", "A-password-123");

        assertThat(response.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> signUp(String email, String displayName, String password) throws Exception {
        return postJson("/donors", "{\"email\":\"" + email + "\",\"displayName\":\"" + displayName
            + "\",\"password\":\"" + password + "\"}");
    }

    private static void assertInvalid(HttpResponse<String> response, String field) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat((String) json(response.body(), "$.details[0].field")).isEqualTo(field);
    }

    private static String uniqueEmail() {
        return "donor-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    }
}
