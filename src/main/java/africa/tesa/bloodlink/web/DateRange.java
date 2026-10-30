package africa.tesa.bloodlink.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The dates the service accepts from a client: 2000-01-01 to 2100-12-31.
 *
 * <p>Without a bound, a JSON number sent for a date is read as a count of days (or seconds) since 1970 and can land a
 * million years away. PostgreSQL cannot store such a date, the driver writes "minus infinity" instead, and the unit is
 * then read back as -999999999-01-01, which is not a date the contract allows. The contract tests found this on
 * 30 October. Every date or time a client sends is checked here first, so such a value is refused with a clear message.
 */
public final class DateRange {

    public static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    public static final LocalDate LATEST = LocalDate.of(2100, 12, 31);
    private static final String ISSUE = "Must be between " + EARLIEST + " and " + LATEST + ".";

    private DateRange() {
    }

    /** A body field: 422 VALIDATION_FAILED naming the field when the date is out of range. */
    public static void checkBody(String field, LocalDate date) {
        if (date != null && outside(date)) {
            throw ApiException.validation(field, ISSUE);
        }
    }

    /** A body field holding a time: checked by its date in its own offset. */
    public static void checkBody(String field, OffsetDateTime time) {
        if (time != null) {
            checkBody(field, time.toLocalDate());
        }
    }

    /** A query value: 400 MALFORMED_REQUEST when the date is out of range (contract: BadRequest). */
    public static void checkQuery(String field, LocalDate date) {
        if (date != null && outside(date)) {
            throw ApiException.malformed(field, ISSUE);
        }
    }

    private static boolean outside(LocalDate date) {
        return date.isBefore(EARLIEST) || date.isAfter(LATEST);
    }
}
