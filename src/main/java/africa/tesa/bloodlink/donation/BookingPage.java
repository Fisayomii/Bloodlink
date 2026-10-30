package africa.tesa.bloodlink.donation;

import java.util.List;

/** Contract: BookingPage. */
public record BookingPage(List<BookingView> items, int page, int size, long totalItems) {
}
