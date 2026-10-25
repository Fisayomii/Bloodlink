package africa.tesa.bloodlink.auth;

import africa.tesa.bloodlink.auth.SessionController.Session;
import africa.tesa.bloodlink.auth.SessionController.User;
import africa.tesa.bloodlink.organisation.OrganisationType;
import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Checks a demo account's email and password and issues a signed access token. */
@Service
public class SessionService {

    /**
     * A real BCrypt hash of a random value nobody knows. When the email is unknown we still check the password against
     * it, so a wrong email takes as long as a wrong password and response times do not reveal which emails exist.
     */
    private static final String UNKNOWN_USER_HASH = "$2a$10$23wPO4MG58VfFR.lSmzF8uK9Eq5fOCYUASXbHSA3IF85yDvhfsTHu";

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final Duration timeToLive;
    private final Clock clock;

    public SessionService(JdbcClient jdbc, PasswordEncoder passwords, JwtEncoder tokens,
                          @Value("${app.jwt.time-to-live}") Duration timeToLive) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.timeToLive = timeToLive;
        this.clock = Clock.systemUTC();
    }

    public Session signIn(String email, String password) {
        Optional<Account> account = findByEmail(email.trim().toLowerCase(Locale.ROOT));
        String hash = account.map(Account::passwordHash).orElse(UNKNOWN_USER_HASH);
        boolean passwordMatches = passwords.matches(password, hash);
        if (account.isEmpty() || !passwordMatches) {
            // Same answer for "no such email" and "wrong password", so the message does not reveal which.
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
        }
        Account user = account.get();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(timeToLive);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer(TokenConfig.ISSUER)
            .subject(user.id().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(TokenConfig.ROLE_CLAIM, user.role().name());
        if (user.organisation() != null) {
            claims.claim(TokenConfig.ORGANISATION_CLAIM, user.organisation().id().toString());
        }
        String token = tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
            .getTokenValue();
        return new Session(token, "Bearer", expiresAt,
            new User(user.id(), user.displayName(), user.email(), user.role(), user.organisation()));
    }

    private Optional<Account> findByEmail(String email) {
        return jdbc.sql("""
                SELECT u.id, u.email, u.display_name, u.password_hash, u.role,
                       o.id AS org_id, o.name AS org_name, o.type AS org_type, o.area AS org_area
                  FROM app_user u
                  LEFT JOIN organisation o ON o.id = u.organisation_id
                 WHERE u.email = ?""")
            .param(email)
            .query((rs, row) -> new Account(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                rs.getString("org_id") == null ? null : new OrganisationView(
                    rs.getObject("org_id", UUID.class),
                    rs.getString("org_name"),
                    OrganisationType.valueOf(rs.getString("org_type")),
                    rs.getString("org_area"))))
            .optional();
    }

    private record Account(UUID id, String email, String displayName, String passwordHash, Role role,
                           OrganisationView organisation) {
    }
}
