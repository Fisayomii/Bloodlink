package africa.tesa.bloodlink.web;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Requests the contract tests (Schemathesis) found answered wrongly on 27 and 28 October: each one was a 500, or a 400 in Spring's
 * own body instead of the contract's Error shape, or a status the contract does not list. Each must now be a 400
 * MALFORMED_REQUEST in the Error shape, carrying the request id.
 */
class MalformedRequestIT extends AbstractPostgresIT {

    @Test
    void aQueryParameterWithNoNameIsA400NotA500() throws Exception {
        HttpResponse<String> response = get("/availability?area=&=null", signIn(DONOR));

        assertMalformed(response);
    }

    @Test
    void aMultipartContentTypeWithoutABoundaryIsA400NotA500() throws Exception {
        HttpResponse<String> response = send(request("/requests")
            .header("Authorization", "Bearer " + signIn(LAGOS_BANK_STAFF))
            .header("Content-Type", "multipart/form-data")
            .GET().build());

        // A GET has no body to read, so it may simply succeed; what it must never be is a server error.
        assertThat(response.statusCode()).isIn(200, 400);
    }

    @Test
    void aMultipartBodyOnAJsonOperationIsA400() throws Exception {
        HttpResponse<String> response = send(request("/sessions")
            .header("Content-Type", "multipart/form-data")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + DONOR + "\",\"password\":\"x\"}")).build());

        assertMalformed(response);
    }

    @Test
    void aPathTheFirewallRejectsStillGetsTheContractErrorShape() throws Exception {
        HttpResponse<String> response = send(request("/requests/%25not-an-id")
            .header("Authorization", "Bearer " + signIn(DONOR))
            .header(RequestIdFilter.HEADER, "malformed-path-1")
            .GET().build());

        assertMalformed(response);
        assertThat((String) json(response.body(), "$.requestId")).isEqualTo("malformed-path-1");
        assertThat(response.body()).doesNotContain("timestamp");
    }

    @Test
    void anAreaLongerThan80CharactersIsA400AsTheContractSays() throws Exception {
        String token = signIn(DONOR);
        String area = "a".repeat(81);

        assertMalformed(get("/availability?area=" + area, token));
        assertMalformed(get("/organisations?area=" + area, token));
    }

    @Test
    void aNullCharacterInAQueryValueIsA400NotA500() throws Exception {
        String token = signIn(DONOR);

        // PostgreSQL refuses the null character in any text, so it used to fail inside the query (found 28 October).
        assertMalformed(get("/availability?area=%00Ikeja&component=PLASMA", token));
        assertMalformed(get("/organisations?area=Ikeja%00&type=BLOOD_BANK", token));
    }

    @Test
    void aBlankIdInThePathIsA400NotA500() throws Exception {
        // Spring turns " " into no id at all and reports a missing path value (found 30 October).
        assertMalformed(postJson("/bookings/%20/cancellation", "", signIn(DONOR)));
        assertMalformed(postJson("/requests/%20/approval", "", signIn(LAGOS_BANK_STAFF)));
    }

    @Test
    void aVeryLongQueryStillGetsTheContractErrorShape() throws Exception {
        // About 9 KB of query, as the contract tests sent: over Tomcat's 8 KB default, which answered in HTML.
        String query = "size=x&".repeat(1300);

        HttpResponse<String> response = get("/bookings?" + query, signIn(DONOR));

        assertMalformed(response);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).contains("json"));
    }

    private static void assertMalformed(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
        assertThat((String) json(response.body(), "$.requestId")).isNotBlank();
        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).isPresent();
    }
}
