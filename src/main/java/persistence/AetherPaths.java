package persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the directory used by AETHER for local application data.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class AetherPaths {

    /** System property used to override the data directory during development and tests. */
    private static final String DATA_DIR_PROPERTY = "aether.data.dir";

    /** Application folder name on Windows and macOS. */
    private static final String APP_FOLDER = "AETHER";

    private AetherPaths() {
        // Utility class.
    }

    /**
     * Returns the directory where the local database is stored.
     *
     * @return AETHER's data directory
     */
    public static Path dataDirectory() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, APP_FOLDER);
            }
        }

        if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", APP_FOLDER);
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Paths.get(xdgDataHome, "aether");
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share", "aether");
    }

    /**
     * Returns the SQLite database path.
     *
     * @return the database file path
     */
    public static Path databaseFile() {
        // Delega em Database.getDatabasePath() — UMA definição do local da
        // base de dados (sem duplicação). Nota: historicamente o local da DB
        // (APPDATA/~/AETHER) diferia do dataDirectory() usado pelo vault; o
        // comportamento existente é preservado para não mover silenciosamente
        // bases de dados de instalações em uso.
        return Database.getDatabasePath();
    }
}
