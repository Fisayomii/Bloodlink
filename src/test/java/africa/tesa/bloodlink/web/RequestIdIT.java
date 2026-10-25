package africa.tesa.bloodlink.web;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The request identifier that lets one user's journey be followed across both tiers. */
class RequestIdIT extends AbstractPostgresIT {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Test
    void theInterfacesIdIsReturnedOnTheResponseAndInTheErrorBody() throws Exception {
        HttpResponse<String> response = send(request("/units").header("X-Request-Id", "web-7f9c2b1e").GET().build());

        assertThat(response.headers().firstValue("X-Request-Id")).contains("web-7f9c2b1e");
        assertThat(response.body()).contains("\"requestId\":\"web-7f9c2b1e\"");
    }

    @Test
    void aRequestWithoutAnIdGetsANewOne() throws Exception {
        HttpResponse<String> response = get("/units");

        String generated = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(generated).matches(UUID_PATTERN);
        assertThat(response.body()).contains("\"requestId\":\"" + generated + "\"");
    }

    @Test
    void anUnsafeIdIsReplacedRatherThanLogged() throws Exception {
        HttpResponse<String> response = send(request("/units").header("X-Request-Id", "evil\"}{fake-log-line").GET().build());

        assertThat(response.headers().firstValue("X-Request-Id")).hasValueSatisfying(id -> assertThat(id).matches(UUID_PATTERN));
    }

    @Test
    void healthChecksCarryAnIdToo() throws Exception {
        HttpResponse<String> response = send(request("/actuator/health").header("X-Request-Id", "probe-1").GET().build());

        assertThat(response.headers().firstValue("X-Request-Id")).contains("probe-1");
    }
}
