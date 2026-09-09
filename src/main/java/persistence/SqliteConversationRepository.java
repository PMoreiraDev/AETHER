package persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import repository.PersistenceException;

/**
 * SqliteConversationRepository — persistent AI chat conversations.
 * <p>
 * A conversation is a sequence of user/assistant messages. Every message is
 * written to SQLite the moment it exists (the user message BEFORE the AI call,
 * the assistant reply after a successful reply), so a crash, an Ollama outage
 * or an app restart never loses what the user typed. This is the authoritative
 * store for conversation history; the in-memory {@link session.ChatSession}
 * list is only a live cache of the active conversation.
 * </p>
 * <p>
 * Tables are created by {@link SchemaMigrations} (migration v2); this class
 * also self-ensures them so it can be used standalone in tests.
 * </p>
 *
 * @author AETHER
 */
public class SqliteConversationRepository {

    private static final Logger LOGGER =
            Logger.getLogger(SqliteConversationRepository.class.getName());

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** A persisted conversation. */
    public record Conversation(String id, String title, LocalDateTime createdAt,
                               LocalDateTime updatedAt) { }

    /** A persisted message. */
    public record Message(String id, String conversationId, long seq, String role,
                          String content, LocalDateTime createdAt) { }

    public SqliteConversationRepository() {
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
        String sql = """
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """;
        String messages = """
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
                """;
        String index = """
                CREATE INDEX IF NOT EXISTS idx_messages_conversation
                    ON messages (conversation_id, seq)
                """;
        try (Connection connection = Database.getConnection();
             java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            stmt.execute(messages);
            stmt.execute(index);
        } catch (SQLException e) {
            throw new PersistenceException("Could not create conversation tables.", e);
        }
    }

    /**
     * Creates a new empty conversation.
     *
     * @return the created conversation
     */
    public Conversation createConversation() {
        ensureFresh();
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String sql = """
                INSERT INTO conversations (id, title, created_at, updated_at)
                VALUES (?, '', ?, ?)
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, now.format(TS));
            statement.setString(3, now.format(TS));
            statement.executeUpdate();
            return new Conversation(id, "", now, now);
        } catch (SQLException e) {
            throw new PersistenceException("Could not create conversation.", e);
        }
    }

    /**
     * Most recently updated conversation, or {@code null} when none exists.
     */
    public Conversation latestConversation() {
        ensureFresh();
        String sql = """
                SELECT id, title, created_at, updated_at
                FROM conversations
                ORDER BY updated_at DESC, rowid DESC
                LIMIT 1
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? conversationFromRow(rs) : null;
        } catch (SQLException e) {
            LOGGER.warning("Could not read latest conversation: " + e.getMessage());
            return null;
        }
    }

    /**
     * All conversations, most recently updated first.
     */
    public List<Conversation> listConversations() {
        ensureFresh();
        String sql = """
                SELECT id, title, created_at, updated_at
                FROM conversations
                ORDER BY updated_at DESC, rowid DESC
                """;
        List<Conversation> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(conversationFromRow(rs));
            }
        } catch (SQLException e) {
            LOGGER.warning("Could not list conversations: " + e.getMessage());
        }
        return out;
    }

    /**
     * All messages of a conversation in chronological order.
     */
    public List<Message> messages(String conversationId) {
        ensureFresh();
        String sql = """
                SELECT id, conversation_id, seq, role, content, created_at
                FROM messages
                WHERE conversation_id = ?
                ORDER BY seq ASC
                """;
        List<Message> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(messageFromRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Could not read messages: " + e.getMessage());
        }
        return out;
    }

    /**
     * Appends a message to a conversation (persisted immediately) and bumps
     * the conversation's updated_at.
     *
     * @return the persisted message, or {@code null} on failure (non-fatal —
     *         the caller keeps the message in RAM so the current session
     *         still works; the loss is logged)
     */
    public Message addMessage(String conversationId, String role, String content) {
        ensureFresh();
        if (conversationId == null || role == null || content == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        String id = UUID.randomUUID().toString();
        String insert = """
                INSERT INTO messages (id, conversation_id, seq, role, content, created_at)
                VALUES (?, ?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM messages
                               WHERE conversation_id = ?), ?, ?, ?)
                """;
        String touch = "UPDATE conversations SET updated_at = ? WHERE id = ?";
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            long seq;
            try (PreparedStatement ins = connection.prepareStatement(insert)) {
                ins.setString(1, id);
                ins.setString(2, conversationId);
                ins.setString(3, conversationId);
                ins.setString(4, role);
                ins.setString(5, content);
                ins.setString(6, now.format(TS));
                ins.executeUpdate();
                try (PreparedStatement sel = connection.prepareStatement(
                        "SELECT seq FROM messages WHERE id = ?")) {
                    sel.setString(1, id);
                    try (ResultSet rs = sel.executeQuery()) {
                        seq = rs.next() ? rs.getLong("seq") : 0;
                    }
                }
                try (PreparedStatement up = connection.prepareStatement(touch)) {
                    up.setString(1, now.format(TS));
                    up.setString(2, conversationId);
                    up.executeUpdate();
                }
                connection.commit();
                return new Message(id, conversationId, seq, role, content, now);
            } catch (SQLException e) {
                connection.rollback();
                LOGGER.warning("Could not persist message: " + e.getMessage());
                return null;
            }
        } catch (SQLException e) {
            LOGGER.warning("Could not persist message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deletes a conversation and its messages (cascade). Never deletes other
     * conversations.
     *
     * @return {@code true} if a conversation was removed
     */
    public boolean deleteConversation(String conversationId) {
        ensureFresh();
        String sql = "DELETE FROM conversations WHERE id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Could not delete conversation: " + e.getMessage());
            return false;
        }
    }

    /**
     * Full-text search over message content, newest conversations first.
     */
    public List<Message> search(String query) {
        ensureFresh();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT id, conversation_id, seq, role, content, created_at
                FROM messages
                WHERE content LIKE ?
                ORDER BY created_at DESC
                LIMIT 200
                """;
        List<Message> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "%" + query.replace("%", "\\%").replace("_", "\\_") + "%");
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(messageFromRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Could not search messages: " + e.getMessage());
        }
        return out;
    }

    private static Conversation conversationFromRow(ResultSet rs) throws SQLException {
        return new Conversation(
                rs.getString("id"),
                rs.getString("title"),
                parseTime(rs.getString("created_at")),
                parseTime(rs.getString("updated_at")));
    }

    private static Message messageFromRow(ResultSet rs) throws SQLException {
        return new Message(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getLong("seq"),
                rs.getString("role"),
                rs.getString("content"),
                parseTime(rs.getString("created_at")));
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, TS);
        } catch (Exception e) {
            return null;
        }
    }
}
