package africa.tesa.bloodlink.web;

import java.util.List;

/**
 * The one error shape every failure returns (contract: components.schemas.Error).
 *
 * @param code      stable code the interface acts on
 * @param message   plain sentence safe to show to the user
 * @param requestId same value as the X-Request-Id response header
 * @param details   field-level problems; empty when not applicable, never null
 */
public record ApiError(ErrorCode code, String message, String requestId, List<FieldIssue> details) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** One problem with one body field, parameter or header. */
    public record FieldIssue(String field, String issue) {
    }
}
