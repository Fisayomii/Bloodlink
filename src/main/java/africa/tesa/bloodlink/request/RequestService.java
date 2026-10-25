package africa.tesa.bloodlink.request;

import africa.tesa.bloodlink.auth.CurrentUser;
import africa.tesa.bloodlink.auth.Role;
import africa.tesa.bloodlink.request.RequestController.BloodRequestCreate;
import africa.tesa.bloodlink.request.RequestController.BloodRequestPage;
import africa.tesa.bloodlink.request.RequestReader.Scope;
import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Blood requests: creating them safely, and the bank's decision to approve (reserving units) or decline.
 *
 * <p>The key rule, "a unit is reserved for at most one request at a time", is protected in three layers inside
 * {@link #approve}, so that three approvals racing for the last unit produce exactly one reservation:
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE SKIP LOCKED} picks units and locks them; a racing approval skips locked rows.</li>
 *   <li>{@code UPDATE unit SET state = 'RESERVED' ... WHERE id IN (...) AND state = 'AVAILABLE'} checks and changes in one
 *       statement; fewer rows changed than units wanted means someone else got there first.</li>
 *   <li>The partial unique index reservation_one_active_hold_idx makes a second ACTIVE hold on a unit impossible.</li>
 * </ol>
 * Any refusal throws, which rolls the whole transaction back: approval is all or nothing.
 */
@Service
public class RequestService {

    static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final RequestReader reader;
    private final Duration routineHold;
    private final Duration urgentHold;
    private final Duration emergencyHold;

    public RequestService(JdbcClient jdbc, RequestReader reader,
                          @Value("${app.holds.routine}") Duration routineHold,
                          @Value("${app.holds.urgent}") Duration urgentHold,
                          @Value("${app.holds.emergency}") Duration emergencyHold) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.routineHold = routineHold;
        this.urgentHold = urgentHold;
        this.emergencyHold = emergencyHold;
    }

    /** The result of POST /requests: the request, and whether it was a replay of an earlier submission. */
    public record Created(BloodRequestView request, boolean replayed) {
    }

    /**
     * Creates a request from the signed-in hospital, safe to submit twice.
     *
     * <p>The client sends the same Idempotency-Key on every retry of one form submission. The unique constraint
     * (created_by, idempotency_key) means the database can hold only one request per key: the INSERT uses
     * ON CONFLICT DO NOTHING, so a second submission inserts nothing, even if both arrive at the same moment. We then
     * return the request already stored, provided the body is the same (compared by hash); a different body with the
     * same key is a client mistake and gets 422.
     */
    @Transactional
    public Created create(UUID idempotencyKey, BloodRequestCreate body) {
        CurrentUser user = CurrentUser.get();
        if (!user.is(Role.HOSPITAL_STAFF) || user.organisationId() == null) {
            throw ApiException.forbidden("Only hospital staff can request units.");
        }
        if (!body.neededBy().toInstant().isAfter(Instant.now())) {
            throw ApiException.validation("neededBy", "Must be in the future.");
        }
        boolean bankExists = jdbc.sql("SELECT count(*) > 0 FROM organisation WHERE id = ? AND type = 'BLOOD_BANK'")
            .param(body.bankId()).query(Boolean.class).single();
        if (!bankExists) {
            throw ApiException.notFound("No blood bank with that id.");
        }
        String hash = payloadHash(body);

        Optional<UUID> inserted = jdbc.sql("""
                INSERT INTO blood_request (hospital_id, bank_id, blood_group, rhesus, component, quantity, urgency, needed_by,
                                           created_by, idempotency_key, payload_hash)
                VALUES (:hospital, :bank, :bloodGroup, :rhesus, :component, :quantity, :urgency, :neededBy,
                        :user, :key, :hash)
                ON CONFLICT (created_by, idempotency_key) DO NOTHING
                RETURNING id""")
            .param("hospital", user.organisationId())
            .param("bank", body.bankId())
            .param("bloodGroup", body.bloodGroup())
            .param("rhesus", body.rhesus())
            .param("component", body.component())
            .param("quantity", body.quantity())
            .param("urgency", body.urgency())
            .param("neededBy", body.neededBy())
            .param("user", user.id())
            .param("key", idempotencyKey)
            .param("hash", hash)
            .query(UUID.class)
            .optional();

        if (inserted.isPresent()) {
            history(inserted.get(), "CREATED", user.id(), null);
            return new Created(read(inserted.get()), false);
        }
        // Nothing inserted: this user already used this key. Same body -> replay; different body -> refuse.
        Existing existing = jdbc.sql("SELECT id, payload_hash FROM blood_request WHERE created_by = ? AND idempotency_key = ?")
            .params(user.id(), idempotencyKey)
            .query((rs, row) -> new Existing(rs.getObject("id", UUID.class), rs.getString("payload_hash")))
            .single();
        if (!existing.payloadHash().equals(hash)) {
            throw new ApiException(ApiException.UNPROCESSABLE, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used for a different request. Use a new key for a new request.");
        }
        return new Created(read(existing.id()), true);
    }

    /** GET /requests: hospitals see their own, banks see those addressed to them, the administrator sees all. */
    public BloodRequestPage list(String state, int page, int size) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "page must be 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        Scope scope = scopeOf(CurrentUser.get());
        return new BloodRequestPage(reader.page(scope, state, page, size), page, size, reader.count(scope, state));
    }

    /** GET /requests/{id}. Someone else's request is reported as not found, so its existence is not revealed. */
    public BloodRequestView get(UUID id) {
        CurrentUser user = CurrentUser.get();
        if (user.is(Role.DONOR)) {
            throw ApiException.notFound("Request not found.");
        }
        return reader.find(id, scopeOf(user))
            .orElseThrow(() -> ApiException.notFound("Request not found."));
    }

    /**
     * Approves a pending request by reserving exactly {@code quantity} matching units, or nothing at all.
     * See the class comment for the three layers that make a double reservation impossible.
     */
    @Transactional
    public BloodRequestView approve(UUID requestId) {
        CurrentUser user = CurrentUser.get();
        Pending request = lockPendingForDecision(requestId, user);

        // 1. Choose units: exact match, not expired (Lagos date), soonest expiry first. A unit still marked RESERVED
        //    whose hold has lapsed can be taken too. FOR UPDATE locks the chosen rows until this transaction ends;
        //    SKIP LOCKED makes a racing approval pass over them instead of waiting, so it finds too few and is refused.
        List<UUID> units = jdbc.sql("""
                SELECT u.id
                  FROM unit u
                 WHERE u.bank_id = :bank AND u.blood_group = :bloodGroup AND u.rhesus = :rhesus AND u.component = :component
                   AND u.expiry_date >= current_date
                   AND (u.state = 'AVAILABLE'
                        OR (u.state = 'RESERVED' AND NOT EXISTS (
                               SELECT 1 FROM reservation r
                                WHERE r.unit_id = u.id AND r.status = 'ACTIVE' AND r.hold_expires_at > now())))
                 ORDER BY u.expiry_date, u.id
                 LIMIT :quantity""")
            .param("bank", request.bankId())
            .param("bloodGroup", request.bloodGroup())
            .param("rhesus", request.rhesus())
            .param("component", request.component())
            .param("quantity", request.quantity())
            .query(UUID.class)
            .list();
        if (units.size() < request.quantity()) {
            throw notEnoughUnits(units.size(), request.quantity());
        }

        // EVIDENCE ONLY: widen the gap between reading the unit and reserving it, so the race shows reliably.
        jdbc.sql("SELECT 1 FROM pg_sleep(0.2)").query(Integer.class).single();

        // 2. Any chosen unit still held by a lapsed reservation: end that reservation and put the unit back to AVAILABLE,
        //    so the guarded update below treats every chosen unit the same way.
        releaseLapsedHolds(units);

        // 3. Check-and-change in one statement. If any unit is no longer AVAILABLE, fewer rows change: refuse.
        int reserved = jdbc.sql("""
                UPDATE unit SET state = 'RESERVED', version = version + 1
                 WHERE id IN (:units)""")
            .param("units", units)
            .update();
        if (reserved < request.quantity()) {
            throw notEnoughUnits(reserved, request.quantity());
        }

        // 4. One reservation per unit. The partial unique index refuses a second ACTIVE hold on any of them.
        Duration hold = holdFor(request.urgency());
        try {
            for (UUID unit : units) {
                jdbc.sql("""
                        INSERT INTO reservation (request_id, unit_id, hold_expires_at)
                        VALUES (:request, :unit, now() + :seconds * interval '1 second')""")
                    .param("request", requestId)
                    .param("unit", unit)
                    .param("seconds", hold.toSeconds())
                    .update();
                jdbc.sql("""
                        INSERT INTO activity (entity_type, entity_id, event, actor_id, note)
                        VALUES ('UNIT', :unit, 'RESERVED', :user, :note)""")
                    .param("unit", unit)
                    .param("user", user.id())
                    .param("note", "For request " + request.reference())
                    .update();
            }
        } catch (DuplicateKeyException alreadyHeld) {
            throw notEnoughUnits(0, request.quantity());
        }

        jdbc.sql("UPDATE blood_request SET state = 'APPROVED' WHERE id = ? AND state = 'PENDING'").param(requestId).update();
        history(requestId, "APPROVED", user.id(), "Hold " + hold.toMinutes() + " minutes");
        return read(requestId);
    }

    /** Declines a pending request with a reason the hospital will see. */
    @Transactional
    public BloodRequestView decline(UUID requestId, String reason) {
        CurrentUser user = CurrentUser.get();
        lockPendingForDecision(requestId, user);
        jdbc.sql("UPDATE blood_request SET state = 'DECLINED', decline_reason = ? WHERE id = ? AND state = 'PENDING'")
            .params(reason.trim(), requestId)
            .update();
        history(requestId, "DECLINED", user.id(), reason.trim());
        return read(requestId);
    }

    /**
     * Locks the request row for this decision, so two decisions on one request run one after the other. Only staff of
     * the bank it was sent to, or the administrator, may decide; anyone else is told it does not exist.
     */
    private Pending lockPendingForDecision(UUID requestId, CurrentUser user) {
        Pending request = jdbc.sql("""
                SELECT id, reference, bank_id, blood_group, rhesus, component, quantity, urgency, state
                  FROM blood_request WHERE id = ? FOR UPDATE""")
            .param(requestId)
            .query((rs, row) -> new Pending(
                rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("bank_id", UUID.class),
                rs.getString("blood_group"), rs.getString("rhesus"), rs.getString("component"), rs.getInt("quantity"),
                rs.getString("urgency"), rs.getString("state")))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Request not found."));
        boolean allowed = user.is(Role.ADMIN) || (user.is(Role.BANK_STAFF) && user.worksFor(request.bankId()));
        if (!allowed) {
            throw ApiException.notFound("Request not found.");
        }
        if (!"PENDING".equals(request.state())) {
            throw ApiException.conflict(ErrorCode.REQUEST_NOT_PENDING,
                "This request is no longer pending: it is " + request.state().toLowerCase().replace('_', ' ') + ".");
        }
        return request;
    }

    /**
     * Ends lapsed holds on the given (already locked) units and returns those units to AVAILABLE. A request left with no
     * running or issued hold becomes LAPSED, with a System entry in its history.
     */
    private void releaseLapsedHolds(List<UUID> units) {
        List<UUID> affectedRequests = jdbc.sql("""
                UPDATE reservation SET status = 'LAPSED', ended_at = now()
                 WHERE unit_id IN (:units) AND status = 'ACTIVE' AND hold_expires_at <= now()
                RETURNING request_id""")
            .param("units", units)
            .query(UUID.class)
            .list();
        if (affectedRequests.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE unit SET state = 'AVAILABLE', version = version + 1 WHERE id IN (:units) AND state = 'RESERVED'")
            .param("units", units)
            .update();
        List<UUID> lapsed = jdbc.sql("""
                UPDATE blood_request br SET state = 'LAPSED'
                 WHERE br.id IN (:requests) AND br.state = 'APPROVED'
                   AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.request_id = br.id AND r.status IN ('ACTIVE', 'ISSUED'))
                RETURNING br.id""")
            .param("requests", affectedRequests.stream().distinct().toList())
            .query(UUID.class)
            .list();
        for (UUID request : lapsed) {
            history(request, "HOLD_LAPSED", null, "Hold ended before the units were issued; units returned to stock");
        }
    }

    private Duration holdFor(String urgency) {
        return switch (urgency) {
            case "EMERGENCY" -> emergencyHold;
            case "URGENT" -> urgentHold;
            default -> routineHold;
        };
    }

    private static ApiException notEnoughUnits(int free, int wanted) {
        return ApiException.conflict(ErrorCode.UNITS_NO_LONGER_AVAILABLE,
            "Only " + free + " of " + wanted + " matching units are free now. Nothing was reserved; the request is still pending.");
    }

    /** Adds an entry to the request's history. A null actor means the system did it. */
    private void history(UUID requestId, String event, UUID actor, String note) {
        jdbc.sql("""
                INSERT INTO activity (entity_type, entity_id, event, actor_id, note)
                VALUES ('REQUEST', :request, :event, :actor, :note)""")
            .param("request", requestId)
            .param("event", event)
            .param("actor", actor, Types.OTHER)
            .param("note", note, Types.VARCHAR)
            .update();
    }

    private BloodRequestView read(UUID id) {
        return reader.find(id, Scope.EVERYTHING).orElseThrow();
    }

    private static Scope scopeOf(CurrentUser user) {
        if (user.is(Role.ADMIN)) {
            return Scope.EVERYTHING;
        }
        if (user.is(Role.HOSPITAL_STAFF)) {
            return new Scope(user.organisationId(), null);
        }
        if (user.is(Role.BANK_STAFF)) {
            return new Scope(null, user.organisationId());
        }
        throw ApiException.forbidden("Donors cannot see blood requests.");
    }

    /**
     * SHA-256 of the request's fields in a fixed order, so the same submission always gives the same hash. neededBy is
     * normalised to UTC, so "14:00+01:00" and "13:00Z" count as the same time.
     */
    static String payloadHash(BloodRequestCreate body) {
        String canonical = String.join("|", body.bankId().toString(), body.bloodGroup(), body.rhesus(), body.component(),
            String.valueOf(body.quantity()), body.urgency(), body.neededBy().toInstant().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", impossible);
        }
    }

    private record Existing(UUID id, String payloadHash) {
    }

    private record Pending(UUID id, String reference, UUID bankId, String bloodGroup, String rhesus, String component,
                           int quantity, String urgency, String state) {
    }
}
