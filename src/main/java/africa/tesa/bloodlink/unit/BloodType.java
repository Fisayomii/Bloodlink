package africa.tesa.bloodlink.unit;

/**
 * The allowed values from the contract, used for query filters. Each matches a CHECK constraint in 001-core.sql.
 * Matching is always exact: the service never substitutes one group or component for another.
 */
public final class BloodType {

    private BloodType() {
    }

    /** ABO group. With rhesus this gives the eight groups. */
    public enum Group { A, B, AB, O }

    public enum Rhesus { POSITIVE, NEGATIVE }

    public enum Component { WHOLE_BLOOD, RED_CELLS, PLATELETS, PLASMA, CRYOPRECIPITATE }

    /** A unit has exactly one state at a time. */
    public enum UnitState { AVAILABLE, RESERVED, ISSUED, EXPIRED, DISCARDED }

    /** Patterns for validating request bodies, so a bad value names its field in a 422 (see UnitCreate). */
    static final String GROUP_PATTERN = "A|B|AB|O";
    static final String RHESUS_PATTERN = "POSITIVE|NEGATIVE";
    static final String COMPONENT_PATTERN = "WHOLE_BLOOD|RED_CELLS|PLATELETS|PLASMA|CRYOPRECIPITATE";
}
