package africa.tesa.bloodlink.request;

import africa.tesa.bloodlink.organisation.OrganisationType;
import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.request.BloodRequestView.HistoryEntry;
import africa.tesa.bloodlink.request.BloodRequestView.Reservation;
import africa.tesa.bloodlink.request.BloodRequestView.UnitSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads requests with their reservations and history, always through the status views. Read-only: nothing here
 * changes data. A page of requests costs three queries (requests, their reservations, their history), however many
 * requests are on the page.
 */
@Component
class RequestReader {

    /** Who may see a request: its hospital, its bank, or (when both are null) everyone, for the administrator. */
    record Scope(UUID hospitalId, UUID bankId) {

        static final Scope EVERYTHING = new Scope(null, null);
    }

    private static final String SELECT_REQUESTS = """
            SELECT br.id, br.reference, br.blood_group, br.rhesus, br.component, br.quantity, br.urgency, br.needed_by,
                   br.effective_state, br.decline_reason, br.created_at,
                   h.id AS h_id, h.name AS h_name, h.type AS h_type, h.area AS h_area,
                   b.id AS b_id, b.name AS b_name, b.type AS b_type, b.area AS b_area
              FROM blood_request_status br
              JOIN organisation h ON h.id = br.hospital_id
              JOIN organisation b ON b.id = br.bank_id
            """;

    private static final String SCOPE_FILTERS = """
             WHERE (CAST(:hospital AS uuid) IS NULL OR br.hospital_id = :hospital)
               AND (CAST(:bank AS uuid) IS NULL OR br.bank_id = :bank)
               AND (CAST(:state AS text) IS NULL OR br.effective_state = :state)
            """;

    private final JdbcClient jdbc;

    RequestReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One request, if it exists and the scope may see it. Not-visible and not-found look the same (404). */
    Optional<BloodRequestView> find(UUID id, Scope scope) {
        List<Header> headers = jdbc.sql(SELECT_REQUESTS + SCOPE_FILTERS + " AND br.id = :id")
            .param("hospital", scope.hospitalId(), Types.OTHER)
            .param("bank", scope.bankId(), Types.OTHER)
            .param("state", null, Types.VARCHAR)
            .param("id", id)
            .query(RequestReader::header)
            .list();
        return complete(headers).stream().findFirst();
    }

    /** A page of requests the scope may see, newest first. */
    List<BloodRequestView> page(Scope scope, String state, int page, int size) {
        List<Header> headers = jdbc.sql(SELECT_REQUESTS + SCOPE_FILTERS
                + " ORDER BY br.created_at DESC, br.id LIMIT :limit OFFSET :offset")
            .param("hospital", scope.hospitalId(), Types.OTHER)
            .param("bank", scope.bankId(), Types.OTHER)
            .param("state", state, Types.VARCHAR)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(RequestReader::header)
            .list();
        return complete(headers);
    }

    long count(Scope scope, String state) {
        return jdbc.sql("SELECT count(*) FROM blood_request_status br" + SCOPE_FILTERS)
            .param("hospital", scope.hospitalId(), Types.OTHER)
            .param("bank", scope.bankId(), Types.OTHER)
            .param("state", state, Types.VARCHAR)
            .query(Long.class)
            .single();
    }

    /** Adds reservations and history to each request: one query each for the whole page (ids expand to IN (...)). */
    private List<BloodRequestView> complete(List<Header> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = headers.stream().map(Header::id).toList();
        Map<UUID, List<Reservation>> reservations = new HashMap<>();
        jdbc.sql("""
                SELECT rs.id, rs.request_id, rs.effective_status, rs.hold_expires_at, rs.created_at,
                       u.id AS unit_id, u.reference AS unit_reference, u.expiry_date AS unit_expiry
                  FROM reservation_status rs
                  JOIN unit u ON u.id = rs.unit_id
                 WHERE rs.request_id IN (:ids)
                 ORDER BY rs.created_at, u.reference""")
            .param("ids", ids)
            .query((ResultSet rs) -> {
                reservations.computeIfAbsent(rs.getObject("request_id", UUID.class), key -> new ArrayList<>())
                    .add(new Reservation(
                        rs.getObject("id", UUID.class),
                        new UnitSummary(rs.getObject("unit_id", UUID.class), rs.getString("unit_reference"),
                            rs.getObject("unit_expiry", LocalDate.class)),
                        rs.getString("effective_status"),
                        rs.getTimestamp("hold_expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()));
            });
        Map<UUID, List<HistoryEntry>> history = new HashMap<>();
        jdbc.sql("""
                SELECT a.entity_id, a.at, a.event, a.note,
                       CASE WHEN u.id IS NULL THEN 'System'
                            WHEN o.id IS NULL THEN u.display_name
                            ELSE u.display_name || ', ' || o.name END AS actor
                  FROM activity a
                  LEFT JOIN app_user u ON u.id = a.actor_id
                  LEFT JOIN organisation o ON o.id = u.organisation_id
                 WHERE a.entity_type = 'REQUEST' AND a.entity_id IN (:ids)
                 ORDER BY a.at, a.id""")
            .param("ids", ids)
            .query((ResultSet rs) -> {
                history.computeIfAbsent(rs.getObject("entity_id", UUID.class), key -> new ArrayList<>())
                    .add(new HistoryEntry(rs.getTimestamp("at").toInstant(), rs.getString("event"), rs.getString("actor"),
                        rs.getString("note")));
            });
        return headers.stream()
            .map(h -> h.withDetails(reservations.getOrDefault(h.id(), List.of()), history.getOrDefault(h.id(), List.of())))
            .toList();
    }

    private static Header header(ResultSet rs, int row) throws SQLException {
        return new Header(
            rs.getObject("id", UUID.class),
            rs.getString("reference"),
            organisation(rs, "h_"),
            organisation(rs, "b_"),
            rs.getString("blood_group"),
            rs.getString("rhesus"),
            rs.getString("component"),
            rs.getInt("quantity"),
            rs.getString("urgency"),
            rs.getTimestamp("needed_by").toInstant(),
            rs.getString("effective_state"),
            rs.getString("decline_reason"),
            rs.getTimestamp("created_at").toInstant());
    }

    private static OrganisationView organisation(ResultSet rs, String prefix) throws SQLException {
        return new OrganisationView(
            rs.getObject(prefix + "id", UUID.class),
            rs.getString(prefix + "name"),
            OrganisationType.valueOf(rs.getString(prefix + "type")),
            rs.getString(prefix + "area"));
    }

    /** A request row before its reservations and history are attached. */
    private record Header(UUID id, String reference, OrganisationView hospital, OrganisationView bank, String bloodGroup,
                          String rhesus, String component, int quantity, String urgency, Instant neededBy,
                          String state, String declineReason, Instant createdAt) {

        BloodRequestView withDetails(List<Reservation> reservations, List<HistoryEntry> history) {
            return new BloodRequestView(id, reference, hospital, bank, bloodGroup, rhesus, component, quantity, urgency,
                neededBy, state, declineReason, createdAt, reservations, history);
        }
    }
}
