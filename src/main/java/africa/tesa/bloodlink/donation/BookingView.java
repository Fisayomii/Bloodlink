package africa.tesa.bloodlink.donation;

import java.time.Instant;
import java.util.UUID;

/** Contract: Booking. */
public record BookingView(UUID id, SlotView slot, DonorSummary donor, String status, Instant createdAt) {
}
