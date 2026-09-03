package persistence;

import domain.AppSettings;
import repository.AppSettingsRepository;
import repository.PersistenceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * SQLite implementation of the application settings repository.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class SqliteAppSettingsRepository implements AppSettingsRepository {

    /**
     * Creates the repository and ensures its table exists.
     */
    public SqliteAppSettingsRepository() {
        createTable();
    }

    /**
     * Creates the application settings table if necessary, and applies
     * lightweight column additions (ALTER TABLE) for schema versions above 1,
     * so existing databases keep working without a destructive migration.
     */
    private void createTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS app_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    schema_version INTEGER NOT NULL DEFAULT 1,
                    active_model_id TEXT NOT NULL DEFAULT '',
                    onboarding_completed INTEGER NOT NULL DEFAULT 0,
                    vault_path_override TEXT NOT NULL DEFAULT '',
                    ollama_path_override TEXT NOT NULL DEFAULT ''
                )
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.executeUpdate();
            ensureColumn(connection, "vault_path_override");
            ensureColumn(connection, "ollama_path_override");

        } catch (SQLException e) {
            throw new PersistenceException(
                    "Could not create app_settings table.",
                    e
            );
        }
    }

    /**
     * Adds a TEXT column with a default empty-string value if it does not yet
     * exist. SQLite ALTER TABLE ADD COLUMN is idempotent-safe here because we
     * first check the table's columns.
     *
     * @param connection the database connection
     * @param column     the column name to add
     */
    private void ensureColumn(Connection connection, String column) {
        try {
            boolean exists = false;
            try (ResultSet columns = connection.getMetaData().getColumns(
                    null, null, "app_settings", column)) {
                exists = columns.next();
            }
            if (!exists) {
                connection.createStatement().executeUpdate(
                        "ALTER TABLE app_settings ADD COLUMN " + column + " TEXT NOT NULL DEFAULT ''");
            }
        } catch (SQLException ignored) {
            // Column may already exist or table is brand new; non-fatal.
        }
    }

    /**
     * Loads the stored application settings.
     *
     * @return stored settings or empty if no settings exist
     */
    @Override
    public Optional<AppSettings> load() {
        String sql = """
                SELECT active_model_id, onboarding_completed,
                       vault_path_override, ollama_path_override
                FROM app_settings
                WHERE id = 1
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            if (!result.next()) {
                return Optional.empty();
            }

            AppSettings settings = new AppSettings();

            settings.setActiveModelId(
                    result.getString("active_model_id")
            );

            settings.setOnboardingCompleted(
                    result.getInt("onboarding_completed") == 1
            );

            settings.setVaultPathOverride(
                    safeString(result, "vault_path_override")
            );

            settings.setOllamaPathOverride(
                    safeString(result, "ollama_path_override")
            );

            return Optional.of(settings);

        } catch (SQLException e) {
            throw new PersistenceException(
                    "Could not load application settings.",
                    e
            );
        }
    }

    /**
     * Saves the application settings.
     *
     * @param settings settings to save
     */
    @Override
    public void save(AppSettings settings) {
        String sql = """
                INSERT INTO app_settings (
                    id,
                    schema_version,
                    active_model_id,
                    onboarding_completed,
                    vault_path_override,
                    ollama_path_override
                )
                VALUES (1, 1, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    active_model_id = excluded.active_model_id,
                    onboarding_completed = excluded.onboarding_completed,
                    vault_path_override = excluded.vault_path_override,
                    ollama_path_override = excluded.ollama_path_override
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, settings.getActiveModelId());
            statement.setInt(
                    2,
                    settings.isOnboardingCompleted() ? 1 : 0
            );
            statement.setString(3, settings.getVaultPathOverride());
            statement.setString(4, settings.getOllamaPathOverride());

            statement.executeUpdate();

        } catch (SQLException e) {
            throw new PersistenceException(
                    "Could not save application settings.",
                    e
            );
        }
    }

    /**
     * Reads a column that may be missing in older databases without throwing,
     * returning an empty string when the column does not exist.
     *
     * @param result the result set
     * @param column the column name
     * @return the value, or empty string
     */
    private String safeString(ResultSet result, String column) {
        try {
            String value = result.getString(column);
            return value == null ? "" : value;
        } catch (SQLException ignored) {
            return "";
        }
    }
}