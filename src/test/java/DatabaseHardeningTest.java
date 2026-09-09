import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import persistence.Database;
import persistence.SchemaMigrations;
import session.UserSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the connection-level database hardening claims (WAL, foreign
 * keys, busy timeout) with real PRAGMA reads, and that startup ABORTS on an
 * incompatible (newer) schema instead of continuing and corrupting data.
 */
class DatabaseHardeningTest {

    @BeforeAll
    static void isolateData() {
        try {
            Path dataDir = Files.createTempDirectory("aether-hardening-test");
            System.setProperty("aether.data.dir", dataDir.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        SchemaMigrations.migrateIfNeeded(); // fresh database at latest version
    }

    private String pragma(String name) throws Exception {
        try (Connection c = Database.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void walModeActiveOnFreshDatabase() throws Exception {
        assertEquals("wal", pragma("journal_mode").toLowerCase(),
                "fresh databases must run in WAL mode (crash-safe)");
    }

    @Test
    void foreignKeysEnforcedPerConnection() throws Exception {
        assertEquals("1", pragma("foreign_keys"),
                "every connection must enforce foreign keys");
    }

    @Test
    void busyTimeoutConfigured() throws Exception {
        assertEquals("5000", pragma("busy_timeout"),
                "connections must wait instead of failing on momentary locks");
    }

    @Test
    void incompatibleDatabaseAbortsStartup() throws Exception {
        // Stamp the database as written by a NEWER build.
        try (Connection c = Database.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE schema_info SET version = "
                    + (SchemaMigrations.LATEST_VERSION + 1));
        }

        try {
            // Reset the singleton: another test class may have initialized it
            // earlier in this JVM (a fresh construction is what startup does).
            java.lang.reflect.Field f = UserSession.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, null);

            // Startup must refuse to construct the session: continuing would
            // risk corrupting a database written by a newer build (spec §49).
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    UserSession::getInstance,
                    "startup must abort when the schema version is newer than the build");
            assertTrue(error.getMessage().contains("incompatível")
                            || error.getMessage().contains("incompatible"),
                    "the error must explain the incompatibility: " + error.getMessage());

            // And the version stamp is untouched.
            assertEquals(SchemaMigrations.LATEST_VERSION + 1, SchemaMigrations.currentVersion());
        } finally {
            // Restore compatibility so later test classes in this JVM are not
            // poisoned by the incompatible stamp.
            try (Connection c = Database.getConnection();
                 Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE schema_info SET version = "
                        + SchemaMigrations.LATEST_VERSION);
            }
            // And leave a working singleton behind for later tests.
            java.lang.reflect.Field f = UserSession.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, null);
            UserSession.getInstance();
        }
    }
}
