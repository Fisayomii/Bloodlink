package africa.tesa.bloodlink.request;

import africa.tesa.bloodlink.organisation.OrganisationView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A blood request as the contract shows it (components.schemas.BloodRequest). {@code state} and each reservation's
 * {@code status} come from the status views, so a lapsed hold or an expired unit shows correctly before any job runs.
 * There is no patient field of any kind.
 */
public record BloodRequestView(
    UUID id,
    String reference,
    OrganisationView hospital,
    OrganisationView bank,
    String bloodGroup,
    String rhesus,
    String component,
    int quantity,
    String urgency,
    Instant neededBy,
    String state,
    String declineReason,
    Instant createdAt,
    List<Reservation> reservations,
    List<HistoryEntry> history) {

    /** Contract: Reservation. holdExpiresAt is shown to users as "hold ends at". */
    public record Reservation(UUID id, UnitSummary unit, String status, Instant holdExpiresAt, Instant createdAt) {
    }

    /** Contract: UnitSummary. */
    public record UnitSummary(UUID id, String reference, LocalDate expiryDate) {
    }

    /** Contract: HistoryEntry. actor is "Name, Organisation" or "System"; entries are never removed. */
    public record HistoryEntry(Instant at, String event, String actor, String note) {
    }
}
