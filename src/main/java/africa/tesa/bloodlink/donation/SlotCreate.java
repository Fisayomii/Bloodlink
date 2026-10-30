package africa.tesa.bloodlink.donation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/** Contract: DonationSlotCreate. The bank comes from the token, never from the body. */
public record SlotCreate(
    @NotNull OffsetDateTime startsAt,
    @NotNull OffsetDateTime endsAt,
    @NotNull @Min(1) @Max(50) Integer capacity) {
}
