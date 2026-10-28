package africa.tesa.bloodlink.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Thrown by services when a request breaks a rule. GlobalExceptionHandler turns it into the contract's
 * Error body with this status. Because it is unchecked, throwing it inside a @Transactional method also
 * rolls the transaction back, so a refused request never leaves half its changes behind.
 */
public class ApiException extends RuntimeException {

    /** 422. Looked up by number because its constant name differs between Spring versions. */
    public static final HttpStatus UNPROCESSABLE = HttpStatus.valueOf(422);

    private final HttpStatus status;
    private final ErrorCode code;
    private final List<ApiError.FieldIssue> details;

    public ApiException(HttpStatus status, ErrorCode code, String message, List<ApiError.FieldIssue> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, List.of());
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, message);
    }

    public static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 400: a query or path value the contract does not allow (contract: BadRequest). */
    public static ApiException malformed(String field, String issue) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "A value in the address is not valid.",
            List.of(new ApiError.FieldIssue(field, issue)));
    }

    public static ApiException validation(String message, List<ApiError.FieldIssue> details) {
        return new ApiException(UNPROCESSABLE, ErrorCode.VALIDATION_FAILED, message, details);
    }

    public static ApiException validation(String field, String issue) {
        return validation("Some fields need attention.", List.of(new ApiError.FieldIssue(field, issue)));
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiError.FieldIssue> details() {
        return details;
    }
}
