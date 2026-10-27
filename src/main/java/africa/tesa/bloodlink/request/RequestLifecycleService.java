package africa.tesa.bloodlink.request;

import africa.tesa.bloodlink.auth.CurrentUser;
import africa.tesa.bloodlink.auth.Role;
import africa.tesa.bloodlink.request.RequestReader.Scope;
import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens to a request after the bank's decision: issuing the reserved units, reversing an issue recorded in
 * error, the hospital cancelling, and the bank releasing a routine hold for an emergency (ADR-002).
 *
 * <p>Every method is one transaction that starts by locking the request row ({@code SELECT ... FOR UPDATE}), so two
 * changes to the same request (an issue and a cancellation arriving together, say) run one after the other: the
 * second sees what the first did and is refused cleanly. Unit rows are then locked and changed only with guarded
 * updates ({@code ... WHERE state = 'RESERVED'}), the same pattern as approval. Any refusal throws, which rolls the
 * whole transaction back.
 */
@Service
public class RequestLifecycleService {

    private static final int NOTE_LIMIT = 500;

    private final JdbcClient jdbc;
    private final RequestReader reader;
    private final RequestService requests;

    public RequestLifecycleService(JdbcClient jdbc, RequestReader reader, RequestService requests) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.requests = requests;
    }

    /**
     * Records every reserved unit of an APPROVED request as issued. All or nothing. Allowed only while every hold is
     * still running and no reserved unit is past its expiry date: an expired unit can never be issued.
     */
    @Transactional
    public BloodRequestView issue(UUID requestId) {
        CurrentUser user = CurrentUser.get();
        Locked request = lockForBank(requestId, user);
        if (!"APPROVED".equals(request.effectiveState())) {
            throw notApproved(request);
        }

        // Lock the reservations and their units together. Only holds that are still running, on units still in date.
        List<Held> held = jdbc.sql("""
                SELECT r.id, r.unit_id, u.reference
                  FROM reservation r
                  JOIN unit u ON u.id = r.unit_id
                 WHERE r.request_id = :request AND r.status = 'ACTIVE'
                   AND r.hold_expires_at > now() AND u.expiry_date >= current_date
                 ORDER BY u.id
                   FOR UPDATE OF r, u""")
            .param("request", requestId)
            .query((rs, row) -> new Held(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getString("reference")))
            .list();
        int active = jdbc.sql("SELECT count(*) FROM reservation WHERE request_id = ? AND status = 'ACTIVE'")
            .param(requestId).query(Integer.class).single();
        if (held.isEmpty() || held.size() != active) {
            // A hold lapsed or a unit expired in the moment between the state check and the lock.
            throw ApiException.conflict(ErrorCode.REQUEST_NOT_APPROVED,
                "This request cannot be issued: its hold has just ended or a unit has expired.");
        }

        List<UUID> units = held.stream().map(Held::unitId).toList();
        int issued = jdbc.sql("UPDATE unit SET state = 'ISSUED', version = version + 1 WHERE id IN (:units) AND state = 'RESERVED'")
            .param("units", units)
            .update();
        if (issued != units.size()) {
            throw ApiException.conflict(ErrorCode.REQUEST_NOT_APPROVED,
                "This request cannot be issued: a reserved unit changed while issuing. Nothing was issued.");
        }
        jdbc.sql("UPDATE reservation SET status = 'ISSUED', ended_at = now() WHERE id IN (:reservations) AND status = 'ACTIVE'")
            .param("reservations", held.stream().map(Held::reservationId).toList())
            .update();
        jdbc.sql("UPDATE blood_request SET state = 'FULFILLED' WHERE id = ? AND state = 'APPROVED'").param(requestId).update();

        for (Held unit : held) {
            unitHistory(unit.unitId(), "ISSUED", user.id(), "To " + request.reference());
        }
        requests.history(requestId, "ISSUED", user.id(), held.size() == 1 ? "1 unit issued" : held.size() + " units issued");
        return read(requestId);
    }

    /**
     * Reverses an issue recorded in error. Each unit returns to AVAILABLE only if it has not expired; an expired unit
     * becomes EXPIRED instead. The reservations become REVERSED and the request goes back to PENDING, so the bank can
     * approve it again. Nothing is deleted: both the ISSUED and the ISSUE_REVERSED entries stay in the history.
     */
    @Transactional
    public BloodRequestView reverseIssue(UUID requestId, String reason) {
        CurrentUser user = CurrentUser.get();
        Locked request = lockForBank(requestId, user);
        if (!"FULFILLED".equals(request.storedState())) {
            throw ApiException.conflict(ErrorCode.REQUEST_NOT_FULFILLED,
                "There is no issue to reverse: this request is " + describe(request.effectiveState()) + ".");
        }

        List<Held> issued = jdbc.sql("""
                SELECT r.id, r.unit_id, u.reference
                  FROM reservation r
                  JOIN unit u ON u.id = r.unit_id
                 WHERE r.request_id = :request AND r.status = 'ISSUED'
                 ORDER BY u.id
                   FOR UPDATE OF r, u""")
            .param("request", requestId)
            .query((rs, row) -> new Held(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getString("reference")))
            .list();
        // Back to stock only if still in date (Lagos date); otherwise the unit is out of use for good.
        List<Restored> restored = jdbc.sql("""
                UPDATE unit SET state = CASE WHEN expiry_date < current_date THEN 'EXPIRED' ELSE 'AVAILABLE' END,
                                version = version + 1
                 WHERE id IN (:units) AND state = 'ISSUED'
                RETURNING id, state""")
            .param("units", issued.stream().map(Held::unitId).toList())
            .query((rs, row) -> new Restored(rs.getObject("id", UUID.class), rs.getString("state")))
            .list();
        if (issued.isEmpty() || restored.size() != issued.size()) {
            throw ApiException.conflict(ErrorCode.REQUEST_NOT_FULFILLED,
                "The issued units have changed since they were issued, so this issue cannot be reversed.");
        }
        jdbc.sql("UPDATE reservation SET status = 'REVERSED' WHERE id IN (:reservations) AND status = 'ISSUED'")
            .param("reservations", issued.stream().map(Held::reservationId).toList())
            .update();
        jdbc.sql("UPDATE blood_request SET state = 'PENDING' WHERE id = ? AND state = 'FULFILLED'").param(requestId).update();

        for (Restored unit : restored) {
            unitHistory(unit.id(), "ISSUE_REVERSED", user.id(), "AVAILABLE".equals(unit.state())
                ? "Back in stock; issue to " + request.reference() + " reversed"
                : "Expired, so not returned to stock; issue to " + request.reference() + " reversed");
        }
        requests.history(requestId, "ISSUE_REVERSED", user.id(), note(reason));
        return read(requestId);
    }

    /**
     * The hospital that made a request withdraws it. Only that hospital's staff: any other hospital is told the
     * request does not exist (SecurityConfig already refuses banks, donors and the administrator). Cancelling an
     * APPROVED request releases its holds in the same transaction.
     */
    @Transactional
    public BloodRequestView cancel(UUID requestId, String reason) {
        CurrentUser user = CurrentUser.get();
        Locked request = lock(requestId);
        if (!user.is(Role.HOSPITAL_STAFF) || !user.worksFor(request.hospitalId())) {
            throw ApiException.notFound("Request not found.");
        }
        if (!Set.of("PENDING", "APPROVED").contains(request.effectiveState())) {
            throw ApiException.conflict(ErrorCode.REQUEST_CLOSED,
                "This request can no longer be cancelled: it is " + describe(request.effectiveState()) + ".");
        }
        if ("APPROVED".equals(request.effectiveState())) {
            releaseHolds(requestId, user, "Released: " + request.reference() + " cancelled by the hospital");
        }
        jdbc.sql("UPDATE blood_request SET state = 'CANCELLED' WHERE id = ? AND state IN ('PENDING', 'APPROVED')")
            .param(requestId).update();
        requests.history(requestId, "CANCELLED", user.id(), note(reason));
        return read(requestId);
    }

    /**
     * ADR-002: bank staff release a routine request's hold so an emergency request can have the units. A recorded
     * decision, never automatic. Releasing and approving the emergency are one transaction: if the emergency still
     * cannot be met, the approval throws and nothing is released. The routine request goes back to PENDING.
     */
    @Transactional
    public BloodRequestView releaseHoldFor(UUID routineId, UUID emergencyId, String reason) {
        CurrentUser user = CurrentUser.get();
        if (routineId.equals(emergencyId)) {
            throw ApiException.validation("forRequestId", "Must be a different request.");
        }
        // Lock both requests in id order, so two releases touching the same pair can never deadlock.
        List<UUID> order = routineId.compareTo(emergencyId) < 0 ? List.of(routineId, emergencyId) : List.of(emergencyId, routineId);
        jdbc.sql("SELECT id FROM blood_request WHERE id IN (:ids) ORDER BY id FOR UPDATE")
            .param("ids", order).query(UUID.class).list();
        Locked routine = lockForBank(routineId, user);
        Locked emergency = lockForBank(emergencyId, user);

        if (!"APPROVED".equals(routine.effectiveState()) || !"ROUTINE".equals(routine.urgency())) {
            throw notReleasable(routine.reference() + " is not an approved routine request holding units.");
        }
        if (!"PENDING".equals(emergency.storedState())) {
            throw ApiException.conflict(ErrorCode.REQUEST_NOT_PENDING,
                emergency.reference() + " is no longer pending: it is " + describe(emergency.effectiveState()) + ".");
        }
        if (!"EMERGENCY".equals(emergency.urgency())) {
            throw notReleasable(emergency.reference() + " is not an emergency request.");
        }
        if (!routine.bankId().equals(emergency.bankId()) || !routine.bloodGroup().equals(emergency.bloodGroup())
                || !routine.rhesus().equals(emergency.rhesus()) || !routine.component().equals(emergency.component())) {
            throw notReleasable("The two requests are not for the same bank, group, rhesus type and component.");
        }
        int free = jdbc.sql("""
                SELECT count(*) FROM unit_status
                 WHERE bank_id = :bank AND blood_group = :bloodGroup AND rhesus = :rhesus AND component = :component
                   AND effective_state = 'AVAILABLE'""")
            .param("bank", emergency.bankId())
            .param("bloodGroup", emergency.bloodGroup())
            .param("rhesus", emergency.rhesus())
            .param("component", emergency.component())
            .query(Integer.class).single();
        if (free >= emergency.quantity()) {
            throw notReleasable("Enough matching units are free: approve " + emergency.reference()
                + " directly; no hold needs to be released.");
        }

        releaseHolds(routineId, user, "Released for emergency " + emergency.reference());
        jdbc.sql("UPDATE blood_request SET state = 'PENDING' WHERE id = ? AND state = 'APPROVED'").param(routineId).update();
        requests.history(routineId, "HOLD_RELEASED", user.id(), note("For " + emergency.reference() + ": " + reason));
        // Same transaction: if the emergency still cannot be met in full, this throws and the release above is undone.
        return requests.approve(emergencyId);
    }

    /** Ends the request's running holds early (RELEASED) and puts their units back to AVAILABLE. */
    private void releaseHolds(UUID requestId, CurrentUser user, String unitNote) {
        List<Held> held = jdbc.sql("""
                SELECT r.id, r.unit_id, u.reference
                  FROM reservation r
                  JOIN unit u ON u.id = r.unit_id
                 WHERE r.request_id = :request AND r.status = 'ACTIVE'
                 ORDER BY u.id
                   FOR UPDATE OF r, u""")
            .param("request", requestId)
            .query((rs, row) -> new Held(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getString("reference")))
            .list();
        if (held.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE reservation SET status = 'RELEASED', ended_at = now() WHERE id IN (:reservations) AND status = 'ACTIVE'")
            .param("reservations", held.stream().map(Held::reservationId).toList())
            .update();
        jdbc.sql("UPDATE unit SET state = 'AVAILABLE', version = version + 1 WHERE id IN (:units) AND state = 'RESERVED'")
            .param("units", held.stream().map(Held::unitId).toList())
            .update();
        for (Held unit : held) {
            unitHistory(unit.unitId(), "HOLD_RELEASED", user.id(), unitNote);
        }
    }

    /** Locks the request row and reads its effective state. Not found if it does not exist. */
    private Locked lock(UUID requestId) {
        return jdbc.sql("""
                SELECT br.id, br.reference, br.hospital_id, br.bank_id, br.blood_group, br.rhesus, br.component,
                       br.quantity, br.urgency, br.state
                  FROM blood_request br
                 WHERE br.id = ?
                   FOR UPDATE""")
            .param(requestId)
            .query((rs, row) -> new Locked(
                rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("hospital_id", UUID.class),
                rs.getObject("bank_id", UUID.class), rs.getString("blood_group"), rs.getString("rhesus"),
                rs.getString("component"), rs.getInt("quantity"), rs.getString("urgency"), rs.getString("state"),
                null))
            .optional()
            .map(locked -> locked.withEffectiveState(jdbc.sql("SELECT effective_state FROM blood_request_status WHERE id = ?")
                .param(requestId).query(String.class).single()))
            .orElseThrow(() -> ApiException.notFound("Request not found."));
    }

    /** Locks a request that the caller's bank supplies (or any, for the administrator). Others: not found. */
    private Locked lockForBank(UUID requestId, CurrentUser user) {
        Locked request = lock(requestId);
        boolean allowed = user.is(Role.ADMIN) || (user.is(Role.BANK_STAFF) && user.worksFor(request.bankId()));
        if (!allowed) {
            throw ApiException.notFound("Request not found.");
        }
        return request;
    }

    private void unitHistory(UUID unitId, String event, UUID actor, String note) {
        jdbc.sql("INSERT INTO activity (entity_type, entity_id, event, actor_id, note) VALUES ('UNIT', ?, ?, ?, ?)")
            .params(unitId, event, actor, note(note))
            .update();
    }

    private BloodRequestView read(UUID id) {
        return reader.find(id, Scope.EVERYTHING).orElseThrow();
    }

    private static ApiException notApproved(Locked request) {
        String why = switch (request.effectiveState()) {
            case "LAPSED" -> "its hold has lapsed. Approve it again if units are free";
            case "UNIT_EXPIRED" -> "a reserved unit has expired, and an expired unit can never be issued";
            case "FULFILLED" -> "its units were already issued";
            default -> "it is " + describe(request.effectiveState());
        };
        return ApiException.conflict(ErrorCode.REQUEST_NOT_APPROVED, "This request cannot be issued: " + why + ".");
    }

    private static ApiException notReleasable(String why) {
        return ApiException.conflict(ErrorCode.HOLD_NOT_RELEASABLE, "This hold cannot be released. " + why);
    }

    private static String describe(String state) {
        return state.toLowerCase().replace('_', ' ');
    }

    /** Keeps a history note within the database's 500-character limit. */
    private static String note(String text) {
        String trimmed = text.trim();
        return trimmed.length() <= NOTE_LIMIT ? trimmed : trimmed.substring(0, NOTE_LIMIT - 1) + "…";
    }

    private record Held(UUID reservationId, UUID unitId, String reference) {
    }

    private record Restored(UUID id, String state) {
    }

    private record Locked(UUID id, String reference, UUID hospitalId, UUID bankId, String bloodGroup, String rhesus,
                          String component, int quantity, String urgency, String storedState, String effectiveState) {

        Locked withEffectiveState(String effective) {
            return new Locked(id, reference, hospitalId, bankId, bloodGroup, rhesus, component, quantity, urgency,
                storedState, effective);
        }
    }
}
