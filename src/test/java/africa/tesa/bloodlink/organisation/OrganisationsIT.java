package africa.tesa.bloodlink.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** GET /organisations, checked against the demo banks and hospitals. */
class OrganisationsIT extends AbstractPostgresIT {

    @Test
    void listsTheDemoBanksAndHospitalsByName() throws Exception {
        HttpResponse<String> response = get("/organisations", signIn(YABA_HOSPITAL_STAFF));

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> names = json(response.body(), "$.items[*].name");
        assertThat(names).contains(
            "Lagos Central Blood Bank (demo)", "Lekki Regional Blood Centre (demo)", "Surulere Community Blood Bank (demo)",
            "Yaba General Hospital (demo)", "Ikorodu Children's Hospital (demo)", "Victoria Island Medical Centre (demo)");
    }

    @Test
    void filtersByTypeAndArea() throws Exception {
        String token = signIn(YABA_HOSPITAL_STAFF);

        List<String> bankTypes = json(get("/organisations?type=BLOOD_BANK", token).body(), "$.items[*].type");
        List<String> inIkeja = json(get("/organisations?type=BLOOD_BANK&area=ikeja", token).body(), "$.items[*].name");

        assertThat(bankTypes).isNotEmpty().containsOnly("BLOOD_BANK");
        assertThat(inIkeja).contains("Lagos Central Blood Bank (demo)").doesNotContain("Lekki Regional Blood Centre (demo)");
    }

    @Test
    void anUnknownTypeIsMalformed() throws Exception {
        HttpResponse<String> response = get("/organisations?type=PHARMACY", signIn(YABA_HOSPITAL_STAFF));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) json(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void requiresSignIn() throws Exception {
        assertThat(get("/organisations").statusCode()).isEqualTo(401);
    }
}
