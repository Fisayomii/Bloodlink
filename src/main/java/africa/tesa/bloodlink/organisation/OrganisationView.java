package africa.tesa.bloodlink.organisation;

import java.util.UUID;

/** A bank or hospital as the contract shows it (components.schemas.Organisation). */
public record OrganisationView(UUID id, String name, OrganisationType type, String area) {
}
