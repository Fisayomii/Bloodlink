package africa.tesa.bloodlink.donation;

import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donation slots and bookings. Who may call each path is set in SecurityConfig; whose bookings a user may see or change
 * is checked in DonationService.
 *
 * <ul>
 *   <li>GET /donation-slots, GET /bookings: safe.</li>
 *   <li>POST /donation-slots: bank staff open a slot. Idempotent with an Idempotency-Key.</li>
 *   <li>POST /donation-slots/{id}/bookings: a donor books. Never beyond capacity (DonationService).</li>
 *   <li>POST /bookings/{id}/cancellation and /attendance: change a booking's status once; a repeat gets 409.</li>
 * </ul>
 */
@RestController
public class DonationController {

    private static final String STATUS_PATTERN = "BOOKED|CANCELLED|ATTENDED|NO_SHOW";

    private final DonationService donations;

    public DonationController(DonationService donations) {
        this.donations = donations;
    }

    @GetMapping("/donation-slots")
    SlotList list(@RequestParam(required = false) UUID bankId,
                  @RequestParam(required = false) LocalDate from,
                  @RequestParam(required = false) LocalDate to) {
        return donations.list(bankId, from, to);
    }

    @PostMapping("/donation-slots")
    ResponseEntity<SlotView> create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                    @Valid @RequestBody SlotCreate body) {
        DonationService.Created created = donations.create(idempotencyKey, body);
        if (created.replayed()) {
            // Same key and same body as an earlier submission: nothing new was opened.
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(created.slot());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created.slot());
    }

    @PostMapping("/donation-slots/{slotId}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView book(@PathVariable UUID slotId) {
        return donations.book(slotId);
    }

    @GetMapping("/bookings")
    BookingPage bookings(@RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        if (status != null && !status.matches(STATUS_PATTERN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "status is not one of the allowed values.");
        }
        return donations.listBookings(status, page, size);
    }

    @PostMapping("/bookings/{bookingId}/cancellation")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView cancel(@PathVariable UUID bookingId) {
        return donations.cancel(bookingId);
    }

    @PostMapping("/bookings/{bookingId}/attendance")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView attendance(@PathVariable UUID bookingId, @Valid @RequestBody Attendance body) {
        return donations.recordAttendance(bookingId, body.outcome());
    }

    /** Contract: Attendance. */
    public record Attendance(
        @NotNull @Pattern(regexp = "ATTENDED|NO_SHOW", message = "Must be ATTENDED or NO_SHOW.") String outcome) {
    }
}
