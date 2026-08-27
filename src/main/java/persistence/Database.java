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
     * Deletes the local database.
     * <p>
     * Intended for development and testing only.
     * </p>
     */
    public static void reset() {
        Path databasePath = getDatabasePath();

        try {
            Files.deleteIfExists(databasePath);
            Files.deleteIfExists(
                    Path.of(databasePath + "-shm")
            );
            Files.deleteIfExists(
                    Path.of(databasePath + "-wal")
            );
        } catch (IOException e) {
            throw new RuntimeException("Could not reset AETHER database.", e);
        }
    }
}