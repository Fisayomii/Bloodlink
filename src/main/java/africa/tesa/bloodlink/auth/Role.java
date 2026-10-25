package africa.tesa.bloodlink.auth;

/** The four roles from the contract (components.schemas.Role). Stored in app_user.role and in every token. */
public enum Role {
    BANK_STAFF,
    HOSPITAL_STAFF,
    DONOR,
    ADMIN;

    /** The name Spring Security checks in hasRole("BANK_STAFF"), which looks for the authority "ROLE_BANK_STAFF". */
    public String authority() {
        return "ROLE_" + name();
    }
}
