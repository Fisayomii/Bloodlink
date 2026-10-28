package africa.tesa.bloodlink.unit;

import africa.tesa.bloodlink.organisation.OrganisationView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A unit with its history (contract: UnitDetail). The history is oldest first and never edited, so an issue and its
 * reversal both stay visible. {@code state} is the effective state from the unit_status view.
 */
public record UnitDetailView(
    UUID id,
    String reference,
    String bloodGroup,
    String rhesus,
    String component,
    LocalDate collectionDate,
    LocalDate expiryDate,
    String state,
    OrganisationView bank,
    Instant recordedAt,
    List<HistoryEntry> history) {

    static UnitDetailView of(UnitView unit, List<HistoryEntry> history) {
        return new UnitDetailView(unit.id(), unit.reference(), unit.bloodGroup(), unit.rhesus(), unit.component(),
            unit.collectionDate(), unit.expiryDate(), unit.state(), unit.bank(), unit.recordedAt(), history);
    }

    /** Contract: HistoryEntry. actor is "Name, Organisation" or "System". */
    public record HistoryEntry(Instant at, String event, String actor, String note) {
    }
}
