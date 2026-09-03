import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.Database;
import persistence.VaultManager;
import repository.AppSettingsRepository;
import persistence.SqliteAppSettingsRepository;
import session.UserSession;
import domain.AppSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the new vault and Ollama path overrides are persisted to the
 * SQLite database and reloaded correctly — the schema migration must add the
 * new columns to an existing app_settings table without losing data.
 */
class SettingsPathOverrideTest {

    private AppSettingsRepository repository;

    @BeforeEach
    void setup() throws Exception {
        Path tmp = Files.createTempDirectory("aether-settings-test");
        System.setProperty("aether.data.dir", tmp.toString());
        // Re-create the settings table fresh each run.
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS app_settings");
        }
        repository = new SqliteAppSettingsRepository();
    }

    @Test
    void vaultPathOverride_isPersistedAndReloaded() {
        AppSettings settings = new AppSettings();
        settings.setVaultPathOverride("/tmp/my-aether-vault");
        settings.setOllamaPathOverride("/opt/ollama/bin");
        repository.save(settings);

        AppSettings loaded = repository.load().orElseThrow();
        assertEquals("/tmp/my-aether-vault", loaded.getVaultPathOverride());
        assertEquals("/opt/ollama/bin", loaded.getOllamaPathOverride());
    }

    @Test
    void blankOverride_resetsToDefaultVaultLocation() {
        // Save an override, then clear it — the vault path must fall back to
        // the default AETHER-Vault folder under the data dir.
        AppSettings settings = new AppSettings();
        settings.setVaultPathOverride("/some/other/place");
        repository.save(settings);

        settings.setVaultPathOverride("");
        repository.save(settings);

        UserSession session = UserSession.getInstance();
        session.getAppSettings().setVaultPathOverride("");
        Path vault = VaultManager.getVaultPath();
        assertTrue(vault.endsWith("AETHER-Vault"),
                "blank override should resolve to default vault folder, got " + vault);
    }

    @Test
    void migration_addsColumnsToExistingOldTable() throws Exception {
        // Simulate an old database that only has the original columns.
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS app_settings");
            s.executeUpdate("""
                    CREATE TABLE app_settings (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        active_model_id TEXT NOT NULL DEFAULT '',
                        onboarding_completed INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            s.executeUpdate("""
                    INSERT INTO app_settings (id, schema_version, active_model_id, onboarding_completed)
                    VALUES (1, 1, 'llama3.2:3b', 1)
                    """);
        }

        // Constructing the repository must migrate the table (add columns)
        // without losing the existing active model.
        AppSettingsRepository migrated = new SqliteAppSettingsRepository();
        AppSettings loaded = migrated.load().orElseThrow();

        assertEquals("llama3.2:3b", loaded.getActiveModelId());
        assertTrue(loaded.isOnboardingCompleted());
        assertEquals("", loaded.getVaultPathOverride());
        assertEquals("", loaded.getOllamaPathOverride());

        // And saving new overrides must now work.
        loaded.setVaultPathOverride("/tmp/migrated-vault");
        migrated.save(loaded);
        AppSettings reloaded = migrated.load().orElseThrow();
        assertEquals("/tmp/migrated-vault", reloaded.getVaultPathOverride());
    }
}
