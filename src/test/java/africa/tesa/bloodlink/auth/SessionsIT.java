package africa.tesa.bloodlink.auth;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** POST /sessions and what the issued token can and cannot do. */
class SessionsIT extends AbstractPostgresIT {

    @Autowired
    JwtEncoder encoder;

    @Test
    void bankStaffSignInReturnsATokenAndWhoTheyAre() throws Exception {
        HttpResponse<String> response = postJson("/sessions",
            "{\"email\":\"" + LAGOS_BANK_STAFF + "\",\"password\":\"" + DEMO_PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        String body = response.body();
        assertThat((String) json(body, "$.tokenType")).isEqualTo("Bearer");
        assertThat((String) json(body, "$.accessToken")).isNotBlank();
        assertThat(Instant.parse(json(body, "$.expiresAt"))).isAfter(Instant.now().plusSeconds(7 * 3600));
        assertThat((String) json(body, "$.user.role")).isEqualTo("BANK_STAFF");
        assertThat((String) json(body, "$.user.organisation.name")).isEqualTo("Lagos Central Blood Bank (demo)");
        assertThat(body).doesNotContain("password");
    }

    @Test
    void emailIsNotCaseSensitive() throws Exception {
        HttpResponse<String> response = postJson("/sessions",
            "{\"email\":\"Staff@Lagos-Central.Bank.Demo\",\"password\":\"" + DEMO_PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void donorsAndTheAdministratorHaveNoOrganisation() throws Exception {
        HttpResponse<String> response = postJson("/sessions",
            "{\"email\":\"" + DONOR + "\",\"password\":\"" + DEMO_PASSWORD + "\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((Object) json(response.body(), "$.user.organisation")).isNull();
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        HttpResponse<String> wrongPassword = postJson("/sessions",
            "{\"email\":\"" + LAGOS_BANK_STAFF + "\",\"password\":\"Not-The-Password\"}");
        HttpResponse<String> unknownEmail = postJson("/sessions",
            "{\"email\":\"nobody@bloodlink.demo\",\"password\":\"" + DEMO_PASSWORD + "\"}");

        for (HttpResponse<String> response : List.of(wrongPassword, unknownEmail)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat((String) json(response.body(), "$.code")).isEqualTo("INVALID_CREDENTIALS");
            assertThat((String) json(response.body(), "$.message")).isEqualTo("Email or password is incorrect.");
        }
    }

    @Test
    void aShortPasswordIsAValidationErrorNamingTheField() throws Exception {
        HttpResponse<String> response = postJson("/sessions", "{\"email\":\"" + LAGOS_BANK_STAFF + "\",\"password\":\"short\"}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat((String) json(response.body(), "$.details[0].field")).isEqualTo("password");
    }

    @Test
    void fieldsOutsideTheContractAreRefused() throws Exception {
        HttpResponse<String> response = postJson("/sessions",
            "{\"email\":\"" + LAGOS_BANK_STAFF + "\",\"password\":\"" + DEMO_PASSWORD + "\",\"rememberMe\":true}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void bodyThatIsNotJsonIsMalformed() throws Exception {
        HttpResponse<String> response = postJson("/sessions", "email=a&password=b");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void theTokenOpensProtectedPaths() throws Exception {
        String token = signIn(YABA_HOSPITAL_STAFF);

        HttpResponse<String> response = get("/organisations", token);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void aTokenEditedToClaimAdminIsRefused() throws Exception {
        String[] parts = signIn(YABA_HOSPITAL_STAFF).split("\\.");
        // Keep the header and the genuine signature, but swap the payload for one that claims to be the administrator.
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("{\"iss\":\"bloodlink\",\"sub\":\"9a8b7c6d-5e4f-4a3b-8c2d-000000000001\",\"role\":\"ADMIN\","
                + "\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        HttpResponse<String> response = get("/organisations", forged);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void aMissingTokenIsRefused() throws Exception {
        HttpResponse<String> response = get("/organisations");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(TokenConfig.ISSUER)
            .subject("9a8b7c6d-5e4f-4a3b-8c2d-000000000005")
            .issuedAt(twoHoursAgo.minusSeconds(3600))
            .expiresAt(twoHoursAgo)
            .claim(TokenConfig.ROLE_CLAIM, "HOSPITAL_STAFF")
            .build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();

        HttpResponse<String> response = get("/organisations", expired);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("UNAUTHENTICATED");
    }
}
