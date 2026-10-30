package africa.tesa.bloodlink.support;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for every integration test: the whole service on a random port, against a real PostgreSQL
 * (the same major version as production), never an in-memory substitute.
 *
 * <p>One container is started for the whole test run and shared by every test class. Spring caches the
 * application between classes, so the service and its migrations start once, which keeps CI fast.
 * Tests therefore share one database: each test creates its own rows with fresh ids instead of
 * assuming an empty table. The demo accounts (002-demo-accounts.sql) are always present.
 */
// The sweep is off in tests, so they can check what reads show before any job has run; SweepIT runs it directly.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.sweep.enabled=false")
public abstract class AbstractPostgresIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    /** Every demo account's password (README). */
    protected static final String DEMO_PASSWORD = "Demo-Password-1";
    protected static final String ADMIN = "admin@bloodlink.demo";
    protected static final String LAGOS_BANK_STAFF = "staff@lagos-central.bank.demo";
    protected static final String LEKKI_BANK_STAFF = "staff@lekki.bank.demo";
    protected static final String YABA_HOSPITAL_STAFF = "staff@yaba.hospital.demo";
    protected static final String IKORODU_HOSPITAL_STAFF = "staff@ikorodu.hospital.demo";
    protected static final String VI_HOSPITAL_STAFF = "staff@vi.hospital.demo";
    protected static final String DONOR = "donor@bloodlink.demo";

    static {
        // Started here rather than per class, so the cached Spring context never points at a stopped database.
        // Testcontainers removes the container when the test run ends.
        POSTGRES.start();
    }

    // Property rather than @LocalServerPort, whose package moved between Boot versions.
    @Value("${local.server.port}")
    protected int port;

    protected final HttpClient http = HttpClient.newHttpClient();

    protected HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    protected HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET().build());
    }

    protected HttpResponse<String> get(String path, String token) throws Exception {
        return send(request(path).header("Authorization", "Bearer " + token).GET().build());
    }

    protected HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    protected HttpResponse<String> postJson(String path, String json, String token) throws Exception {
        return send(request(path).header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    /** Signs in as a demo account through POST /sessions and returns its access token. */
    protected String signIn(String email) throws Exception {
        HttpResponse<String> response = postJson("/sessions",
            "{\"email\":\"" + email + "\",\"password\":\"" + DEMO_PASSWORD + "\"}");
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Sign-in failed for " + email + ": " + response.statusCode() + " " + response.body());
        }
        return JsonPath.read(response.body(), "$.accessToken");
    }

    /** Creates a request as a hospital account through POST /requests (needed in 4 hours) and returns its id. */
    protected String createRequest(String hospitalStaffEmail, UUID bankId, String group, String rhesus, String component,
                                   int quantity, String urgency) throws Exception {
        HttpResponse<String> response = send(request("/requests")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + signIn(hospitalStaffEmail))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(requestJson(bankId, group, rhesus, component, quantity, urgency)))
            .build());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Request failed: " + response.statusCode() + " " + response.body());
        }
        return JsonPath.read(response.body(), "$.id");
    }

    /** A POST /requests body, needed four hours from now. */
    protected static String requestJson(UUID bankId, String group, String rhesus, String component, int quantity,
                                        String urgency) {
        return """
            {"bankId":"%s","bloodGroup":"%s","rhesus":"%s","component":"%s","quantity":%d,"urgency":"%s","neededBy":"%s"}"""
            .formatted(bankId, group, rhesus, component, quantity, urgency, Instant.now().plusSeconds(4 * 3600));
    }

    /** Reads one value from a JSON body, e.g. json(body, "$.user.role"). */
    protected static <T> T json(String body, String path) {
        return JsonPath.read(body, path);
    }
}
