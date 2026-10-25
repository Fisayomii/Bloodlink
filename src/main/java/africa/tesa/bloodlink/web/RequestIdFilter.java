package africa.tesa.bloodlink.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an identifier so one user's journey can be followed across both tiers.
 *
 * <ul>
 *   <li>Uses the interface's X-Request-Id if it is safe (letters, digits, . _ : -, up to 64 characters);
 *       otherwise makes a new UUID. An unsafe value is replaced rather than logged, so nobody can inject
 *       fake log lines through the header.</li>
 *   <li>Puts it in the logging context (MDC key "requestId"), so every log line written while handling the
 *       request carries it, and returns it in the X-Request-Id response header and in every Error body.</li>
 *   <li>Writes one access line per request: method, path, status, duration.</li>
 * </ul>
 *
 * Runs before Spring Security (highest precedence), so even a 401 carries the identifier.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = acceptOrCreate(request.getHeader(HEADER));
        long started = System.nanoTime();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (!isQuietHealthCheck(request, response)) {
                LOG.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(), response.getStatus(), millis);
            }
            MDC.remove(MDC_KEY);
        }
    }

    /** The interface's identifier when it is safe to log; a new UUID otherwise. */
    static String acceptOrCreate(String supplied) {
        if (supplied != null && SAFE.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    /** The identifier of the request being handled, for Error bodies. */
    public static String current() {
        String requestId = MDC.get(MDC_KEY);
        return requestId != null ? requestId : "unknown";
    }

    /** The host polls health every few seconds; successful polls would drown the useful lines. */
    private static boolean isQuietHealthCheck(HttpServletRequest request, HttpServletResponse response) {
        return "/actuator/health".equals(request.getRequestURI()) && response.getStatus() == 200;
    }
}
