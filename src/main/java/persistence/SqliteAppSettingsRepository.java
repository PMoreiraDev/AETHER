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
     * Creates the application settings table if necessary.
     */
    private void createTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS app_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    schema_version INTEGER NOT NULL DEFAULT 1,
                    active_model_id TEXT NOT NULL DEFAULT '',
                    onboarding_completed INTEGER NOT NULL DEFAULT 0
                )
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.executeUpdate();

        } catch (SQLException e) {
            throw new PersistenceException(
                    "Could not create app_settings table.",
                    e
            );
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
                SELECT active_model_id, onboarding_completed
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
                    onboarding_completed
                )
                VALUES (1, 1, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    active_model_id = excluded.active_model_id,
                    onboarding_completed = excluded.onboarding_completed
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, settings.getActiveModelId());
            statement.setInt(
                    2,
                    settings.isOnboardingCompleted() ? 1 : 0
            );

            statement.executeUpdate();

        } catch (SQLException e) {
            throw new PersistenceException(
                    "Could not save application settings.",
                    e
            );
        }
    }
}