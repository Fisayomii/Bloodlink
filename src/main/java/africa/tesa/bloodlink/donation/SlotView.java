package africa.tesa.bloodlink.donation;

import africa.tesa.bloodlink.organisation.OrganisationView;
import java.time.Instant;
import java.util.UUID;

/** Contract: DonationSlot. booked counts active bookings (BOOKED or ATTENDED); placesLeft = capacity - booked. */
public record SlotView(UUID id, OrganisationView bank, Instant startsAt, Instant endsAt, int capacity, int booked,
                       int placesLeft) {
}
