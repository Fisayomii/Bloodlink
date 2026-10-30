package africa.tesa.bloodlink.donation;

import africa.tesa.bloodlink.auth.CurrentUser;
import africa.tesa.bloodlink.auth.Role;
import africa.tesa.bloodlink.organisation.OrganisationType;
import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.DateRange;
import africa.tesa.bloodlink.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Donation slots and bookings.
 *
 * <p>The rule (brief): "A donation slot cannot be booked beyond its capacity, including when two donors book the last
 * place in the same second." Two layers, in the same shape as approvals (ADR-001):
 * <ol>
 *   <li>Booking locks the slot row ({@code SELECT ... FOR UPDATE}), so bookings for one slot run one after another and
 *       each sees the places the previous one took. The loser gets a clear 409 SLOT_FULL.</li>
 *   <li>The database: every active booking holds a place number from 1 to the slot's capacity, and the partial unique
 *       index {@code booking_one_active_per_place_idx} lets each place be held once (007-donation-slots.sql). Even
 *       without layer 1, a slot can never hold more active bookings than places.</li>
 * </ol>
 */
@Service
public class DonationService {

    static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 31;
    private static final Duration MAX_SLOT_LENGTH = Duration.ofHours(8);
    private static final String ACTIVE = "('BOOKED', 'ATTENDED')";

    /** One slot with the bank and its active bookings counted now. Shared by every read. */
    private static final String SLOT_SELECT = """
            SELECT s.id AS slot_id, s.starts_at, s.ends_at, s.capacity,
                   o.id AS bank_id, o.name AS bank_name, o.type AS bank_type, o.area AS bank_area,
                   (SELECT count(*) FROM booking x WHERE x.slot_id = s.id AND x.status IN %s) AS booked
              FROM donation_slot s
              JOIN organisation o ON o.id = s.bank_id
            """.formatted(ACTIVE);

    private static final String BOOKING_SELECT = """
            SELECT b.id, b.status, b.created_at, d.id AS donor_id, d.display_name AS donor_name,
                   s.id AS slot_id, s.starts_at, s.ends_at, s.capacity,
                   o.id AS bank_id, o.name AS bank_name, o.type AS bank_type, o.area AS bank_area,
                   (SELECT count(*) FROM booking x WHERE x.slot_id = s.id AND x.status IN %s) AS booked
              FROM booking b
              JOIN app_user d ON d.id = b.donor_id
              JOIN donation_slot s ON s.id = b.slot_id
              JOIN organisation o ON o.id = s.bank_id
            """.formatted(ACTIVE);

    private final JdbcClient jdbc;
    private final ZoneId zone;

    public DonationService(JdbcClient jdbc, @Value("${app.time-zone}") ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    /** The result of POST /donation-slots: the slot, and whether it was a replay of an earlier submission. */
    public record Created(SlotView slot, boolean replayed) {
    }

    /** GET /donation-slots: any signed-in user. Lagos dates; the next 14 days by default, at most 31. */
    public SlotList list(UUID bankId, LocalDate from, LocalDate to) {
        LocalDate first = from != null ? from : LocalDate.now(zone);
        LocalDate last = to != null ? to : first.plusDays(DEFAULT_DAYS);
        DateRange.checkQuery("from", first);
        DateRange.checkQuery("to", last);
        if (last.isBefore(first)) {
            throw ApiException.malformed("to", "Must be on or after from.");
        }
        if (last.isAfter(first.plusDays(MAX_DAYS))) {
            throw ApiException.malformed("to", "At most " + MAX_DAYS + " days after from.");
        }
        // Taken before the query, so placesLeft is never older than the time shown beside it.
        Instant computedAt = Instant.now();
        List<SlotView> items = jdbc.sql(SLOT_SELECT + """
                 WHERE s.starts_at >= :fromStart AND s.starts_at < :toEnd
                   AND (CAST(:bank AS uuid) IS NULL OR s.bank_id = :bank)
                 ORDER BY s.starts_at, o.name""")
            .param("fromStart", first.atStartOfDay(zone).toOffsetDateTime())
            .param("toEnd", last.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
            .param("bank", bankId, Types.OTHER)
            .query(DonationService::slot)
            .list();
        return new SlotList(computedAt, items);
    }

    /**
     * POST /donation-slots: bank staff open a slot at their own bank. Safe to submit twice, exactly like POST /requests:
     * the unique constraint (created_by, idempotency_key) means one slot per key; a same-body repeat returns the slot
     * already opened, a different body with the same key is refused.
     */
    @Transactional
    public Created create(UUID idempotencyKey, SlotCreate body) {
        CurrentUser user = CurrentUser.get();
        if (!user.is(Role.BANK_STAFF) || user.organisationId() == null) {
            throw ApiException.forbidden("Only bank staff can open donation slots.");
        }
        DateRange.checkBody("startsAt", body.startsAt());
        DateRange.checkBody("endsAt", body.endsAt());
        if (!body.startsAt().toInstant().isAfter(Instant.now())) {
            throw ApiException.validation("startsAt", "Must be in the future.");
        }
        if (!body.endsAt().isAfter(body.startsAt())) {
            throw ApiException.validation("endsAt", "Must be after startsAt.");
        }
        if (Duration.between(body.startsAt(), body.endsAt()).compareTo(MAX_SLOT_LENGTH) > 0) {
            throw ApiException.validation("endsAt", "At most 8 hours after startsAt.");
        }
        String hash = payloadHash(body);
        Optional<UUID> inserted = jdbc.sql("""
                INSERT INTO donation_slot (bank_id, starts_at, ends_at, capacity, created_by, idempotency_key, payload_hash)
                VALUES (:bank, :startsAt, :endsAt, :capacity, :user, :key, :hash)
                ON CONFLICT (created_by, idempotency_key) DO NOTHING
                RETURNING id""")
            .param("bank", user.organisationId())
            .param("startsAt", body.startsAt())
            .param("endsAt", body.endsAt())
            .param("capacity", body.capacity())
            .param("user", user.id())
            .param("key", idempotencyKey)
            .param("hash", hash)
            .query(UUID.class)
            .optional();
        if (inserted.isPresent()) {
            return new Created(slot(inserted.get()), false);
        }
        // Nothing inserted: this user already used this key. Same body -> replay; different body -> refuse.
        Existing existing = jdbc.sql("SELECT id, payload_hash FROM donation_slot WHERE created_by = ? AND idempotency_key = ?")
            .params(user.id(), idempotencyKey)
            .query((rs, row) -> new Existing(rs.getObject("id", UUID.class), rs.getString("payload_hash")))
            .single();
        if (!existing.payloadHash().equals(hash)) {
            throw new ApiException(ApiException.UNPROCESSABLE, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used for a different slot. Use a new key for a new slot.");
        }
        return new Created(slot(existing.id()), true);
    }

    /**
     * POST /donation-slots/{slotId}/bookings: a donor takes a place. See the class comment for the two layers that keep
     * a slot within its capacity.
     */
    @Transactional
    public BookingView book(UUID slotId) {
        CurrentUser user = CurrentUser.get();
        if (!user.is(Role.DONOR)) {
            throw ApiException.forbidden("Only donors can book a donation slot.");
        }
        // Layer 1: one booking at a time per slot. A second donor waits here until the first commits.
        LockedSlot slot = jdbc.sql("SELECT id, capacity, starts_at FROM donation_slot WHERE id = ?")
            .param(slotId)
            .query((rs, row) -> new LockedSlot(rs.getObject("id", UUID.class), rs.getInt("capacity"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant()))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Slot not found."));
        if (!slot.startsAt().isAfter(Instant.now())) {
            throw ApiException.conflict(ErrorCode.SLOT_CLOSED, "This slot has already started and can no longer be booked.");
        }
        if (hasActiveBooking(slotId, user.id())) {
            throw ApiException.conflict(ErrorCode.ALREADY_BOOKED, "You already have a place in this slot.");
        }
        Optional<Integer> place = jdbc.sql("""
                SELECT p FROM generate_series(1, :capacity) AS p
                 WHERE NOT EXISTS (SELECT 1 FROM booking b
                                    WHERE b.slot_id = :slot AND b.place_no = p AND b.status IN %s)
                 ORDER BY p
                 LIMIT 1""".formatted(ACTIVE))
            .param("capacity", slot.capacity())
            .param("slot", slotId)
            .query(Integer.class)
            .optional();
        if (place.isEmpty()) {
            throw slotFull();
        }
        // EVIDENCE ONLY, never merged: widens the window between choosing a place and taking it (docs/evidence.md).
        jdbc.sql("SELECT pg_sleep(0.2)").query(String.class).single();
        // Layer 2: the place index. If this place was taken after all, nothing is inserted and the donor is told it is full.
        Optional<UUID> booked = jdbc.sql("""
                INSERT INTO booking (slot_id, slot_capacity, place_no, donor_id)
                VALUES (:slot, :capacity, :place, :donor)
                ON CONFLICT DO NOTHING
                RETURNING id""")
            .param("slot", slotId)
            .param("capacity", slot.capacity())
            .param("place", place.get())
            .param("donor", user.id())
            .query(UUID.class)
            .optional();
        if (booked.isEmpty()) {
            if (hasActiveBooking(slotId, user.id())) {
                throw ApiException.conflict(ErrorCode.ALREADY_BOOKED, "You already have a place in this slot.");
            }
            throw slotFull();
        }
        return booking(booked.get());
    }

    /** GET /bookings: donors see their own, bank staff their bank's slots, the administrator all. */
    public BookingPage listBookings(String status, int page, int size) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "page must be 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        CurrentUser user = CurrentUser.get();
        UUID donor = null;
        UUID bank = null;
        if (user.is(Role.DONOR)) {
            donor = user.id();
        } else if (user.is(Role.BANK_STAFF)) {
            bank = user.organisationId();
        } else if (!user.is(Role.ADMIN)) {
            throw ApiException.forbidden("Hospital staff cannot see donation bookings.");
        }
        String where = """
                 WHERE (CAST(:donor AS uuid) IS NULL OR b.donor_id = :donor)
                   AND (CAST(:bank AS uuid) IS NULL OR s.bank_id = :bank)
                   AND (CAST(:status AS text) IS NULL OR b.status = :status)
                """;
        List<BookingView> items = jdbc.sql(BOOKING_SELECT + where + """
                 ORDER BY s.starts_at, b.created_at
                 LIMIT :limit OFFSET :offset""")
            .param("donor", donor, Types.OTHER)
            .param("bank", bank, Types.OTHER)
            .param("status", status, Types.VARCHAR)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(DonationService::booking)
            .list();
        // The text blocks are joined with a line break, so "b.slot_id" and "WHERE" never run together.
        long total = jdbc.sql("""
                SELECT count(*) FROM booking b JOIN donation_slot s ON s.id = b.slot_id
                """ + where)
            .param("donor", donor, Types.OTHER)
            .param("bank", bank, Types.OTHER)
            .param("status", status, Types.VARCHAR)
            .query(Long.class)
            .single();
        return new BookingPage(items, page, size, total);
    }

    /** POST /bookings/{id}/cancellation: only the donor who booked; anyone else is told it does not exist. */
    @Transactional
    public BookingView cancel(UUID bookingId) {
        CurrentUser user = CurrentUser.get();
        LockedBooking booking = lockBooking(bookingId);
        if (!booking.donorId().equals(user.id())) {
            throw ApiException.notFound("Booking not found.");
        }
        if (!"BOOKED".equals(booking.status())) {
            throw ApiException.conflict(ErrorCode.BOOKING_NOT_ACTIVE,
                "This booking is no longer active: it is " + booking.status().toLowerCase().replace('_', ' ') + ".");
        }
        if (!booking.startsAt().isAfter(Instant.now())) {
            throw ApiException.conflict(ErrorCode.SLOT_CLOSED, "This slot has already started, so the booking cannot be cancelled.");
        }
        setStatus(bookingId, "CANCELLED", user.id());
        return booking(bookingId);
    }

    /**
     * POST /bookings/{id}/attendance: staff of the bank running the slot, or the administrator, record ATTENDED or
     * NO_SHOW once the slot has started. Staff of another bank are told it does not exist.
     */
    @Transactional
    public BookingView recordAttendance(UUID bookingId, String outcome) {
        CurrentUser user = CurrentUser.get();
        LockedBooking booking = lockBooking(bookingId);
        boolean allowed = user.is(Role.ADMIN) || (user.is(Role.BANK_STAFF) && user.worksFor(booking.bankId()));
        if (!allowed) {
            throw ApiException.notFound("Booking not found.");
        }
        if (!"BOOKED".equals(booking.status())) {
            throw ApiException.conflict(ErrorCode.BOOKING_NOT_ACTIVE,
                "An outcome was already recorded for this booking: " + booking.status().toLowerCase().replace('_', ' ') + ".");
        }
        if (booking.startsAt().isAfter(Instant.now())) {
            throw ApiException.conflict(ErrorCode.SLOT_NOT_STARTED, "Attendance can be recorded once the slot has started.");
        }
        setStatus(bookingId, outcome, user.id());
        return booking(bookingId);
    }

    private boolean hasActiveBooking(UUID slotId, UUID donorId) {
        return jdbc.sql("SELECT count(*) > 0 FROM booking WHERE slot_id = ? AND donor_id = ? AND status IN " + ACTIVE)
            .params(slotId, donorId)
            .query(Boolean.class)
            .single();
    }

    /** Locks the booking row, so two outcomes for one booking (a cancellation, an attendance) run one after the other. */
    private LockedBooking lockBooking(UUID bookingId) {
        return jdbc.sql("""
                SELECT b.id, b.donor_id, b.status, s.starts_at, s.bank_id
                  FROM booking b
                  JOIN donation_slot s ON s.id = b.slot_id
                 WHERE b.id = ?
                   FOR UPDATE OF b""")
            .param(bookingId)
            .query((rs, row) -> new LockedBooking(rs.getObject("id", UUID.class), rs.getObject("donor_id", UUID.class),
                rs.getString("status"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("bank_id", UUID.class)))
            .optional()
            .orElseThrow(() -> ApiException.notFound("Booking not found."));
    }

    private void setStatus(UUID bookingId, String status, UUID actor) {
        jdbc.sql("""
                UPDATE booking SET status = :status, status_changed_at = now(), status_changed_by = :actor
                 WHERE id = :id AND status = 'BOOKED'""")
            .param("status", status)
            .param("actor", actor)
            .param("id", bookingId)
            .update();
    }

    private SlotView slot(UUID id) {
        return jdbc.sql(SLOT_SELECT + " WHERE s.id = ?").param(id).query(DonationService::slot).single();
    }

    private BookingView booking(UUID id) {
        return jdbc.sql(BOOKING_SELECT + " WHERE b.id = ?").param(id).query(DonationService::booking).single();
    }

    private static SlotView slot(ResultSet rs, int row) throws SQLException {
        int capacity = rs.getInt("capacity");
        int booked = rs.getInt("booked");
        return new SlotView(
            rs.getObject("slot_id", UUID.class),
            new OrganisationView(rs.getObject("bank_id", UUID.class), rs.getString("bank_name"),
                OrganisationType.valueOf(rs.getString("bank_type")), rs.getString("bank_area")),
            rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
            rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
            capacity,
            booked,
            Math.max(0, capacity - booked));
    }

    private static BookingView booking(ResultSet rs, int row) throws SQLException {
        return new BookingView(
            rs.getObject("id", UUID.class),
            slot(rs, row),
            new DonorSummary(rs.getObject("donor_id", UUID.class), rs.getString("donor_name")),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static ApiException slotFull() {
        return ApiException.conflict(ErrorCode.SLOT_FULL, "This slot has no places left. Please choose another time.");
    }

    /** SHA-256 of the slot's fields in a fixed order; times normalised to UTC so equal instants hash alike. */
    static String payloadHash(SlotCreate body) {
        String canonical = String.join("|", body.startsAt().toInstant().toString(), body.endsAt().toInstant().toString(),
            String.valueOf(body.capacity()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", impossible);
        }
    }

    private record Existing(UUID id, String payloadHash) {
    }

    private record LockedSlot(UUID id, int capacity, Instant startsAt) {
    }

    private record LockedBooking(UUID id, UUID donorId, String status, Instant startsAt, UUID bankId) {
    }
}
