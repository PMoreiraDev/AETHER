package persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.sqlite.SQLiteConfig;

/**
 * Provides access to the local AETHER SQLite database.
 * <p>
 * Every connection opened through this class is hardened consistently:
 * </p>
 * <ul>
 *   <li>{@code journal_mode=WAL} — crash-safe writes; an interrupted process
 *       never leaves a torn database, only an orphaned WAL that is replayed on
 *       the next open;</li>
 *   <li>{@code busy_timeout=5000} — concurrent readers (UI thread, watcher
 *       thread) wait briefly instead of failing immediately with SQLITE_BUSY;</li>
 *   <li>{@code foreign_keys=ON} — declared FOREIGN KEY constraints (e.g.
 *       conversation → messages) are actually enforced.</li>
 * </ul>
 * <p>
 * <b>Single definition of the database location:</b>
 * {@link #getDatabasePath()} is the one source of truth; other classes must
 * resolve the database file through it (see {@link AetherPaths} which
 * delegates here).
 * </p>
 * <p>
 * This class deliberately does NOT run schema migrations — see
 * {@link SchemaMigrations}, invoked explicitly at startup, to avoid surprises
 * (and recursion) on every connection.
 * </p>
 *
 * @author Paulo Moreira
 * @version 2.0
 */
public final class Database {

    /** SQLite database file name. */
    private static final String DATABASE_NAME = "AETHER.db";

    /** Database directory name. */
    private static final String DATA_DIRECTORY = "AETHER";

    /** JDBC URL prefix. */
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    /** Milliseconds a connection waits for a busy database before failing. */
    private static final int BUSY_TIMEOUT_MS = 5000;

    /** Tentativas de abertura em caso de erro transiente (lock/IO momentâneo). */
    private static final int OPEN_ATTEMPTS = 3;

    /** Pausa entre tentativas de abertura. */
    private static final long OPEN_RETRY_PAUSE_MS = 150;

    /** Prevents instantiation. */
    private Database() {
    }

    /**
     * Opens a hardened connection to the AETHER database.
     *
     * @return an open SQLite connection (busy timeout, foreign keys on; WAL
     *         enabled when possible — see note below)
     */
    public static Connection getConnection() {
        SQLException last = null;
        for (int attempt = 1; attempt <= OPEN_ATTEMPTS; attempt++) {
            try {
                return openConnection();
            } catch (SQLException e) {
                if (!isTransient(e) || attempt == OPEN_ATTEMPTS) {
                    throw new RuntimeException("Could not open AETHER database.", e);
                }
                last = e;
                try {
                    Thread.sleep(OPEN_RETRY_PAUSE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Could not open AETHER database.", e);
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not open AETHER database.", e);
            }
        }
        throw new RuntimeException("Could not open AETHER database.", last);
    }

    /**
     * Erros transientes de abertura: SQLITE_BUSY (lock momentâneo) e
     * SQLITE_IOERR_* (ex.: disputa de ficheiros -wal/-shm entre ligações
     * concorrentes, antivírus no Windows). Vale a pena tentar de novo.
     */
    private static boolean isTransient(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("BUSY")
                || message.contains("LOCKED")
                || message.contains("IOERR");
    }

    private static Connection openConnection() throws SQLException, IOException {
        Path databasePath = getDatabasePath();

        Files.createDirectories(databasePath.getParent());

        // busy_timeout e foreign_keys são por ligação e seguros de aplicar
        // sempre. O journal_mode NÃO: mudar de/para WAL exige um lock
        // exclusivo e falha com SQLITE_BUSY se outra ligação estiver
        // aberta — e o WAL é persistente no ficheiro da base de dados,
        // bastando ser ativado uma vez. Por isso é aplicado como
        // best-effort abaixo, não como config obrigatória.
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        config.enforceForeignKeys(true);
        Properties props = config.toProperties();

        Connection connection = DriverManager.getConnection(
                JDBC_PREFIX + databasePath, props);

        try (Statement stmt = connection.createStatement()) {
            // WAL: best-effort. Na primeira abertura de uma base nova
            // resulta; se já estiver em WAL (ou estiver ocupada), é
            // inofensivo — o modo anterior mantém-se.
            try {
                stmt.execute("PRAGMA journal_mode=WAL");
            } catch (SQLException walBusy) {
                // Outra ligação aberta: o modo já está aplicado ou será
                // aplicado pela primeira ligação que conseguir lock.
            }
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    /**
     * Returns the path where the database is stored.
     * <p>
     * This is the single authoritative definition of the database location.
     * The location is deliberately unchanged from previous releases:
     * {@code %APPDATA%\AETHER\AETHER.db} on Windows and
     * {@code ~/AETHER/AETHER.db} elsewhere, unless overridden by the
     * {@code aether.data.dir} system property.
     * </p>
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
     * Checkpoints the write-ahead log into the main database file so that a
     * plain file copy of {@code AETHER.db} contains every committed
     * transaction. Used by {@link util.BackupService} before backing up.
     */
    public static void checkpointWal() {
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            // Non-fatal: backup will still include the -wal file when present.
        }
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
