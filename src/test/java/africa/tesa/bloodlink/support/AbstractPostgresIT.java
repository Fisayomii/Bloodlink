package africa.tesa.bloodlink.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * assuming an empty table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractPostgresIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

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
}
