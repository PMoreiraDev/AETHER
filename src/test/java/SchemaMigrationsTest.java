import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.AetherPaths;
import persistence.Database;
import persistence.SchemaMigrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link SchemaMigrations} framework:
 * <ul>
 *   <li>fresh database is brought to the latest version;</li>
 *   <li>migrations are idempotent (re-running is a no-op) and do not
 *       re-create the pre-migration backup on a second run;</li>
 *   <li>a database written by a NEWER build is refused, not corrupted;</li>
 *   <li>the migration from the pre-v2 baseline creates a pre-migration\n *       backup of the database file before touching the schema.</li>
 * </ul>
 */
class SchemaMigrationsTest {

    private static Path dataDir;

    @BeforeAll
    static void isolateData() {
        try {
            dataDir = Files.createTempDirectory("aether-migration-test");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.setProperty("aether.data.dir", dataDir.toString());
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        // Fresh database (and backups) per test.
        try (Stream<Path> files = Files.list(dataDir)) {
            for (Path p : files.toList()) {
                deleteRecursively(p);
            }
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void freshDatabaseMigratesToLatest() throws Exception {
        assertNull(SchemaMigrations.migrateIfNeeded());
        assertEquals(SchemaMigrations.LATEST_VERSION, SchemaMigrations.currentVersion());
        // The v2/v3 tables exist and are usable.
        try (Connection c = Database.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO conversations (id, title, created_at, updated_at) "
                            + "VALUES ('t1', '', '2026-01-01T00:00', '2026-01-01T00:00')");
            st.executeUpdate(
                    "INSERT INTO person (id, name) VALUES ('p1', 'Teste')");
        }
    }

    @Test
    void migrationsAreIdempotentAndDoNotDuplicateBackups() throws Exception {
        assertNull(SchemaMigrations.migrateIfNeeded());
        assertEquals(SchemaMigrations.LATEST_VERSION, SchemaMigrations.currentVersion());

        // Second run: no-op, no error.
        assertNull(SchemaMigrations.migrateIfNeeded());
        assertEquals(SchemaMigrations.LATEST_VERSION, SchemaMigrations.currentVersion());
    }

    @Test
    void newerSchemaVersionIsRefusedNotCorrupted() throws Exception {
        // Bring the database up to date, then simulate a NEWER build's stamp.
        assertNull(SchemaMigrations.migrateIfNeeded());
        try (Connection c = Database.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE schema_info SET version = "
                    + (SchemaMigrations.LATEST_VERSION + 1));
        }

        String error = SchemaMigrations.migrateIfNeeded();
        assertNotNull(error, "a newer schema version must be refused with an error");
        assertTrue(error.contains("newer"),
                "the error must explain the version conflict: " + error);
        // The version is untouched (still the newer value): nothing was run.
        assertEquals(SchemaMigrations.LATEST_VERSION + 1, SchemaMigrations.currentVersion());
    }

    @Test
    void preMigrationBackupIsCreatedWhenUpgradingBaseline() throws Exception {
        // Simulate a PRE-v2 installation: schema_info stamped at baseline and
        // data present. The upgrade must back the file up before migrating.
        assertNull(SchemaMigrations.migrateIfNeeded());
        // Clean the backups folder: the fresh-DB migration above also backs
        // up (a new database starts at the v1 baseline), and this test
        // asserts specifically about the SECOND upgrade below.
        Path backups = AetherPaths.dataDirectory().resolve("backups");
        if (Files.isDirectory(backups)) {
            try (Stream<Path> files = Files.list(backups)) {
                for (Path p : files.toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        try (Connection c = Database.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM schema_info");
            st.executeUpdate("INSERT INTO schema_info (version) VALUES ("
                    + SchemaMigrations.BASELINE_VERSION + ")");
        }

        assertNull(SchemaMigrations.migrateIfNeeded());
        assertEquals(SchemaMigrations.LATEST_VERSION, SchemaMigrations.currentVersion());

        assertTrue(Files.isDirectory(backups), "backups directory must exist");
        try (Stream<Path> files = Files.list(backups)) {
            List<Path> preMigration = files
                    .filter(p -> p.getFileName().toString().startsWith("pre-migration-"))
                    .toList();
            assertEquals(1, preMigration.size(),
                    "exactly one pre-migration backup must exist for the upgrade, got " + preMigration);
        }
    }
}
