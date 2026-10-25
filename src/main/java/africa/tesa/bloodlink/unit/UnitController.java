package africa.tesa.bloodlink.unit;

import africa.tesa.bloodlink.unit.BloodType.Component;
import africa.tesa.bloodlink.unit.BloodType.Group;
import africa.tesa.bloodlink.unit.BloodType.Rhesus;
import africa.tesa.bloodlink.unit.BloodType.UnitState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Blood units held by a bank.
 *
 * <ul>
 *   <li>POST /units: bank staff record one received unit at their own bank. Neither safe nor idempotent, but a repeated
 *       reference at the same bank is refused (409), so a retry after a lost response is detected, not duplicated.</li>
 *   <li>GET /units: bank staff see their own bank's units; the administrator sees all and may filter by bank. Safe.</li>
 * </ul>
 * Who may call each one is set in SecurityConfig; which bank is decided here from the token, never from the request.
 */
@RestController
public class UnitController {

    private final UnitService units;

    public UnitController(UnitService units) {
        this.units = units;
    }

    @PostMapping("/units")
    ResponseEntity<UnitView> record(@Valid @RequestBody UnitCreate body) {
        UnitView unit = units.record(body);
        return ResponseEntity.created(URI.create("/units/" + unit.id())).body(unit);
    }

    @GetMapping("/units")
    UnitPage list(@RequestParam(required = false) UUID bankId,
                  @RequestParam(required = false) UnitState state,
                  @RequestParam(required = false) Group bloodGroup,
                  @RequestParam(required = false) Rhesus rhesus,
                  @RequestParam(required = false) Component component,
                  @RequestParam(defaultValue = "0") int page,
                  @RequestParam(defaultValue = "20") int size) {
        return units.list(new UnitService.UnitFilter(bankId, state, bloodGroup, rhesus, component), page, size);
    }

    /**
     * Contract: UnitCreate. Values are checked as text against the allowed lists, so a wrong value comes back as a 422
     * naming the field, which the interface can show next to that field. There is no bank field: the bank comes from
     * the signed-in user, and sending one is refused like any other unknown field.
     */
    public record UnitCreate(
        @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "Use letters, digits and hyphens only.")
        String reference,
        @NotNull @Pattern(regexp = BloodType.GROUP_PATTERN, message = "Must be one of A, B, AB, O.")
        String bloodGroup,
        @NotNull @Pattern(regexp = BloodType.RHESUS_PATTERN, message = "Must be POSITIVE or NEGATIVE.")
        String rhesus,
        @NotNull @Pattern(regexp = BloodType.COMPONENT_PATTERN,
            message = "Must be one of WHOLE_BLOOD, RED_CELLS, PLATELETS, PLASMA, CRYOPRECIPITATE.")
        String component,
        @NotNull LocalDate collectionDate,
        @NotNull LocalDate expiryDate) {
    }

    /** Contract: UnitPage. */
    public record UnitPage(List<UnitView> items, int page, int size, long totalItems) {
    }
}
