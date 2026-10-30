package africa.tesa.bloodlink.donation;

import java.util.UUID;

/** Contract: DonorSummary. Display name only; no contact or health details ever leave the service. */
public record DonorSummary(UUID id, String displayName) {
}
