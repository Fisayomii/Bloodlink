package africa.tesa.bloodlink.config;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless security with bearer tokens (see auth/TokenConfig). Everything is closed except health, sign-in and the
 * API docs.
 *
 * <p>Two levels of access control, both enforced here in the service, never by the interface:
 * <ol>
 *   <li><b>Role rules per path</b>, below: for example only bank staff may record units. A wrong role gets 403.</li>
 *   <li><b>Organisation rules</b> in the services: for example bank staff may change only their own bank's units.</li>
 * </ol>
 * 401 and 403 are handed to GlobalExceptionHandler, so they use the contract's Error shape like every other failure.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
            JwtAuthenticationConverter tokenToUser) throws Exception {
        http
            // A token-based API with no cookies, so there is no CSRF exposure to protect against.
            // The interface is a separate site on its own address, so the browser needs CORS (see corsConfigurationSource).
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Spring's internal error page dispatch must not itself demand a token.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.POST, "/sessions").permitAll()
                // API docs: the Swagger page and the contract it shows. Both are public, like the repository.
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/openapi.yaml", "/favicon.ico").permitAll()
                // Role rules, one line per operation in the contract that is limited to certain roles.
                // Removing any of these makes RoleRulesIT fail.
                .requestMatchers(HttpMethod.GET, "/units").hasAnyRole("BANK_STAFF", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/units").hasRole("BANK_STAFF")
                .requestMatchers(HttpMethod.GET, "/requests").hasAnyRole("BANK_STAFF", "HOSPITAL_STAFF", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/requests").hasRole("HOSPITAL_STAFF")
                .requestMatchers(HttpMethod.POST, "/requests/*/approval", "/requests/*/declination")
                    .hasAnyRole("BANK_STAFF", "ADMIN")
                // Everything else (organisations, availability, one request) needs any signed-in user.
                .anyRequest().authenticated())
            // Reads "Authorization: Bearer <token>", checks its signature and expiry, and takes the role from it.
            .oauth2ResourceServer(tokens -> tokens
                .jwt(jwt -> jwt.jwtAuthenticationConverter(tokenToUser))
                .authenticationEntryPoint((request, response, ex) -> errors.resolveException(request, response, null, ex))
                .accessDeniedHandler((request, response, ex) -> errors.resolveException(request, response, null, ex)))
            // No or bad token -> 401 UNAUTHENTICATED; wrong role -> 403 FORBIDDEN. Both go through
            // GlobalExceptionHandler so the body is the contract's Error shape with the request id.
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) -> errors.resolveException(request, response, null, ex))
                .accessDeniedHandler((request, response, ex) -> errors.resolveException(request, response, null, ex)));
        return http.build();
    }

    /**
     * Allows only the interface's own addresses. Set ALLOWED_ORIGINS on the host to the deployed site,
     * comma-separated, e.g. https://bloodlink.netlify.app. Defaults cover the Vite dev server (5173) and vite preview (4173).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        // Headers the interface must be able to read from responses.
        cors.setExposedHeaders(List.of("X-Request-Id", "Idempotent-Replayed", "Location"));
        // Bearer tokens, no cookies.
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
