package africa.tesa.bloodlink;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 009-request-status-indexable.sql rewrote blood_request_status so indexes can answer it (docs/data-model.md, index
 * analysis). This checks the rewrite changed nothing: for every request in the database (the seed has LAPSED and
 * UNIT_EXPIRED cases, and every other test adds its own), the view gives the same state as the original rule, which
 * read each hold's status through reservation_status.
 */
class ViewEquivalenceIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void theIndexableRequestViewGivesTheSameStateAsTheOriginalRule() {
        Integer different = jdbc.sql("""
                SELECT count(*)::int
                  FROM blood_request br
                  JOIN blood_request_status v ON v.id = br.id
                 WHERE v.effective_state IS DISTINCT FROM CASE
                           WHEN br.state = 'APPROVED' AND EXISTS (
                               SELECT 1 FROM reservation_status rs
                                WHERE rs.request_id = br.id AND rs.effective_status = 'UNIT_EXPIRED') THEN 'UNIT_EXPIRED'
                           WHEN br.state = 'APPROVED' AND NOT EXISTS (
                               SELECT 1 FROM reservation_status rs
                                WHERE rs.request_id = br.id AND rs.effective_status IN ('ACTIVE', 'ISSUED')) THEN 'LAPSED'
                           ELSE br.state END""")
            .query(Integer.class).single();
        Integer covered = jdbc.sql("""
                SELECT count(DISTINCT effective_state)::int FROM blood_request_status
                 WHERE effective_state IN ('LAPSED', 'UNIT_EXPIRED', 'APPROVED', 'PENDING')""")
            .query(Integer.class).single();

        assertThat(covered).as("the seed covers the states the rewrite computes").isEqualTo(4);
        assertThat(different).as("requests whose state changed").isZero();
    }
}
