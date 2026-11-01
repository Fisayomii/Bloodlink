package africa.tesa.bloodlink.auth;

import africa.tesa.bloodlink.auth.SessionController.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /donors: a voluntary donor creates their own account and is signed in straight away (contract v0.2.6).
 * Only donor accounts can be created this way; bank and hospital staff are set up by the team, not by themselves.
 * Neither safe nor idempotent: a repeat with the same email answers 409, and the donor then signs in.
 */
@RestController
public class DonorController {

    private final DonorService donors;

    public DonorController(DonorService donors) {
        this.donors = donors;
    }

    @PostMapping("/donors")
    @ResponseStatus(HttpStatus.CREATED)
    Session create(@Valid @RequestBody DonorCreate body) {
        return donors.signUp(body.email(), body.displayName(), body.password());
    }

    /**
     * Contract: DonorCreate. The display name's length is checked in DonorService, counted as the database counts it.
     * The password's upper limit is also checked there in bytes: bcrypt uses only the first 72 bytes.
     */
    public record DonorCreate(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull String displayName,
        @NotBlank @Size(min = 8, max = 72) String password) {
    }
}
