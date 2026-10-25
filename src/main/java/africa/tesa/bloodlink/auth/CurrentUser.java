package africa.tesa.bloodlink.auth;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling, read from their verified token. Services use this for organisation checks such as
 * "only staff of the bank that owns this unit". Never trust an organisation id sent in a request body.
 *
 * @param id             the user's id (the token's subject)
 * @param role           the user's role
 * @param organisationId the bank or hospital they work for; null for donors and the administrator
 */
public record CurrentUser(UUID id, Role role, UUID organisationId) {

    /** The signed-in user of the request being handled. Only call this on paths that require sign-in. */
    public static CurrentUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("No signed-in user on this request");
        }
        return from(token.getToken());
    }

    static CurrentUser from(Jwt jwt) {
        String organisation = jwt.getClaimAsString(TokenConfig.ORGANISATION_CLAIM);
        return new CurrentUser(
            UUID.fromString(jwt.getSubject()),
            Role.valueOf(jwt.getClaimAsString(TokenConfig.ROLE_CLAIM)),
            organisation == null ? null : UUID.fromString(organisation));
    }

    public boolean is(Role candidate) {
        return role == candidate;
    }

    /** True when this user is staff of the given organisation (a bank or a hospital). */
    public boolean worksFor(UUID organisation) {
        return organisationId != null && organisationId.equals(organisation);
    }
}
