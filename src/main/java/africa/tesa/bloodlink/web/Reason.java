package africa.tesa.bloodlink.web;

/**
 * A reason someone types (declining, reversing an issue, cancelling, releasing a hold): trimmed, then 3 to 500 characters.
 *
 * <p>Characters are counted the way PostgreSQL counts them (Unicode code points), not as Java counts them. An emoji is one
 * character to the database but two to Java, and Java's trim also removes control characters. So "\u0003\u0004" plus two
 * emoji passed a Java @Size(min = 3), was stored as two emoji, and the database's own rule (at least 3) refused it with a
 * 500. Found by the contract tests on 30 October. Every reason now goes through here before it is stored.
 */
public final class Reason {

    public static final int MIN = 3;
    public static final int MAX = 500;

    private Reason() {
    }

    /** The reason as it will be stored, or 422 VALIDATION_FAILED naming the field. */
    public static String check(String field, String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int characters = trimmed.codePointCount(0, trimmed.length());
        if (characters < MIN || characters > MAX) {
            throw ApiException.validation(field, "Between " + MIN + " and " + MAX
                + " characters, not counting spaces or control characters at either end.");
        }
        return trimmed;
    }
}
