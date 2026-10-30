package africa.tesa.bloodlink.dashboard;

import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.request.BloodRequestView.UnitSummary;
import java.time.Instant;
import java.util.UUID;

/** Contract: HoldEndingSoon. One running hold that ends within the window. */
public record HoldEndingSoon(UUID requestId, String requestReference, OrganisationView hospital, UnitSummary unit,
                             Instant holdExpiresAt) {
}
