package africa.tesa.bloodlink.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Donor accounts of their own for one test (password Demo-Password-1), so races between donors are exact. */
public final class TestDonors {

    private TestDonors() {
    }

    /** Creates this many donors and returns their emails, for AbstractPostgresIT.signIn. */
    public static List<String> create(JdbcClient jdbc, int count) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<String> emails = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String email = "donor-" + tag + "-" + i + "@test-donor.demo";
            jdbc.sql("INSERT INTO app_user (email, display_name, password_hash, role) VALUES (?, ?, ?, 'DONOR')")
                .params(email, "Test donor " + tag + "-" + i, TestBank.DEMO_PASSWORD_HASH)
                .update();
            emails.add(email);
        }
        return emails;
    }
}
