package africa.tesa.bloodlink.auth;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * How access tokens are signed and checked.
 *
 * <p>The service issues its own tokens (JWT, signed with HMAC-SHA256 using one secret key) because the brief rules out
 * real identity verification and an extra identity server would be one more thing to host for free. The same key signs
 * on sign-in and verifies on every request; only HS256 is accepted, and the expiry ("exp") is always checked.
 */
@Configuration
public class TokenConfig {

    /** Claim holding the user's role, e.g. "BANK_STAFF". */
    public static final String ROLE_CLAIM = "role";
    /** Claim holding the user's organisation id (absent for donors and the administrator). */
    public static final String ORGANISATION_CLAIM = "org";
    /** Issuer written into every token. */
    public static final String ISSUER = "bloodlink";

    private static final Logger LOG = LoggerFactory.getLogger(TokenConfig.class);
    private static final int MINIMUM_SECRET_BYTES = 32;
    private static final String DEVELOPMENT_SECRET_PREFIX = "local-development-only";

    @Bean
    SecretKey tokenSigningKey(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_SECRET_BYTES) {
            // HS256 needs at least 256 bits of key. Refusing to start beats issuing weak tokens.
            throw new IllegalStateException("JWT_SECRET must be at least " + MINIMUM_SECRET_BYTES + " characters long");
        }
        if (secret.startsWith(DEVELOPMENT_SECRET_PREFIX)) {
            LOG.warn("Using the local development JWT secret. Set JWT_SECRET to a long random value before deploying.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey tokenSigningKey) {
        return NimbusJwtEncoder.withSecretKey(tokenSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    /** Checks the signature with the same key, accepts only HS256, and rejects expired tokens. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey tokenSigningKey) {
        return NimbusJwtDecoder.withSecretKey(tokenSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** Turns the token's "role" claim into the authority Spring Security checks, e.g. ROLE_BANK_STAFF. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** BCrypt with cost 10: slow enough to make guessing stored passwords expensive, fast enough for sign-in. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
