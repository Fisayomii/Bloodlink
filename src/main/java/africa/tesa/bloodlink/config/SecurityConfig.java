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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless security: everything is closed except health, sign-in and the API docs.
 * 401 and 403 are handed to GlobalExceptionHandler, so they use the contract's Error shape like every other failure.
 * Token checking and roles arrive with the sign-in work (batch 2).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
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
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/sessions").permitAll()
                // API docs: the Swagger page and the contract it shows. Both are public, like the repository.
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/openapi.yaml", "/favicon.ico").permitAll()
                .anyRequest().authenticated())
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
