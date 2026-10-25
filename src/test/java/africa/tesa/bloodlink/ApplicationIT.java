package africa.tesa.bloodlink;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The service starts against a real Postgres with every migration applied, refuses strangers,
 * serves its API docs, and only lets the interface's own site call it from a browser.
 */
class ApplicationIT extends AbstractPostgresIT {

    @Test
    void healthIsUpAfterMigrations() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void protectedPathWithoutTokenIsRefusedWith401InTheContractErrorShape() throws Exception {
        HttpResponse<String> response = get("/units");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
            .contains("\"code\":\"UNAUTHENTICATED\"")
            .contains("\"message\":\"Sign in to continue.\"")
            .contains("\"details\":[]");
    }

    @Test
    void swaggerPageAndTheCommittedContractAreServedWithoutAToken() throws Exception {
        HttpResponse<String> page = get("/");
        HttpResponse<String> contract = get("/openapi.yaml");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("swagger-ui");
        assertThat(contract.statusCode()).isEqualTo(200);
        // Byte-for-byte the file in contract/, so the docs can never drift from the agreement.
        assertThat(contract.body()).isEqualTo(Files.readString(Path.of("contract/openapi.yaml")));
    }

    @Test
    void preflightFromTheInterfaceIsAllowed() throws Exception {
        HttpResponse<String> response = preflight("http://localhost:5173");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
    }

    @Test
    void preflightFromAnUnknownSiteIsRefused() throws Exception {
        HttpResponse<String> response = preflight("https://not-our-site.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        return send(request("/sessions")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-request-id")
            .build());
    }
}
