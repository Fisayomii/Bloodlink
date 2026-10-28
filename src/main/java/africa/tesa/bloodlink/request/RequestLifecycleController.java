package africa.tesa.bloodlink.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract v0.2: what happens to a request after the bank's decision. Each path records a thing (an issue, its
 * reversal, a cancellation, a hold release), so each answers 201 with the request as it now stands. None is safe or
 * idempotent: doing it twice gets a 409 naming why. Who may call each path is set in SecurityConfig; which requests a
 * user may act on is checked in RequestLifecycleService.
 */
@RestController
public class RequestLifecycleController {

    private final RequestLifecycleService lifecycle;

    public RequestLifecycleController(RequestLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/requests/{requestId}/issue")
    @ResponseStatus(HttpStatus.CREATED)
    BloodRequestView issue(@PathVariable UUID requestId) {
        return lifecycle.issue(requestId);
    }

    @PostMapping("/requests/{requestId}/issue/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    BloodRequestView reverseIssue(@PathVariable UUID requestId, @Valid @RequestBody IssueReversal body) {
        return lifecycle.reverseIssue(requestId, body.reason());
    }

    @PostMapping("/requests/{requestId}/cancellation")
    @ResponseStatus(HttpStatus.CREATED)
    BloodRequestView cancel(@PathVariable UUID requestId, @Valid @RequestBody Cancellation body) {
        return lifecycle.cancel(requestId, body.reason());
    }

    @PostMapping("/requests/{requestId}/hold-release")
    @ResponseStatus(HttpStatus.CREATED)
    BloodRequestView releaseHold(@PathVariable UUID requestId, @Valid @RequestBody HoldRelease body) {
        return lifecycle.releaseHoldFor(requestId, body.forRequestId(), body.reason());
    }

    /** Contract: IssueReversal. */
    public record IssueReversal(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    /** Contract: Cancellation. Why the hospital no longer needs the units; never a patient's name. */
    public record Cancellation(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    /** Contract: HoldRelease. */
    public record HoldRelease(@NotNull UUID forRequestId, @NotBlank @Size(min = 3, max = 500) String reason) {
    }
}
