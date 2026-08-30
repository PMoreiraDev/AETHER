package persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Provides access to the local AETHER SQLite database.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class Database {

    /** SQLite database file name. */
    private static final String DATABASE_NAME = "AETHER.db";

    /** Database directory name. */
    private static final String DATA_DIRECTORY = "AETHER";

    /** JDBC URL prefix. */
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    /** Prevents instantiation. */
    private Database() {
    }

    /**
     * Opens a connection to the AETHER database.
     *
     * @return an open SQLite connection
     */
    public static Connection getConnection() {
        try {
            Path databasePath = getDatabasePath();

            Files.createDirectories(databasePath.getParent());

            return DriverManager.getConnection(
                    JDBC_PREFIX + databasePath
            );
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Could not open AETHER database.", e);
        }
    }

    /**
     * Returns the path where the database is stored.
     *
     * @return database path
     */
    public static Path getDatabasePath() {
        String customDirectory = System.getProperty("aether.data.dir");

        if (customDirectory != null && !customDirectory.isBlank()) {
            return Path.of(customDirectory).resolve(DATABASE_NAME);
        }

        String appData = System.getenv("APPDATA");

        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, DATA_DIRECTORY, DATABASE_NAME);
        }

        return Path.of(
                System.getProperty("user.home"),
                DATA_DIRECTORY,
                DATABASE_NAME
        );
    }

    /**
     * Resets the development onboarding state while preserving user data.
     * <p>
     * Intended for development and testing only. The saved profile remains
     * available so the setup can be repeated with the existing user data.
     * </p>
     */
    public static void resetForDevelopment() {
        // Development reset intentionally keeps the user's profile data.
        // It only resets the onboarding state so the setup can be repeated.
        // This allows developers to test the complete setup flow and still
        // arrive at the dashboard with the profile entered previously.
        String createSettingsTable = """
                CREATE TABLE IF NOT EXISTS app_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    schema_version INTEGER NOT NULL DEFAULT 1,
                    active_model_id TEXT NOT NULL DEFAULT '',
                    onboarding_completed INTEGER NOT NULL DEFAULT 0
                )
                """;

        String resetSettings = """
                INSERT INTO app_settings
                    (id, schema_version, active_model_id, onboarding_completed)
                VALUES (1, 1, '', 0)
                ON CONFLICT(id) DO UPDATE SET
                    active_model_id = excluded.active_model_id,
                    onboarding_completed = excluded.onboarding_completed
                """;

        try (Connection connection = getConnection();
             java.sql.PreparedStatement create = connection.prepareStatement(createSettingsTable);
             java.sql.PreparedStatement reset = connection.prepareStatement(resetSettings)) {
            create.executeUpdate();
            reset.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not reset AETHER onboarding state.", e);
        }
    }
}