package africa.tesa.bloodlink.auth;

import africa.tesa.bloodlink.organisation.OrganisationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /sessions: sign in with a demonstration account and receive an access token.
 * "Creates a session" rather than "logs in", because the brief asks for resources named as things.
 * Neither safe nor idempotent: each call issues a new token.
 */
@RestController
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    Session create(@Valid @RequestBody SessionCreate body) {
        return sessions.signIn(body.email(), body.password());
    }

    /** Contract: SessionCreate. Limits match the contract so the service refuses what the mock refuses. */
    public record SessionCreate(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password) {
    }

    /** Contract: Session. */
    public record Session(String accessToken, String tokenType, Instant expiresAt, User user) {
    }

    /** Contract: User. organisation is null for donors and the administrator. */
    public record User(UUID id, String displayName, String email, Role role, OrganisationView organisation) {
    }
}
