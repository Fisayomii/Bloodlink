package africa.tesa.bloodlink.auth;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * The brief: "A test calls the service directly with the wrong role and asserts the refusal, and that test must fail
 * when the check is removed."
 *
 * <p>Each test signs in as a real demo account and calls the service directly, with no interface involved.
 * To see a test fail, delete the matching hasRole line in SecurityConfig: the call then gets past the role check and
 * the response is no longer 403. Organisation rules (only your own bank's units) are tested with each feature.
 */
class RoleRulesIT extends AbstractPostgresIT {

    private static final String A_REQUEST = "/requests/5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e";
    private static final String A_SLOT = "/donation-slots/2d3e4f5a-6b7c-4d8e-9f0a-1b2c3d4e5f6a";
    private static final String A_BOOKING = "/bookings/4e5f6a7b-8c9d-4e0f-a1b2-c3d4e5f6a7b8";

    @Test
    void hospitalStaffCannotRecordUnits() throws Exception {
        HttpResponse<String> response = postJson("/units", "{}", signIn(YABA_HOSPITAL_STAFF));

        assertRefused(response);
    }

    @Test
    void donorsCannotRecordUnits() throws Exception {
        HttpResponse<String> response = postJson("/units", "{}", signIn(DONOR));

        assertRefused(response);
    }

    @Test
    void hospitalStaffCannotListABanksUnits() throws Exception {
        HttpResponse<String> response = get("/units", signIn(YABA_HOSPITAL_STAFF));

        assertRefused(response);
    }

    @Test
    void bankStaffCannotRequestUnits() throws Exception {
        HttpResponse<String> response = postJson("/requests", "{}", signIn(LAGOS_BANK_STAFF));

        assertRefused(response);
    }

    @Test
    void donorsCannotSeeRequests() throws Exception {
        HttpResponse<String> response = get("/requests", signIn(DONOR));

        assertRefused(response);
    }

    @Test
    void hospitalStaffCannotApproveTheirOwnRequest() throws Exception {
        HttpResponse<String> response = postJson(A_REQUEST + "/approval", "", signIn(YABA_HOSPITAL_STAFF));

        assertRefused(response);
    }

    @Test
    void hospitalStaffCannotDeclineRequests() throws Exception {
        HttpResponse<String> response = postJson(A_REQUEST + "/declination", "{\"reason\":\"No\"}", signIn(IKORODU_HOSPITAL_STAFF));

        assertRefused(response);
    }

    @Test
    void hospitalStaffCannotIssueUnits() throws Exception {
        assertRefused(postJson(A_REQUEST + "/issue", "", signIn(YABA_HOSPITAL_STAFF)));
    }

    @Test
    void hospitalStaffCannotReverseAnIssue() throws Exception {
        assertRefused(postJson(A_REQUEST + "/issue/reversal", "{\"reason\":\"Mine\"}", signIn(YABA_HOSPITAL_STAFF)));
    }

    @Test
    void hospitalStaffCannotReleaseAnotherHospitalsHold() throws Exception {
        assertRefused(postJson(A_REQUEST + "/hold-release", "{}", signIn(VI_HOSPITAL_STAFF)));
    }

    @Test
    void bankStaffCannotCancelAHospitalsRequest() throws Exception {
        assertRefused(postJson(A_REQUEST + "/cancellation", "{\"reason\":\"Not needed\"}", signIn(LAGOS_BANK_STAFF)));
    }

    @Test
    void theAdministratorCannotCancelAHospitalsRequest() throws Exception {
        // Brief: only the requesting hospital can cancel its own request.
        assertRefused(postJson(A_REQUEST + "/cancellation", "{\"reason\":\"Not needed\"}", signIn(ADMIN)));
    }

    @Test
    void hospitalStaffCannotReadAUnitsHistory() throws Exception {
        assertRefused(get("/units/3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f", signIn(YABA_HOSPITAL_STAFF)));
    }

    @Test
    void onlyBankStaffOpenDonationSlots() throws Exception {
        for (String account : new String[] {YABA_HOSPITAL_STAFF, DONOR}) {
            assertRefused(postJson("/donation-slots", "{}", signIn(account)));
        }
    }

    @Test
    void onlyDonorsBookDonationSlots() throws Exception {
        for (String account : new String[] {LAGOS_BANK_STAFF, YABA_HOSPITAL_STAFF, ADMIN}) {
            assertRefused(postJson(A_SLOT + "/bookings", "", signIn(account)));
        }
    }

    @Test
    void hospitalStaffCannotSeeDonationBookings() throws Exception {
        assertRefused(get("/bookings", signIn(YABA_HOSPITAL_STAFF)));
    }

    @Test
    void donorsCannotRecordAttendance() throws Exception {
        assertRefused(postJson(A_BOOKING + "/attendance", "{\"outcome\":\"ATTENDED\"}", signIn(DONOR)));
    }

    @Test
    void anySignedInRoleCanListOrganisations() throws Exception {
        for (String account : new String[] {ADMIN, LAGOS_BANK_STAFF, VI_HOSPITAL_STAFF, DONOR}) {
            assertThat(get("/organisations", signIn(account)).statusCode()).as(account).isEqualTo(200);
        }
    }

    private static void assertRefused(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("FORBIDDEN");
        assertThat((String) json(response.body(), "$.requestId")).isNotBlank();
    }
}
