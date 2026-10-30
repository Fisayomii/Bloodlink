package africa.tesa.bloodlink.dashboard;

import africa.tesa.bloodlink.auth.CurrentUser;
import africa.tesa.bloodlink.auth.Role;
import africa.tesa.bloodlink.organisation.OrganisationType;
import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.request.BloodRequestView.UnitSummary;
import africa.tesa.bloodlink.unit.UnitService;
import africa.tesa.bloodlink.unit.UnitView;
import africa.tesa.bloodlink.web.ApiException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /dashboard. Every figure is derived from the records at the moment of the request; nothing is stored or cached.
 * The figures come from the same views as every other read (unit_status, reservation_status, blood_request_status), so
 * a lapsed hold or an expired unit is counted correctly even if the sweep has not run.
 *
 * <p>All the queries run in one read-only transaction (REPEATABLE READ), so they see the same moment: the available
 * count and the expiring list can never disagree because a unit changed between two queries.
 */
@Service
public class DashboardService {

    private static final int LIST_LIMIT = 100;
    private static final int RECENT_ACTIVITY = 20;
    /** The contract allows 200 characters for the actor; "Name, Organisation" could be longer. */
    private static final int ACTOR_LIMIT = 200;

    private final JdbcClient jdbc;
    private final int lowStockThreshold;
    private final Duration holdsEndingWithin;
    private final int expiringWithinDays;

    public DashboardService(JdbcClient jdbc,
                            @Value("${app.dashboard.low-stock-threshold:3}") int lowStockThreshold,
                            @Value("${app.dashboard.holds-ending-within:60m}") Duration holdsEndingWithin,
                            @Value("${app.dashboard.expiring-within-days:3}") int expiringWithinDays) {
        this.jdbc = jdbc;
        this.lowStockThreshold = lowStockThreshold;
        this.holdsEndingWithin = holdsEndingWithin;
        this.expiringWithinDays = expiringWithinDays;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dashboard forCaller(UUID requestedBank) {
        CurrentUser user = CurrentUser.get();
        UUID bankId;
        if (user.is(Role.BANK_STAFF)) {
            // Bank staff always see their own bank; a bankId they send is ignored (contract).
            bankId = user.organisationId();
        } else if (user.is(Role.ADMIN)) {
            bankId = requestedBank;
        } else {
            throw ApiException.forbidden("Only bank staff and the administrator can see the dashboard.");
        }
        OrganisationView bank = bankId == null ? null : bank(bankId);
        // Taken before the queries, so no figure is older than the time shown beside it.
        Instant computedAt = Instant.now();

        List<GroupCount> byGroup = availableByGroup(bankId);
        List<GroupCount> low = byGroup.stream().filter(group -> group.availableUnits() < lowStockThreshold).toList();
        return new Dashboard(computedAt, bank, lowStockThreshold, (int) holdsEndingWithin.toMinutes(), expiringWithinDays,
            byGroup, low, openRequests(bankId), holdsEndingSoon(bankId), unitsExpiringSoon(bankId), recentActivity(bankId));
    }

    private OrganisationView bank(UUID id) {
        return jdbc.sql("SELECT id, name, type, area FROM organisation WHERE id = ? AND type = 'BLOOD_BANK'")
            .param(id)
            .query((rs, row) -> new OrganisationView(rs.getObject("id", UUID.class), rs.getString("name"),
                OrganisationType.valueOf(rs.getString("type")), rs.getString("area")))
            .optional()
            // The contract lists 400 for an invalid query value, not 404.
            .orElseThrow(() -> ApiException.malformed("bankId", "No blood bank with that id."));
    }

    /** All eight groups, zero included, every component counted. */
    private List<GroupCount> availableByGroup(UUID bank) {
        return jdbc.sql("""
                SELECT g.blood_group, r.rhesus, count(u.id)::int AS available
                  FROM (VALUES (1, 'A'), (2, 'B'), (3, 'AB'), (4, 'O')) AS g(position, blood_group)
                 CROSS JOIN (VALUES (1, 'POSITIVE'), (2, 'NEGATIVE')) AS r(position, rhesus)
                  LEFT JOIN unit_status u
                         ON u.blood_group = g.blood_group AND u.rhesus = r.rhesus AND u.effective_state = 'AVAILABLE'
                        AND (CAST(:bank AS uuid) IS NULL OR u.bank_id = :bank)
                 GROUP BY g.position, g.blood_group, r.position, r.rhesus
                 ORDER BY g.position, r.position""")
            .param("bank", bank, Types.OTHER)
            .query((rs, row) -> new GroupCount(rs.getString("blood_group"), rs.getString("rhesus"), rs.getInt("available")))
            .list();
    }

    private OpenRequests openRequests(UUID bank) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE effective_state = 'PENDING')::int AS pending,
                       count(*) FILTER (WHERE effective_state = 'APPROVED')::int AS approved
                  FROM blood_request_status
                 WHERE CAST(:bank AS uuid) IS NULL OR bank_id = :bank""")
            .param("bank", bank, Types.OTHER)
            .query((rs, row) -> new OpenRequests(rs.getInt("pending"), rs.getInt("approved")))
            .single();
    }

    private List<HoldEndingSoon> holdsEndingSoon(UUID bank) {
        return jdbc.sql("""
                SELECT rs.request_id, br.reference AS request_reference, rs.hold_expires_at,
                       h.id AS hospital_id, h.name AS hospital_name, h.type AS hospital_type, h.area AS hospital_area,
                       u.id AS unit_id, u.reference AS unit_reference, u.expiry_date
                  FROM reservation_status rs
                  JOIN blood_request br ON br.id = rs.request_id
                  JOIN organisation h ON h.id = br.hospital_id
                  JOIN unit u ON u.id = rs.unit_id
                 WHERE rs.effective_status = 'ACTIVE'
                   AND rs.hold_expires_at <= now() + make_interval(mins => :minutes)
                   AND (CAST(:bank AS uuid) IS NULL OR br.bank_id = :bank)
                 ORDER BY rs.hold_expires_at, u.reference
                 LIMIT :limit""")
            .param("minutes", (int) holdsEndingWithin.toMinutes())
            .param("bank", bank, Types.OTHER)
            .param("limit", LIST_LIMIT)
            .query((rs, row) -> new HoldEndingSoon(
                rs.getObject("request_id", UUID.class),
                rs.getString("request_reference"),
                new OrganisationView(rs.getObject("hospital_id", UUID.class), rs.getString("hospital_name"),
                    OrganisationType.valueOf(rs.getString("hospital_type")), rs.getString("hospital_area")),
                new UnitSummary(rs.getObject("unit_id", UUID.class), rs.getString("unit_reference"),
                    rs.getObject("expiry_date", LocalDate.class)),
                rs.getObject("hold_expires_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    /** Available or reserved units whose expiry date is today or within the window (Lagos dates). */
    private List<UnitView> unitsExpiringSoon(UUID bank) {
        return jdbc.sql("""
                SELECT u.id, u.reference, u.blood_group, u.rhesus, u.component, u.collection_date, u.expiry_date,
                       u.effective_state, u.recorded_at, o.id AS org_id, o.name AS org_name, o.type AS org_type, o.area AS org_area
                  FROM unit_status u
                  JOIN organisation o ON o.id = u.bank_id
                 WHERE u.effective_state IN ('AVAILABLE', 'RESERVED')
                   AND u.expiry_date BETWEEN current_date AND current_date + :days
                   AND (CAST(:bank AS uuid) IS NULL OR u.bank_id = :bank)
                 ORDER BY u.expiry_date, u.reference
                 LIMIT :limit""")
            .param("days", expiringWithinDays)
            .param("bank", bank, Types.OTHER)
            .param("limit", LIST_LIMIT)
            .query(UnitService::toView)
            .list();
    }

    /** The newest history entries for this bank's units and requests (or everything, for the administrator). */
    private List<ActivityEntry> recentActivity(UUID bank) {
        return jdbc.sql("""
                SELECT a.at, a.event, a.entity_type, a.note,
                       COALESCE(un.reference, br.reference, '') AS reference,
                       left(CASE WHEN us.id IS NULL THEN 'System'
                                 WHEN o.id IS NULL THEN us.display_name
                                 ELSE us.display_name || ', ' || o.name END, :actorLimit) AS actor
                  FROM activity a
                  LEFT JOIN unit un ON a.entity_type = 'UNIT' AND un.id = a.entity_id
                  LEFT JOIN blood_request br ON a.entity_type = 'REQUEST' AND br.id = a.entity_id
                  LEFT JOIN app_user us ON us.id = a.actor_id
                  LEFT JOIN organisation o ON o.id = us.organisation_id
                 WHERE CAST(:bank AS uuid) IS NULL OR un.bank_id = :bank OR br.bank_id = :bank
                 ORDER BY a.at DESC, a.id DESC
                 LIMIT :limit""")
            .param("actorLimit", ACTOR_LIMIT)
            .param("bank", bank, Types.OTHER)
            .param("limit", RECENT_ACTIVITY)
            .query((rs, row) -> new ActivityEntry(
                rs.getObject("at", OffsetDateTime.class).toInstant(),
                rs.getString("event"),
                rs.getString("entity_type"),
                rs.getString("reference"),
                rs.getString("actor"),
                rs.getString("note")))
            .list();
    }
}
