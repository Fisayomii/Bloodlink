package africa.tesa.bloodlink.unit;

import africa.tesa.bloodlink.auth.CurrentUser;
import africa.tesa.bloodlink.auth.Role;
import africa.tesa.bloodlink.organisation.OrganisationType;
import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.unit.BloodType.Component;
import africa.tesa.bloodlink.unit.BloodType.Group;
import africa.tesa.bloodlink.unit.BloodType.Rhesus;
import africa.tesa.bloodlink.unit.BloodType.UnitState;
import africa.tesa.bloodlink.unit.UnitController.UnitCreate;
import africa.tesa.bloodlink.unit.UnitController.UnitPage;
import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.DateRange;
import africa.tesa.bloodlink.web.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recording and listing units. */
@Service
public class UnitService {

    static final int MAX_PAGE_SIZE = 100;

    /** Filters shared by the count and the page query; each applies only when given. */
    private static final String LIST_FILTERS = """
             WHERE (CAST(:bank AS uuid) IS NULL OR u.bank_id = :bank)
               AND (CAST(:state AS text) IS NULL OR u.effective_state = :state)
               AND (CAST(:bloodGroup AS text) IS NULL OR u.blood_group = :bloodGroup)
               AND (CAST(:rhesus AS text) IS NULL OR u.rhesus = :rhesus)
               AND (CAST(:component AS text) IS NULL OR u.component = :component)
            """;

    private final JdbcClient jdbc;
    private final ZoneId zone;

    public UnitService(JdbcClient jdbc, @Value("${app.time-zone}") ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    /**
     * Records a received unit at the signed-in staff member's own bank, and writes RECORDED to its history, in one
     * transaction: either both are saved or neither is.
     */
    @Transactional
    public UnitView record(UnitCreate body) {
        CurrentUser user = CurrentUser.get();
        if (!user.is(Role.BANK_STAFF) || user.organisationId() == null) {
            // SecurityConfig already limits this path to bank staff; this guards the service if that ever changes.
            throw ApiException.forbidden("Only blood bank staff can record units.");
        }
        DateRange.checkBody("collectionDate", body.collectionDate());
        DateRange.checkBody("expiryDate", body.expiryDate());
        LocalDate today = LocalDate.now(zone);
        if (body.collectionDate().isAfter(today)) {
            throw ApiException.validation("collectionDate", "Cannot be in the future.");
        }
        if (!body.expiryDate().isAfter(body.collectionDate())) {
            throw ApiException.validation("expiryDate", "Must be after collectionDate.");
        }
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO unit (bank_id, reference, blood_group, rhesus, component, collection_date, expiry_date, recorded_by)
                    VALUES (:bank, :reference, :bloodGroup, :rhesus, :component, :collected, :expires, :user)
                    RETURNING id""")
                .param("bank", user.organisationId())
                .param("reference", body.reference())
                .param("bloodGroup", body.bloodGroup())
                .param("rhesus", body.rhesus())
                .param("component", body.component())
                .param("collected", body.collectionDate())
                .param("expires", body.expiryDate())
                .param("user", user.id())
                .query(UUID.class)
                .single();
        } catch (DuplicateKeyException duplicate) {
            // unit_bank_reference_key: this bank already holds a unit with that reference.
            throw ApiException.conflict(ErrorCode.DUPLICATE_UNIT_REFERENCE,
                "This bank already has a unit with reference " + body.reference() + ".");
        }
        jdbc.sql("INSERT INTO activity (entity_type, entity_id, event, actor_id) VALUES ('UNIT', :unit, 'RECORDED', :user)")
            .param("unit", id)
            .param("user", user.id())
            .update();
        return find(id);
    }

    /**
     * One page of units, soonest expiry first. Bank staff always see only their own bank, whatever bankId they send;
     * the administrator sees every bank and may narrow by bankId.
     */
    public UnitPage list(UnitFilter filter, int page, int size) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "page must be 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        CurrentUser user = CurrentUser.get();
        UUID bank = user.is(Role.ADMIN) ? filter.bankId() : user.organisationId();
        if (bank == null && !user.is(Role.ADMIN)) {
            throw ApiException.forbidden("Only blood bank staff and the administrator can list units.");
        }

        long total = filtered(jdbc.sql("SELECT count(*) FROM unit_status u" + LIST_FILTERS), bank, filter)
            .query(Long.class).single();
        List<UnitView> items = filtered(jdbc.sql("""
                SELECT u.id, u.reference, u.blood_group, u.rhesus, u.component, u.collection_date, u.expiry_date,
                       u.effective_state, u.recorded_at, o.id AS org_id, o.name AS org_name, o.type AS org_type, o.area AS org_area
                  FROM unit_status u
                  JOIN organisation o ON o.id = u.bank_id
                """ + LIST_FILTERS + """
                 ORDER BY u.expiry_date, u.reference
                 LIMIT :limit OFFSET :offset"""), bank, filter)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(UnitService::toView)
            .list();
        return new UnitPage(items, page, size, total);
    }

    /**
     * GET /units/{unitId}: one unit and its full history. Bank staff see only their own bank's units; another bank's unit
     * answers 404, so its existence is not revealed. The administrator sees any unit. Safe: nothing is changed.
     */
    public UnitDetailView detail(UUID id) {
        CurrentUser user = CurrentUser.get();
        UnitView unit = jdbc.sql("SELECT bank_id FROM unit WHERE id = ?").param(id).query(UUID.class).optional()
            .filter(bank -> user.is(Role.ADMIN) || (user.is(Role.BANK_STAFF) && user.worksFor(bank)))
            .map(bank -> find(id))
            .orElseThrow(() -> ApiException.notFound("Unit not found."));
        List<UnitDetailView.HistoryEntry> history = jdbc.sql("""
                SELECT a.at, a.event, a.note,
                       CASE WHEN u.id IS NULL THEN 'System'
                            WHEN o.id IS NULL THEN u.display_name
                            ELSE u.display_name || ', ' || o.name END AS actor
                  FROM activity a
                  LEFT JOIN app_user u ON u.id = a.actor_id
                  LEFT JOIN organisation o ON o.id = u.organisation_id
                 WHERE a.entity_type = 'UNIT' AND a.entity_id = :unit
                 ORDER BY a.at, a.id""")
            .param("unit", id)
            .query((rs, row) -> new UnitDetailView.HistoryEntry(rs.getTimestamp("at").toInstant(), rs.getString("event"),
                rs.getString("actor"), rs.getString("note")))
            .list();
        return UnitDetailView.of(unit, history);
    }

    /** One unit with its effective state. */
    UnitView find(UUID id) {
        return jdbc.sql("""
                SELECT u.id, u.reference, u.blood_group, u.rhesus, u.component, u.collection_date, u.expiry_date,
                       u.effective_state, u.recorded_at, o.id AS org_id, o.name AS org_name, o.type AS org_type, o.area AS org_area
                  FROM unit_status u
                  JOIN organisation o ON o.id = u.bank_id
                 WHERE u.id = :id""")
            .param("id", id)
            .query(UnitService::toView)
            .single();
    }

    /** Binds the filter values with explicit SQL types, so an absent filter is an unambiguous NULL. */
    private static JdbcClient.StatementSpec filtered(JdbcClient.StatementSpec sql, UUID bank, UnitFilter filter) {
        return sql
            .param("bank", bank, Types.OTHER)
            .param("state", filter.state() == null ? null : filter.state().name(), Types.VARCHAR)
            .param("bloodGroup", filter.bloodGroup() == null ? null : filter.bloodGroup().name(), Types.VARCHAR)
            .param("rhesus", filter.rhesus() == null ? null : filter.rhesus().name(), Types.VARCHAR)
            .param("component", filter.component() == null ? null : filter.component().name(), Types.VARCHAR);
    }

    private static UnitView toView(ResultSet rs, int row) throws SQLException {
        return new UnitView(
            rs.getObject("id", UUID.class),
            rs.getString("reference"),
            rs.getString("blood_group"),
            rs.getString("rhesus"),
            rs.getString("component"),
            rs.getObject("collection_date", LocalDate.class),
            rs.getObject("expiry_date", LocalDate.class),
            rs.getString("effective_state"),
            new OrganisationView(
                rs.getObject("org_id", UUID.class),
                rs.getString("org_name"),
                OrganisationType.valueOf(rs.getString("org_type")),
                rs.getString("org_area")),
            rs.getTimestamp("recorded_at").toInstant());
    }

    /** Optional filters for GET /units. */
    public record UnitFilter(UUID bankId, UnitState state, Group bloodGroup, Rhesus rhesus, Component component) {
    }
}
