package africa.tesa.bloodlink.unit;

import africa.tesa.bloodlink.organisation.OrganisationType;
import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.unit.BloodType.Component;
import africa.tesa.bloodlink.unit.BloodType.Group;
import africa.tesa.bloodlink.unit.BloodType.Rhesus;
import africa.tesa.bloodlink.web.ApiException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /availability: how many units each bank can supply, by exact group, rhesus type and component. Any signed-in
 * user. Safe (reads only, never changes data).
 *
 * <p>A unit counts as available when it is not past its expiry date (Lagos date) and either is AVAILABLE, or is
 * RESERVED but no hold on it is still running. So a lapsed hold counts as available straight away, even if nothing has
 * released it yet. This is the same rule as the unit_status view; AvailabilityIT checks the two always agree. It is
 * written out here, rather than read from the view, so that each half can use its own partial index.
 */
@RestController
public class AvailabilityController {

    /** Fixed text for the screen (contract: Availability.matching). */
    static final String MATCHING = "Exact group, rhesus type and component only. No substitution.";

    private static final int MAX_AREA_LENGTH = 80;

    private final JdbcClient jdbc;

    public AvailabilityController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/availability")
    Availability search(@RequestParam(required = false) Group bloodGroup,
                        @RequestParam(required = false) Rhesus rhesus,
                        @RequestParam(required = false) Component component,
                        @RequestParam(required = false) UUID bankId,
                        @RequestParam(required = false) String area) {
        if (area != null && area.length() > MAX_AREA_LENGTH) {
            throw ApiException.validation("area", "At most " + MAX_AREA_LENGTH + " characters.");
        }
        // Taken before the query, so the figures are never older than the time shown beside them.
        Instant computedAt = Instant.now();
        List<AvailabilityRow> items = jdbc.sql("""
                SELECT o.id, o.name, o.type, o.area, u.blood_group, u.rhesus, u.component,
                       count(*) AS available_units, min(u.expiry_date) AS earliest_expiry
                  FROM unit u
                  JOIN organisation o ON o.id = u.bank_id
                 WHERE u.expiry_date >= current_date
                   AND (u.state = 'AVAILABLE'
                        OR (u.state = 'RESERVED' AND NOT EXISTS (
                               SELECT 1 FROM reservation r
                                WHERE r.unit_id = u.id AND r.status = 'ACTIVE' AND r.hold_expires_at > now())))
                   AND (CAST(:bloodGroup AS text) IS NULL OR u.blood_group = :bloodGroup)
                   AND (CAST(:rhesus AS text) IS NULL OR u.rhesus = :rhesus)
                   AND (CAST(:component AS text) IS NULL OR u.component = :component)
                   AND (CAST(:bank AS uuid) IS NULL OR u.bank_id = :bank)
                   AND (CAST(:area AS text) IS NULL OR lower(o.area) = lower(:area))
                 GROUP BY o.id, o.name, o.type, o.area, u.blood_group, u.rhesus, u.component
                 ORDER BY u.blood_group, u.rhesus, u.component, o.name""")
            .param("bloodGroup", bloodGroup == null ? null : bloodGroup.name(), Types.VARCHAR)
            .param("rhesus", rhesus == null ? null : rhesus.name(), Types.VARCHAR)
            .param("component", component == null ? null : component.name(), Types.VARCHAR)
            .param("bank", bankId, Types.OTHER)
            .param("area", area, Types.VARCHAR)
            .query((rs, row) -> new AvailabilityRow(
                new OrganisationView(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    OrganisationType.valueOf(rs.getString("type")),
                    rs.getString("area")),
                rs.getString("blood_group"),
                rs.getString("rhesus"),
                rs.getString("component"),
                rs.getInt("available_units"),
                rs.getObject("earliest_expiry", LocalDate.class)))
            .list();
        return new Availability(computedAt, MATCHING, items);
    }

    /** Contract: Availability. */
    public record Availability(Instant computedAt, String matching, List<AvailabilityRow> items) {
    }

    /** Contract: AvailabilityRow. availableUnits counts units (bags). */
    public record AvailabilityRow(OrganisationView bank, String bloodGroup, String rhesus, String component,
                                  int availableUnits, LocalDate earliestExpiryDate) {
    }
}
