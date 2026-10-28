package africa.tesa.bloodlink.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The body for failures that never reach a controller, so GlobalExceptionHandler cannot shape them: for example a path
 * the security firewall rejects (an encoded "%" or "/") or a request the web server itself refuses. The server forwards
 * these to /error. Spring Boot's default body there ({timestamp, status, error, path}) is not the contract's Error
 * shape, so this replaces it: same status, a contract code, the request id. The contract tests found the difference.
 */
@RestController
public class ErrorPageController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ApiError> error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = statusOf(request);
        // Set by RequestIdFilter when the request got that far; a rejection before it gets a new identifier.
        String requestId = response.getHeader(RequestIdFilter.HEADER);
        if (requestId == null) {
            requestId = RequestIdFilter.acceptOrCreate(request.getHeader(RequestIdFilter.HEADER));
        }
        return ResponseEntity.status(status)
            .header(RequestIdFilter.HEADER, requestId)
            .body(new ApiError(codeFor(status), messageFor(status), requestId, List.of()));
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        return status != null && status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    static ErrorCode codeFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHENTICATED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            default -> status.is4xxClientError() ? ErrorCode.MALFORMED_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static String messageFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "Sign in to continue.";
            case FORBIDDEN -> "You do not have permission to do this.";
            case NOT_FOUND -> "Not found.";
            default -> status.is4xxClientError()
                ? "The request could not be read."
                : "Something went wrong on our side. Please try again.";
        };
    }
}
