package africa.tesa.bloodlink.auth;

import africa.tesa.bloodlink.auth.SessionController.Session;
import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates donor accounts (POST /donors).
 *
 * <ul>
 *   <li>The role is always DONOR and there is no organisation: nothing in the body can choose otherwise, and a body
 *       with any other field (a "role", say) is refused as a whole by the strict-body rule.</li>
 *   <li>One account per email, case-insensitive. The database's unique index decides, so two sign-ups with the same
 *       email at the same moment create one account and the other gets 409.</li>
 *   <li>The password is stored as a bcrypt hash, like every other account. bcrypt reads only the first 72 bytes, so a
 *       longer password is refused (422) rather than silently shortened.</li>
 * </ul>
 */
@Service
public class DonorService {

    static final int NAME_MIN = 2;
    static final int NAME_MAX = 120;
    static final int PASSWORD_MAX_BYTES = 72;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final SessionService sessions;

    public DonorService(JdbcClient jdbc, PasswordEncoder passwords, SessionService sessions) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    @Transactional
    public Session signUp(String email, String displayName, String password) {
        String name = displayName.trim();
        int characters = name.codePointCount(0, name.length());
        if (characters < NAME_MIN || characters > NAME_MAX) {
            throw ApiException.validation("displayName", "Between " + NAME_MIN + " and " + NAME_MAX
                + " characters, not counting spaces or control characters at either end.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw ApiException.validation("password",
                "At most " + PASSWORD_MAX_BYTES + " bytes (72 plain letters or digits; fewer with accented letters or symbols).");
        }
        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);

        Optional<UUID> created = jdbc.sql("""
                INSERT INTO app_user (email, display_name, password_hash, role)
                VALUES (:email, :name, :hash, 'DONOR')
                ON CONFLICT (email) DO NOTHING
                RETURNING id""")
            .param("email", normalisedEmail)
            .param("name", name)
            .param("hash", passwords.encode(password))
            .query(UUID.class)
            .optional();
        if (created.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_ALREADY_REGISTERED,
                "An account with this email already exists. Sign in instead.");
        }
        return sessions.sessionFor(normalisedEmail);
    }
}
