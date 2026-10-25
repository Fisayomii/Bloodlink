package africa.tesa.bloodlink.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit test (no Spring, no database): which identifiers from the interface are safe to keep. */
class RequestIdFilterTest {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Test
    void keepsSafeIdentifiersUpTo64Characters() {
        assertThat(RequestIdFilter.acceptOrCreate("swagger-1f2e:3_4.5")).isEqualTo("swagger-1f2e:3_4.5");
        assertThat(RequestIdFilter.acceptOrCreate("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    @Test
    void replacesAnythingElseWithANewUuid() {
        assertThat(RequestIdFilter.acceptOrCreate("a".repeat(65))).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.acceptOrCreate("has space")).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.acceptOrCreate("line\nbreak")).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.acceptOrCreate("")).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.acceptOrCreate(null)).matches(UUID_PATTERN);
    }
}
