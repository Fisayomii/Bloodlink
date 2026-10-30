package africa.tesa.bloodlink.donation;

import java.time.Instant;
import java.util.List;

/** Contract: DonationSlotList. computedAt is when placesLeft was derived; the screen shows it beside the figures. */
public record SlotList(Instant computedAt, List<SlotView> items) {
}
