package africa.tesa.bloodlink.request;

import africa.tesa.bloodlink.web.ApiException;
import africa.tesa.bloodlink.web.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Blood requests. Who may call each path is set in SecurityConfig; which requests a user may see or decide on is
 * checked in RequestService.
 *
 * <ul>
 *   <li>POST /requests: hospital staff request units. Idempotent with an Idempotency-Key.</li>
 *   <li>GET /requests, GET /requests/{id}: safe.</li>
 *   <li>POST /requests/{id}/approval and /declination: the bank records its decision. Neither safe nor idempotent:
 *       deciding twice gets 409 REQUEST_NOT_PENDING.</li>
 * </ul>
 */
@RestController
public class RequestController {

    private static final String STATE_PATTERN = "PENDING|APPROVED|DECLINED|CANCELLED|FULFILLED|LAPSED|UNIT_EXPIRED";

    private final RequestService requests;

    public RequestController(RequestService requests) {
        this.requests = requests;
    }

    @PostMapping("/requests")
    ResponseEntity<BloodRequestView> create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                            @Valid @RequestBody BloodRequestCreate body) {
        RequestService.Created created = requests.create(idempotencyKey, body);
        if (created.replayed()) {
            // Same key and same body as an earlier submission: nothing new was recorded.
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(created.request());
        }
        return ResponseEntity.created(URI.create("/requests/" + created.request().id())).body(created.request());
    }

    @GetMapping("/requests")
    BloodRequestPage list(@RequestParam(required = false) String state,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        if (state != null && !state.matches(STATE_PATTERN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "state is not one of the allowed values.");
        }
        return requests.list(state, page, size);
    }

    @GetMapping("/requests/{requestId}")
    BloodRequestView get(@PathVariable UUID requestId) {
        return requests.get(requestId);
    }

    @PostMapping("/requests/{requestId}/approval")
    @ResponseStatus(HttpStatus.CREATED)
    BloodRequestView approve(@PathVariable UUID requestId) {
        return requests.approve(requestId);
    }

    @PostMapping("/requests/{requestId}/declination")
    @ResponseStatus(HttpStatus.CREATED)
    BloodRequestView decline(@PathVariable UUID requestId, @Valid @RequestBody Declination body) {
        return requests.decline(requestId, body.reason());
    }

    /**
     * Contract: BloodRequestCreate. No patient field exists; a body with any field not listed here is refused (422).
     * Values are checked as text against the allowed lists, so a wrong value names its field.
     */
    public record BloodRequestCreate(
        @NotNull UUID bankId,
        @NotNull @Pattern(regexp = "A|B|AB|O", message = "Must be one of A, B, AB, O.") String bloodGroup,
        @NotNull @Pattern(regexp = "POSITIVE|NEGATIVE", message = "Must be POSITIVE or NEGATIVE.") String rhesus,
        @NotNull @Pattern(regexp = "WHOLE_BLOOD|RED_CELLS|PLATELETS|PLASMA|CRYOPRECIPITATE",
            message = "Must be one of WHOLE_BLOOD, RED_CELLS, PLATELETS, PLASMA, CRYOPRECIPITATE.") String component,
        @NotNull @Min(1) @Max(20) Integer quantity,
        @NotNull @Pattern(regexp = "ROUTINE|URGENT|EMERGENCY", message = "Must be ROUTINE, URGENT or EMERGENCY.")
        String urgency,
        @NotNull OffsetDateTime neededBy) {
    }

    /** Contract: Declination. */
    public record Declination(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    /** Contract: BloodRequestPage. */
    public record BloodRequestPage(List<BloodRequestView> items, int page, int size, long totalItems) {
    }
}
