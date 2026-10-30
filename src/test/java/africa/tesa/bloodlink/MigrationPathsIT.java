package africa.tesa.bloodlink;

import static org.assertj.core.api.Assertions.assertThat;

import africa.tesa.bloodlink.support.AbstractPostgresIT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Liquibase knows a migration by its id, author AND file path. The service migrates from inside the jar; the pipeline
 * migrates the live database with the Maven plugin from src/main/resources. If the two recorded different paths (for
 * example "classpath:db/..." against "db/..."), the pipeline would think every migration was new and run them all again
 * on the live database. This test fails first: the paths the service records must be the plain paths the plugin uses.
 */
class MigrationPathsIT extends AbstractPostgresIT {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Autowired
    JdbcClient jdbc;

    @Test
    void theServiceRecordsTheSameMigrationPathsAsThePipeline() throws IOException {
        List<String> recorded = jdbc.sql("SELECT DISTINCT filename FROM databasechangelog ORDER BY filename")
            .query(String.class).list();

        List<String> files;
        try (Stream<Path> changes = Files.list(RESOURCES.resolve("db/changelog/changes"))) {
            files = changes.map(file -> RESOURCES.relativize(file).toString().replace('\\', '/')).sorted().toList();
        }

        assertThat(recorded).as("no classpath: prefix or other difference").allMatch(path -> path.startsWith("db/changelog/"));
        assertThat(recorded).containsAll(files);
    }
}
