package africa.tesa.bloodlink.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into the contract's Error shape, with a stable code, a sentence safe to show the user,
 * and the request identifier. Nothing leaks a stack trace or an internal message to the caller.
 *
 * <p>Security failures (401 and 403) are routed here too, from SecurityConfig, so they share the same shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A rule refused by a service: the status, code and message were chosen where the rule lives. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> onRuleRefused(ApiException ex) {
        return respond(ex.status(), ex.code(), ex.getMessage(), ex.details());
    }

    /** No token, or a token that is expired or invalid. */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> onNotSignedIn(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Sign in to continue.");
    }

    /** Signed in, but this role or organisation may not do this. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> onNotAllowed(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "You do not have permission to do this.");
    }

    /** Body fields that break a validation rule (@Valid on a request body). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> onInvalidBody(MethodArgumentNotValidException ex) {
        List<ApiError.FieldIssue> details = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiError.FieldIssue(error.getField(), error.getDefaultMessage()))
            .toList();
        return respond(ApiException.UNPROCESSABLE, ErrorCode.VALIDATION_FAILED, "Some fields need attention.", details);
    }

    /** Parameters that break a validation rule (@Valid or constraints on path, query or header values). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> onInvalidParameters(HandlerMethodValidationException ex) {
        return respond(ApiException.UNPROCESSABLE, ErrorCode.VALIDATION_FAILED, "Some values need attention.");
    }

    /** A required header is missing, for example Idempotency-Key on POST /requests. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> onMissingHeader(MissingRequestHeaderException ex) {
        return respond(ApiException.UNPROCESSABLE, ErrorCode.VALIDATION_FAILED, "A required header is missing.",
            List.of(new ApiError.FieldIssue(ex.getHeaderName(), "Required header is missing.")));
    }

    /**
     * The body is not JSON, or a field has the wrong type (400), or the body has a field the contract does not list
     * (422). Refusing unknown fields is what keeps a patient name out: there is no field for one, so sending one fails.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> onUnreadableBody(HttpMessageNotReadableException ex) {
        // Matched by name so this works whichever Jackson version is on the classpath.
        if ("UnrecognizedPropertyException".equals(ex.getMostSpecificCause().getClass().getSimpleName())) {
            return respond(ApiException.UNPROCESSABLE, ErrorCode.VALIDATION_FAILED,
                "The body contains a field that is not allowed. Send only the fields in the contract.");
        }
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
            "The body could not be read. Send JSON that matches the contract.");
    }

    /** A path or query value of the wrong type, for example a request id that is not a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> onWrongValueType(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "A value in the address is not valid.",
            List.of(new ApiError.FieldIssue(ex.getName(), "Not a valid value.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> onMissingQueryValue(MissingServletRequestParameterException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "A required query value is missing.",
            List.of(new ApiError.FieldIssue(ex.getParameterName(), "Required.")));
    }

    /** The body is not JSON at all (wrong Content-Type). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> onNotJson(HttpMediaTypeNotSupportedException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "Send the body as application/json.");
    }

    /** A path that is not in the contract. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> onUnknownPath(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Not found.");
    }

    /** A real path called with a method the contract does not list for it. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> onWrongMethod(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.MALFORMED_REQUEST, "This method is not allowed here.");
    }

    /** A multipart Content-Type (for example one without a boundary). The API takes no uploads; every body is JSON. */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> onMultipart(MultipartException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "Send the body as application/json.");
    }

    /** Anything unexpected. The details go to the log (with the request id), never to the caller. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> onUnexpected(Exception ex) {
        if (isUnreadableQuery(ex)) {
            return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "The address could not be read.");
        }
        LOG.error("Unhandled error", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
            "Something went wrong on our side. Please try again.");
    }

    /**
     * The web server could not parse the query string, for example a parameter with no name ("?=x"). Tomcat reports this
     * as an InvalidParameterException when the parameters are first read; matched by name, like the Jackson case above.
     */
    private static boolean isUnreadableQuery(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if ("InvalidParameterException".equals(cause.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ErrorCode code, String message) {
        return respond(status, code, message, List.of());
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ErrorCode code, String message,
                                                    List<ApiError.FieldIssue> details) {
        return ResponseEntity.status(status).body(new ApiError(code, message, RequestIdFilter.current(), details));
    }
}
