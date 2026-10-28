package africa.tesa.bloodlink.web;

/**
 * The stable error codes from the contract (components.schemas.Error.code). The interface switches on these,
 * never on the message, so a code is never renamed without a contract change.
 */
public enum ErrorCode {
    MALFORMED_REQUEST,
    UNAUTHENTICATED,
    INVALID_CREDENTIALS,
    FORBIDDEN,
    NOT_FOUND,
    VALIDATION_FAILED,
    IDEMPOTENCY_KEY_REUSED,
    DUPLICATE_UNIT_REFERENCE,
    UNITS_NO_LONGER_AVAILABLE,
    REQUEST_NOT_PENDING,
    REQUEST_NOT_APPROVED,
    REQUEST_NOT_FULFILLED,
    REQUEST_CLOSED,
    HOLD_NOT_RELEASABLE,
    SLOT_FULL,
    SLOT_CLOSED,
    SLOT_NOT_STARTED,
    ALREADY_BOOKED,
    BOOKING_NOT_ACTIVE,
    INTERNAL_ERROR
}
