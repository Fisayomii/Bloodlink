package africa.tesa.bloodlink.unit;

import africa.tesa.bloodlink.organisation.OrganisationView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A unit as the contract shows it (components.schemas.Unit). {@code state} is the effective state from the
 * unit_status view, so an expired unit or a lapsed hold shows correctly even before any job has run.
 */
public record UnitView(
    UUID id,
    String reference,
    String bloodGroup,
    String rhesus,
    String component,
    LocalDate collectionDate,
    LocalDate expiryDate,
    String state,
    OrganisationView bank,
    Instant recordedAt) {
}
