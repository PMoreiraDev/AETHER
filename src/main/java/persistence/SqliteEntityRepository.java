package persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import domain.entities.TaskPriority;
import domain.entities.TaskStatus;
import domain.entities.ProjectStatus;
import repository.PersistenceException;

/**
 * SqliteEntityRepository — the AUTHORITATIVE store for AETHER entities
 * (Person, Event, Task, Note, Project).
 * <p>
 * Historically entities lived only as Markdown files in the Obsidian vault.
 * That made the vault a single point of failure: losing the vault folder meant
 * losing every entity. Since schema v3, SQLite is the source of truth and the
 * vault is a projection (see {@link EntitySynchronizer} and
 * {@link VaultManager}). Every entity keeps its stable id, so renames never
 * break relationships or history.
 * </p>
 * <p>
 * All access is parameterized; there is no dynamic SQL anywhere. Tables are
 * created by {@link SchemaMigrations} (v3) and self-ensured here so the class
 * works standalone in tests.
 * </p>
 *
 * @author AETHER
 */
public class SqliteEntityRepository {

    private static final Logger LOGGER =
            Logger.getLogger(SqliteEntityRepository.class.getName());

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public SqliteEntityRepository() {
        ensureTables();
    }

    /** DB path the tables were ensured for (re-ensure when the test/dev data dir changes). */
    private volatile java.nio.file.Path ensuredForDb;

    /**
     * Re-creates the tables if the active database changed (e.g. the
     * {@code aether.data.dir} system property changed between tests). Cheap
     * no-op when the database is unchanged.
     */
    private void ensureFresh() {
        java.nio.file.Path current = Database.getDatabasePath();
        if (!current.equals(ensuredForDb)) {
            synchronized (this) {
                if (!current.equals(ensuredForDb)) {
                    ensureTables();
                    ensuredForDb = current;
                }
            }
        }
    }

    private void ensureTables() {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS person (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    birth_date TEXT,
                    occupation TEXT NOT NULL DEFAULT '',
                    about TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT '',
                    custom_frontmatter TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS event (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    start_time TEXT,
                    end_time TEXT,
                    location TEXT NOT NULL DEFAULT '',
                    description TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT '',
                    custom_frontmatter TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS task (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    deadline TEXT,
                    status TEXT NOT NULL DEFAULT 'TODO',
                    priority TEXT NOT NULL DEFAULT 'NONE',
                    description TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT '',
                    custom_frontmatter TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS note (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    original_text TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT '',
                    custom_frontmatter TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS project (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    deadline TEXT,
                    status TEXT NOT NULL DEFAULT 'PLANNED',
                    description TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT '',
                    custom_frontmatter TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS entity_projection (
                    entity_id TEXT PRIMARY KEY,
                    file_hash TEXT NOT NULL
                )
                """
        };
        try (Connection connection = Database.getConnection();
             Statement stmt = connection.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not create entity tables.", e);
        }
    }

    // ------------------------------------------------------------------
    // Person
    // ------------------------------------------------------------------

    /** Inserts or replaces a person by stable id. */
    public void upsertPerson(Person p) {
        ensureFresh();
        String sql = """
                INSERT INTO person (id, name, birth_date, occupation, about, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    birth_date = excluded.birth_date,
                    occupation = excluded.occupation,
                    about = excluded.about,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql)) {
            st.setString(1, p.getId());
            st.setString(2, nz(p.getName()));
            st.setString(3, p.getBirthDate() == null ? null : p.getBirthDate().toString());
            st.setString(4, nz(p.getOccupation()));
            st.setString(5, nz(p.getAbout()));
            st.setString(6, fmt(p.getCreatedAt()));
            st.setString(7, fmt(p.getUpdatedAt()));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not save person.", e);
        }
    }

    /** All people, ordered by name. */
    public List<Person> listPeople() {
        ensureFresh();
        String sql = "SELECT * FROM person ORDER BY name COLLATE NOCASE";
        List<Person> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                LocalDate birth = rs.getString("birth_date") == null ? null
                        : LocalDate.parse(rs.getString("birth_date"));
                out.add(new Person(rs.getString("id"), rs.getString("name"), birth,
                        rs.getString("occupation"), rs.getString("about"),
                        parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warning("Could not list people: " + e.getMessage());
        }
        return out;
    }

    /** Finds a person by stable id. */
    public Person findPerson(String id) {
        ensureFresh();
        return findById("person", id, rs -> {
            LocalDate birth = rs.getString("birth_date") == null ? null
                    : LocalDate.parse(rs.getString("birth_date"));
            return new Person(rs.getString("id"), rs.getString("name"), birth,
                    rs.getString("occupation"), rs.getString("about"),
                    parse(rs.getString("created_at")), parse(rs.getString("updated_at")));
        });
    }

    // ------------------------------------------------------------------
    // Event
    // ------------------------------------------------------------------

    /** Inserts or replaces an event by stable id. */
    public void upsertEvent(Event e) {
        ensureFresh();
        String sql = """
                INSERT INTO event (id, title, start_time, end_time, location, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    start_time = excluded.start_time,
                    end_time = excluded.end_time,
                    location = excluded.location,
                    description = excluded.description,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql)) {
            st.setString(1, e.getId());
            st.setString(2, nz(e.getTitle()));
            st.setString(3, fmt(e.getStartDateTime()));
            st.setString(4, fmt(e.getEndDateTime()));
            st.setString(5, nz(e.getLocation()));
            st.setString(6, nz(e.getDescription()));
            st.setString(7, fmt(e.getCreatedAt()));
            st.setString(8, fmt(e.getUpdatedAt()));
            st.executeUpdate();
        } catch (SQLException ex) {
            throw new PersistenceException("Could not save event.", ex);
        }
    }

    /** All events, ordered by start time. */
    public List<Event> listEvents() {
        ensureFresh();
        String sql = "SELECT * FROM event ORDER BY start_time";
        List<Event> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new Event(rs.getString("id"), rs.getString("title"),
                        parse(rs.getString("start_time")), parse(rs.getString("end_time")),
                        rs.getString("location"), rs.getString("description"),
                        parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warning("Could not list events: " + e.getMessage());
        }
        return out;
    }

    /** Finds an event by stable id. */
    public Event findEvent(String id) {
        ensureFresh();
        return findById("event", id, rs -> new Event(rs.getString("id"), rs.getString("title"),
                parse(rs.getString("start_time")), parse(rs.getString("end_time")),
                rs.getString("location"), rs.getString("description"),
                parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
    }

    // ------------------------------------------------------------------
    // Task
    // ------------------------------------------------------------------

    /** Inserts or replaces a task by stable id. */
    public void upsertTask(Task t) {
        ensureFresh();
        String sql = """
                INSERT INTO task (id, title, deadline, status, priority, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    deadline = excluded.deadline,
                    status = excluded.status,
                    priority = excluded.priority,
                    description = excluded.description,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql)) {
            st.setString(1, t.getId());
            st.setString(2, nz(t.getTitle()));
            st.setString(3, fmt(t.getDeadline()));
            st.setString(4, t.getStatus().name());
            st.setString(5, t.getPriority().name());
            st.setString(6, nz(t.getDescription()));
            st.setString(7, fmt(t.getCreatedAt()));
            st.setString(8, fmt(t.getUpdatedAt()));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not save task.", e);
        }
    }

    /** All tasks, ordered by deadline. */
    public List<Task> listTasks() {
        ensureFresh();
        String sql = "SELECT * FROM task ORDER BY deadline";
        List<Task> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new Task(rs.getString("id"), rs.getString("title"),
                        parse(rs.getString("deadline")),
                        enumOf(TaskStatus.class, rs.getString("status"), TaskStatus.TODO),
                        enumOf(TaskPriority.class, rs.getString("priority"), TaskPriority.NONE),
                        rs.getString("description"),
                        parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warning("Could not list tasks: " + e.getMessage());
        }
        return out;
    }

    /** Finds a task by stable id. */
    public Task findTask(String id) {
        ensureFresh();
        return findById("task", id, rs -> new Task(rs.getString("id"), rs.getString("title"),
                parse(rs.getString("deadline")),
                enumOf(TaskStatus.class, rs.getString("status"), TaskStatus.TODO),
                enumOf(TaskPriority.class, rs.getString("priority"), TaskPriority.NONE),
                rs.getString("description"),
                parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
    }

    // ------------------------------------------------------------------
    // Note
    // ------------------------------------------------------------------

    /** Inserts or replaces a note by stable id. */
    public void upsertNote(Note n, String originalText) {
        ensureFresh();
        String sql = """
                INSERT INTO note (id, title, content, original_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    content = excluded.content,
                    original_text = excluded.original_text,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql)) {
            st.setString(1, n.getId());
            st.setString(2, nz(n.getTitle()));
            st.setString(3, nz(n.getContent()));
            st.setString(4, originalText == null ? "" : originalText);
            st.setString(5, fmt(n.getCreatedAt()));
            st.setString(6, fmt(n.getUpdatedAt()));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not save note.", e);
        }
    }

    /** All notes, most recently updated first. */
    public List<Note> listNotes() {
        ensureFresh();
        String sql = "SELECT * FROM note ORDER BY updated_at DESC, rowid DESC";
        List<Note> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new Note(rs.getString("id"), rs.getString("title"),
                        rs.getString("content"),
                        parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warning("Could not list notes: " + e.getMessage());
        }
        return out;
    }

    /** Finds a note by stable id. */
    public Note findNote(String id) {
        ensureFresh();
        return findById("note", id, rs -> new Note(rs.getString("id"), rs.getString("title"),
                rs.getString("content"),
                parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
    }

    /** The stored original text (quick-note provenance) for a note id. */
    public String noteOriginalText(String id) {
        ensureFresh();
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT original_text FROM note WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString("original_text") : "";
            }
        } catch (SQLException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    /** Inserts or replaces a project by stable id. */
    public void upsertProject(Project p) {
        ensureFresh();
        String sql = """
                INSERT INTO project (id, name, deadline, status, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    deadline = excluded.deadline,
                    status = excluded.status,
                    description = excluded.description,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql)) {
            st.setString(1, p.getId());
            st.setString(2, nz(p.getName()));
            st.setString(3, fmt(p.getDeadline()));
            st.setString(4, p.getStatus().name());
            st.setString(5, nz(p.getDescription()));
            st.setString(6, fmt(p.getCreatedAt()));
            st.setString(7, fmt(p.getUpdatedAt()));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not save project.", e);
        }
    }

    /** All projects, ordered by name. */
    public List<Project> listProjects() {
        ensureFresh();
        String sql = "SELECT * FROM project ORDER BY name COLLATE NOCASE";
        List<Project> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new Project(rs.getString("id"), rs.getString("name"),
                        parse(rs.getString("deadline")),
                        enumOf(ProjectStatus.class, rs.getString("status"), ProjectStatus.PLANNED),
                        rs.getString("description"),
                        parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warning("Could not list projects: " + e.getMessage());
        }
        return out;
    }

    /** Finds a project by stable id. */
    public Project findProject(String id) {
        ensureFresh();
        return findById("project", id, rs -> new Project(rs.getString("id"), rs.getString("name"),
                parse(rs.getString("deadline")),
                enumOf(ProjectStatus.class, rs.getString("status"), ProjectStatus.PLANNED),
                rs.getString("description"),
                parse(rs.getString("created_at")), parse(rs.getString("updated_at"))));
    }

    // ------------------------------------------------------------------
    // Delete + projection hashes
    // ------------------------------------------------------------------

    /**
     * Deletes an entity of the given logical type by stable id.
     *
     * @param type one of person/event/task/note/project
     * @return {@code true} if a row was removed
     */
    public boolean delete(String type, String id) {
        ensureFresh();
        String table = tableFor(type);
        if (table == null || id == null || id.isBlank()) {
            return false;
        }
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE id = ?")) {
            st.setString(1, id);
            boolean deleted = st.executeUpdate() > 0;
            if (deleted) {
                try (PreparedStatement hash = connection.prepareStatement(
                        "DELETE FROM entity_projection WHERE entity_id = ?")) {
                    hash.setString(1, id);
                    hash.executeUpdate();
                }
            }
            return deleted;
        } catch (SQLException e) {
            LOGGER.warning("Could not delete " + type + " " + id + ": " + e.getMessage());
            return false;
        }
    }

    /** Records the content hash of the last file projected for an entity. */
    public void setProjectionHash(String entityId, String hash) {
        ensureFresh();
        String sql = """
                INSERT INTO entity_projection (entity_id, file_hash)
                VALUES (?, ?)
                ON CONFLICT(entity_id) DO UPDATE SET file_hash = excluded.file_hash
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(sql)) {
            st.setString(1, entityId);
            st.setString(2, hash == null ? "" : hash);
            st.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warning("Could not record projection hash: " + e.getMessage());
        }
    }

    /** The recorded projection hash for an entity, or {@code null}. */
    public String projectionHash(String entityId) {
        ensureFresh();
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT file_hash FROM entity_projection WHERE entity_id = ?")) {
            st.setString(1, entityId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString("file_hash") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Custom frontmatter (user-defined keys preserved across projections)
    // ------------------------------------------------------------------

    /**
     * Persists the user-defined (non-AETHER) frontmatter keys of an entity
     * as a JSON object, so they survive re-projection, rename and vault loss.
     * Only updates existing rows — an entity must be saved first.
     *
     * @param type one of person/event/task/note/project
     * @param id   the stable entity id
     * @param json the serialized custom keys (may be {@code null} to clear)
     */
    public void setCustomFrontmatter(String type, String id, String json) {
        ensureFresh();
        String table = tableFor(type);
        if (table == null || id == null || id.isBlank()) {
            return;
        }
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(
                     "UPDATE " + table + " SET custom_frontmatter = ? WHERE id = ?")) {
            st.setString(1, json);
            st.setString(2, id);
            st.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warning("Could not store custom frontmatter for " + type
                    + " " + id + ": " + e.getMessage());
        }
    }

    /**
     * The stored custom frontmatter JSON for an entity, or {@code null}.
     */
    public String customFrontmatter(String type, String id) {
        ensureFresh();
        String table = tableFor(type);
        if (table == null || id == null || id.isBlank()) {
            return null;
        }
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT custom_frontmatter FROM " + table + " WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString("custom_frontmatter") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> T findById(String table, String id, RowMapper<T> mapper) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try (Connection connection = Database.getConnection();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT * FROM " + table + " WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warning("Could not read " + table + " " + id + ": " + e.getMessage());
            return null;
        }
    }

    private static String tableFor(String type) {
        return switch (type == null ? "" : type) {
            case "person", "PERSON" -> "person";
            case "event", "EVENT" -> "event";
            case "task", "TASK" -> "task";
            case "note", "NOTE" -> "note";
            case "project", "PROJECT" -> "project";
            default -> null;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(DT);
    }

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DT);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T extends Enum<T>> T enumOf(Class<T> cls, String value, T def) {
        if (value == null || value.isBlank()) {
            return def;
        }
        try {
            return Enum.valueOf(cls, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
