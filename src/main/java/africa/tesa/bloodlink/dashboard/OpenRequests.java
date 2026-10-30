package africa.tesa.bloodlink.dashboard;

/** Contract: OpenRequestCounts. approved counts requests approved and still holding units. */
public record OpenRequests(int pending, int approved) {
}
