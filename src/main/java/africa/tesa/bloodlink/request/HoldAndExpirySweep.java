package africa.tesa.bloodlink.request;

import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The background job: writes down what the views already show (ADR-003). Every read is correct without it: the
 * unit_status, reservation_status and blood_request_status views compute lapsed holds and expired units at read time. So
 * if this job does not run for an hour, nothing a user sees changes; it only brings the stored states and the history
 * up to date.
 *
 * <p>Once a minute, in this order (the same precedence as the views: an expired unit outranks a lapsed hold):
 * <ol>
 *   <li><b>Reserved units past their expiry date:</b> the hold becomes UNIT_EXPIRED, the unit EXPIRED, the request
 *       UNIT_EXPIRED.</li>
 *   <li><b>Lapsed holds:</b> the hold becomes LAPSED and the unit goes back to stock (AVAILABLE, or EXPIRED if past its
 *       date); a request left with no running or issued hold becomes LAPSED.</li>
 *   <li><b>Available units past their expiry date:</b> EXPIRED.</li>
 * </ol>
 * Each request is handled in its own short transaction that locks the request row first, the same order as approval,
 * issue and cancellation, with SKIP LOCKED: a request someone is acting on right now is left for the next run, so the
 * job never waits for a user and never deadlocks with one. Every change gets a System entry in the history.
 */
@Component
public class HoldAndExpirySweep {

    private static final Logger LOG = LoggerFactory.getLogger(HoldAndExpirySweep.class);
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public HoldAndExpirySweep(JdbcClient jdbc, TransactionTemplate transactions,
                              @Value("${app.sweep.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    /** What one run changed. */
    public record Result(int requestsUnitExpired, int requestsLapsed, int holdsLapsed, int unitsExpired) {
    }

    @Scheduled(fixedDelayString = "${app.sweep.every:PT1M}", initialDelayString = "${app.sweep.first-run-after:PT30S}")
    void scheduled() {
        if (!enabled) {
            return;
        }
        Result result = runOnce(null);
        if (result.requestsUnitExpired() + result.holdsLapsed() + result.unitsExpired() > 0) {
            LOG.info("Sweep: {} requests UNIT_EXPIRED, {} holds lapsed ({} requests LAPSED), {} units expired",
                result.requestsUnitExpired(), result.holdsLapsed(), result.requestsLapsed(), result.unitsExpired());
        }
    }

    /**
     * One full pass, over every bank (bank null) or one bank. Public so tests can run it directly on a bank of their own;
     * the schedule is switched off in tests.
     */
    public Result runOnce(UUID bank) {
        int unitExpired = 0;
        for (UUID request : jdbc.sql("""
                SELECT DISTINCT r.request_id FROM reservation r JOIN unit u ON u.id = r.unit_id
                 WHERE r.status = 'ACTIVE' AND u.expiry_date < current_date
                   AND (CAST(:bank AS uuid) IS NULL OR u.bank_id = :bank)
                 LIMIT :batch""").param("bank", bank, Types.OTHER).param("batch", BATCH).query(UUID.class).list()) {
            Boolean changed = transactions.execute(status -> expireReservedUnits(request));
            unitExpired += Boolean.TRUE.equals(changed) ? 1 : 0;
        }
        int holds = 0;
        int lapsed = 0;
        // A hold on a unit that has also expired is step 1's (UNIT_EXPIRED), as in the views.
        for (UUID request : jdbc.sql("""
                SELECT DISTINCT r.request_id FROM reservation r JOIN unit u ON u.id = r.unit_id
                 WHERE r.status = 'ACTIVE' AND r.hold_expires_at <= now() AND u.expiry_date >= current_date
                   AND (CAST(:bank AS uuid) IS NULL OR u.bank_id = :bank)
                 LIMIT :batch""").param("bank", bank, Types.OTHER).param("batch", BATCH).query(UUID.class).list()) {
            int[] counts = transactions.execute(status -> lapseHolds(request));
            if (counts != null) {
                holds += counts[0];
                lapsed += counts[1];
            }
        }
        Integer expired = transactions.execute(status -> expireAvailableUnits(bank));
        return new Result(unitExpired, lapsed, holds, expired == null ? 0 : expired);
    }

    /** Step 1 for one request. False when the request is busy (locked by a user) or nothing needed doing. */
    private boolean expireReservedUnits(UUID request) {
        if (!lockIfFree(request)) {
            return false;
        }
        List<UUID> units = lockUnits(request, "u.expiry_date < current_date");
        if (units.isEmpty()) {
            return false;
        }
        jdbc.sql("""
                UPDATE reservation SET status = 'UNIT_EXPIRED', ended_at = now()
                 WHERE request_id = :request AND unit_id IN (:units) AND status = 'ACTIVE'""")
            .param("request", request).param("units", units).update();
        jdbc.sql("UPDATE unit SET state = 'EXPIRED', version = version + 1 WHERE id IN (:units) AND state = 'RESERVED'")
            .param("units", units).update();
        for (UUID unit : units) {
            history("UNIT", unit, "EXPIRED", "Passed its expiry date while reserved; it can never be issued");
        }
        int changed = jdbc.sql("UPDATE blood_request SET state = 'UNIT_EXPIRED' WHERE id = ? AND state = 'APPROVED'")
            .param(request).update();
        if (changed > 0) {
            history("REQUEST", request, "UNIT_EXPIRED", "A reserved unit passed its expiry date before issue. "
                + "The hospital can make a new request");
        }
        return changed > 0;
    }

    /** Step 2 for one request: {holds lapsed, 1 if the request became LAPSED}. Null when the request is busy. */
    private int[] lapseHolds(UUID request) {
        if (!lockIfFree(request)) {
            return null;
        }
        List<UUID> units = lockUnits(request, "r.hold_expires_at <= now() AND u.expiry_date >= current_date");
        if (units.isEmpty()) {
            return new int[] {0, 0};
        }
        jdbc.sql("""
                UPDATE reservation SET status = 'LAPSED', ended_at = now()
                 WHERE request_id = :request AND unit_id IN (:units) AND status = 'ACTIVE'""")
            .param("request", request).param("units", units).update();
        List<Restored> restored = jdbc.sql("""
                UPDATE unit SET state = CASE WHEN expiry_date < current_date THEN 'EXPIRED' ELSE 'AVAILABLE' END,
                                version = version + 1
                 WHERE id IN (:units) AND state = 'RESERVED'
                RETURNING id, state""")
            .param("units", units)
            .query((rs, row) -> new Restored(rs.getObject("id", UUID.class), rs.getString("state")))
            .list();
        for (Restored unit : restored) {
            if ("EXPIRED".equals(unit.state())) {
                history("UNIT", unit.id(), "EXPIRED", "Hold ended; past its expiry date, so not back in stock");
            } else {
                history("UNIT", unit.id(), "RETURNED_TO_STOCK", "Hold ended before issue");
            }
        }
        int changed = jdbc.sql("""
                UPDATE blood_request br SET state = 'LAPSED'
                 WHERE br.id = ? AND br.state = 'APPROVED'
                   AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.request_id = br.id AND r.status IN ('ACTIVE', 'ISSUED'))""")
            .param(request).update();
        if (changed > 0) {
            history("REQUEST", request, "HOLD_LAPSED", "Hold ended before the units were issued; units returned to stock. "
                + "The hospital can make a new request");
        }
        return new int[] {units.size(), changed};
    }

    /** Step 3: available units past their date. Units being reserved right now are skipped until the next run. */
    private int expireAvailableUnits(UUID bank) {
        List<UUID> units = jdbc.sql("""
                UPDATE unit SET state = 'EXPIRED', version = version + 1
                 WHERE id IN (SELECT id FROM unit WHERE state = 'AVAILABLE' AND expiry_date < current_date
                                 AND (CAST(:bank AS uuid) IS NULL OR bank_id = :bank)
                               LIMIT :batch FOR UPDATE SKIP LOCKED)
                RETURNING id""")
            .param("bank", bank, Types.OTHER).param("batch", BATCH * 5).query(UUID.class).list();
        for (UUID unit : units) {
            history("UNIT", unit, "EXPIRED", "Passed its expiry date");
        }
        return units.size();
    }

    /**
     * Locks the units of the request's active holds that match the condition, skipping any another transaction holds.
     * Units are locked before their reservations are changed, the same order as approval, so the two never wait on
     * each other.
     */
    private List<UUID> lockUnits(UUID request, String condition) {
        return jdbc.sql("""
                SELECT u.id FROM reservation r JOIN unit u ON u.id = r.unit_id
                 WHERE r.request_id = :request AND r.status = 'ACTIVE' AND %s
                 ORDER BY u.id
                   FOR UPDATE OF u SKIP LOCKED""".formatted(condition))
            .param("request", request).query(UUID.class).list();
    }

    private boolean lockIfFree(UUID request) {
        return !jdbc.sql("SELECT id FROM blood_request WHERE id = ? FOR UPDATE SKIP LOCKED")
            .param(request).query(UUID.class).list().isEmpty();
    }

    private void history(String entityType, UUID id, String event, String note) {
        jdbc.sql("INSERT INTO activity (entity_type, entity_id, event, actor_id, note) VALUES (?, ?, ?, NULL, ?)")
            .params(entityType, id, event, note).update();
    }

    private record Restored(UUID id, String state) {
    }
}
