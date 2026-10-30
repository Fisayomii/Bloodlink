package africa.tesa.bloodlink.dashboard;

import java.time.Instant;

/** Contract: ActivityEntry. actor is "Name, Organisation", a name, or "System" for the sweep. */
public record ActivityEntry(Instant at, String event, String subject, String reference, String actor, String note) {
}
