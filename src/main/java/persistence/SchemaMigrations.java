package persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SchemaMigrations — versioned, ordered, backup-guarded schema upgrades.
 * <p>
 * AETHER must survive for years without a developer. This class is the single
 * authority for schema evolution:
 * </p>
 * <ul>
 *   <li>the current schema version lives in the {@code schema_info} table
 *       (one row, one column) — never guessed from table shapes;</li>
 *   <li>each migration is an ordered, idempotent-safe step applied inside a
 *       transaction; the version row is updated in the same transaction, so an
 *       interrupted migration either fully applies or does not apply;</li>
 *   <li>before any migration beyond the baseline runs, the database file is
 *       copied to {@code backups/pre-migration-&lt;version&gt;-&lt;timestamp&gt;.db}
 *       (after a WAL checkpoint) so a failed migration is always
 *       recoverable by restoring that file;</li>
 *   <li>unknown higher versions (a database written by a NEWER build opened by
 *       an older one) are refused with a clear error instead of silently
 *       corrupting data.</li>
 * </ul>
 * <p>
 * The repositories (settings, profile, memory, conversations, entities) still
 * create their own tables with {@code CREATE TABLE IF NOT EXISTS} as a safety
 * net for fresh databases; this class owns the <em>version bookkeeping</em>
 * and the <em>upgrade path</em> for existing installations. There is exactly
 * one migration system in the codebase.
 * </p>
 *
 * @author AETHER
 */
public final class SchemaMigrations {

    private static final Logger LOGGER = Logger.getLogger(SchemaMigrations.class.getName());

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * Baseline: the schema as produced by the repositories' own idempotent
     * table creation (app_settings, user_profile, user_memory). Existing
     * installations are stamped with this version on first run.
     */
    public static final int BASELINE_VERSION = 1;

    /** v2: persistent AI conversations and messages. */
    static final int V2_CONVERSATIONS = 2;

    /** v3: authoritative entity store + projection bookkeeping. */
    static final int V3_ENTITY_STORE = 3;

    /** v4: per-entity custom frontmatter (user-defined keys preserved). */
    static final int V4_CUSTOM_FRONTMATTER = 4;

    /** Latest schema version shipped by this build. */
    public static final int LATEST_VERSION = V4_CUSTOM_FRONTMATTER;

    private SchemaMigrations() {
        // Utility class.
    }

    /** One migration step: version it upgrades to + the SQL it runs. */
    private record Migration(int version, String... statements) { }

    private static List<Migration> migrations() {
        List<Migration> list = new ArrayList<>();
        list.add(new Migration(V2_CONVERSATIONS, """
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """, """
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL
                        REFERENCES conversations(id) ON DELETE CASCADE,
                    seq INTEGER NOT NULL,
                    role TEXT NOT NULL CHECK (role IN ('user','assistant')),
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE (conversation_id, seq)
                )
                """, """
                CREATE INDEX IF NOT EXISTS idx_messages_conversation
                    ON messages (conversation_id, seq)
                """));
        list.add(new Migration(V3_ENTITY_STORE, """
                CREATE TABLE IF NOT EXISTS person (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    birth_date TEXT,
                    occupation TEXT NOT NULL DEFAULT '',
                    about TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT ''
                )
                """, """
                CREATE TABLE IF NOT EXISTS event (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    start_time TEXT,
                    end_time TEXT,
                    location TEXT NOT NULL DEFAULT '',
                    description TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT ''
                )
                """, """
                CREATE TABLE IF NOT EXISTS task (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    deadline TEXT,
                    status TEXT NOT NULL DEFAULT 'TODO',
                    priority TEXT NOT NULL DEFAULT 'NONE',
                    description TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT ''
                )
                """, """
                CREATE TABLE IF NOT EXISTS note (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    original_text TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT ''
                )
                """, """
                CREATE TABLE IF NOT EXISTS project (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    deadline TEXT,
                    status TEXT NOT NULL DEFAULT 'PLANNED',
                    description TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT ''
                )
                """, """
                CREATE TABLE IF NOT EXISTS entity_projection (
                    entity_id TEXT PRIMARY KEY,
                    file_hash TEXT NOT NULL
                )
                """));
        list.add(new Migration(V4_CUSTOM_FRONTMATTER,
                """
                ALTER TABLE person ADD COLUMN custom_frontmatter TEXT
                """,
                """
                ALTER TABLE event ADD COLUMN custom_frontmatter TEXT
                """,
                """
                ALTER TABLE task ADD COLUMN custom_frontmatter TEXT
                """,
                """
                ALTER TABLE note ADD COLUMN custom_frontmatter TEXT
                """,
                """
                ALTER TABLE project ADD COLUMN custom_frontmatter TEXT
                """));
        return list;
    }

    /**
     * Brings the database up to {@link #LATEST_VERSION}. Safe to call on every
     * startup. Never throws for a healthy database; on failure the database
     * stays at its previous version and the error is returned.
     *
     * @return {@code null} on success, or a human-readable error description
     */
    public static synchronized String migrateIfNeeded() {
        int current;
        try {
            ensureSchemaInfoTable();
            current = readVersion();
        } catch (SQLException e) {
            return "Could not read schema version: " + e.getMessage();
        }

        if (current > LATEST_VERSION) {
            // Database written by a newer build — refuse rather than corrupt.
            return "Database schema version " + current
                    + " is newer than this build supports (" + LATEST_VERSION
                    + "). Upgrade AETHER before using this data.";
        }
        if (current == LATEST_VERSION) {
            return null;
        }

        // Backup before any destructive-capable step.
        try {
            backupBeforeMigration(current);
        } catch (IOException e) {
            return "Pre-migration backup failed — migration aborted: " + e.getMessage();
        }

        for (Migration m : migrations()) {
            if (m.version() <= current) {
                continue;
            }
            try (Connection connection = Database.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement stmt = connection.createStatement()) {
                    for (String sql : m.statements()) {
                        try {
                            stmt.execute(sql);
                        } catch (SQLException e) {
                            // ALTER TABLE ... ADD COLUMN é tolerante a
                            // idempotência: se a coluna já existir (ex.: os
                            // repositórios auto-criaram o schema mais recente
                            // numa base nova antes de a migração carimbar a
                            // versão), o estado final é exatamente o que esta
                            // migração produz. Qualquer outro erro aborta.
                            if (sql.strip().startsWith("ALTER TABLE")
                                    && e.getMessage() != null
                                    && e.getMessage().contains("duplicate column")) {
                                continue;
                            }
                            throw e;
                        }
                    }
                    try (PreparedStatement set = connection.prepareStatement(
                            "UPDATE schema_info SET version = ?")) {
                        set.setInt(1, m.version());
                        set.executeUpdate();
                    }
                    connection.commit();
                    LOGGER.info("Applied schema migration to version " + m.version() + ".");
                } catch (SQLException e) {
                    connection.rollback();
                    return "Migration to version " + m.version() + " failed and was rolled back: "
                            + e.getMessage();
                }
            } catch (SQLException e) {
                return "Migration to version " + m.version() + " failed: " + e.getMessage();
            }
        }
        return null;
    }

    /** Reads the current schema version. */
    public static int currentVersion() throws SQLException {
        ensureSchemaInfoTable();
        return readVersion();
    }

    private static void ensureSchemaInfoTable() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS schema_info (
                    version INTEGER NOT NULL
                )
                """;
        try (Connection connection = Database.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_info")) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    try (PreparedStatement seed = connection.prepareStatement(
                            "INSERT INTO schema_info (version) VALUES (?)")) {
                        seed.setInt(1, BASELINE_VERSION);
                        seed.executeUpdate();
                    }
                }
            }
        }
    }

    private static int readVersion() throws SQLException {
        try (Connection connection = Database.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM schema_info")) {
            if (rs.next()) {
                return rs.getInt("version");
            }
        }
        return BASELINE_VERSION;
    }

    /**
     * Copies the (checkpointed) database file next to the normal backups,
     * named after the version being upgraded from.
     */
    private static void backupBeforeMigration(int fromVersion) throws IOException {
        Database.checkpointWal();
        Path db = Database.getDatabasePath();
        if (!Files.isRegularFile(db)) {
            return; // Nothing to back up (fresh install).
        }
        Path backups = AetherPaths.dataDirectory().resolve("backups");
        Files.createDirectories(backups);
        String stamp = LocalDateTime.now().format(TS);
        Path target = backups.resolve(
                "pre-migration-v" + fromVersion + "-" + stamp + ".db");
        Files.copy(db, target, StandardCopyOption.REPLACE_EXISTING);
        // The WAL side file (if non-empty) is preserved too.
        Path wal = db.resolveSibling(db.getFileName().toString() + "-wal");
        if (Files.isRegularFile(wal) && Files.size(wal) > 0) {
            Files.copy(wal, target.resolveSibling(target.getFileName() + "-wal"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        LOGGER.info("Pre-migration backup written to " + target);
    }

    /** Logs (never throws) the migration result; used at startup. */
    public static void runAtStartup() {
        String error = migrateIfNeeded();
        if (error != null) {
            LOGGER.log(Level.SEVERE, "Schema migration problem: " + error);
        }
    }
}
