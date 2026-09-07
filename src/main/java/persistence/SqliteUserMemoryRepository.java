package persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import repository.PersistenceException;

/**
 * SqliteUserMemoryRepository — persistência da MEMÓRIA PESSOAL DO UTILIZADOR
 * com proveniência completa (spec #9) e histórico temporal (spec #23).
 * <p>
 * Cada linha regista UMA transição de memória aceite pelo utilizador:
 * o campo, o novo valor, o valor anterior, a fonte (conversa/nota/edição
 * manual), o contexto-fonte (citação literal da frase que originou a
 * memória), a classificação epistémica (FACT/INFERENCE/SUGGESTION), a
 * confiança e a data/hora.
 * </p>
 * <p>
 * Este repositório é o REGISTO DE MEMÓRIAS (o "diário" auditável); o perfil
 * estruturado continua a ser a fonte canónica do valor ATUAL
 * (SqliteUserProfileRepository). A tabela {@code user_memory} permite:
 * </p>
 * <ul>
 *   <li>Timeline histórica: "Amarante → Porto" mantém o valor antigo (spec #23).</li>
 *   <li>Auditoria: quem/quando/porquê para cada valor do perfil.</li>
 *   <li>Confirmação: {@code confirmation_status} = o estado de aprovação.</li>
 * </ul>
 * Segue o padrão de {@link SqliteUserProfileRepository} (CREATE TABLE IF NOT
 * EXISTS no construtor; {@link Database#getConnection()}).
 *
 * @author AETHER
 */
public class SqliteUserMemoryRepository {

    private static final Logger LOGGER = Logger.getLogger(SqliteUserMemoryRepository.class.getName());

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Uma memória aceite do utilizador, com proveniência completa (spec #9). */
    public record UserMemory(
            String id,
            String field,
            String value,
            String previousValue,
            String source,
            String sourceId,
            String sourceType,
            String sourceContext,
            double confidence,
            String classification,
            String confirmationStatus,
            LocalDateTime timestamp) { }

    /**
     * Cria o repositório e garante que a tabela existe (idempotente).
     */
    public SqliteUserMemoryRepository() {
        createTable();
    }

    private void createTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS user_memory (
                    id TEXT PRIMARY KEY,
                    field TEXT NOT NULL,
                    value TEXT NOT NULL,
                    previous_value TEXT,
                    source TEXT NOT NULL DEFAULT 'conversation',
                    source_id TEXT NOT NULL DEFAULT '',
                    source_type TEXT NOT NULL DEFAULT 'conversation',
                    source_context TEXT NOT NULL DEFAULT '',
                    confidence REAL NOT NULL DEFAULT 0.5,
                    classification TEXT NOT NULL DEFAULT 'FACT',
                    confirmation_status TEXT NOT NULL DEFAULT 'ACCEPTED',
                    timestamp TEXT NOT NULL
                )
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not create user_memory table.", e);
        }
        migrateAddSourceColumns();
    }

    /**
     * Migração idempotente: adiciona source/source_id a vaults de alfa
     * anteriores criados sem estas colunas (spec #9 — proveniência completa).
     */
    private void migrateAddSourceColumns() {
        String check = "PRAGMA table_info(user_memory)";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(check);
             ResultSet rs = statement.executeQuery()) {
            boolean hasSource = false;
            boolean hasSourceId = false;
            while (rs.next()) {
                if ("source".equals(rs.getString("name"))) hasSource = true;
                if ("source_id".equals(rs.getString("name"))) hasSourceId = true;
            }
            if (!hasSource) {
                try (PreparedStatement alter = connection.prepareStatement(
                        "ALTER TABLE user_memory ADD COLUMN source TEXT NOT NULL DEFAULT 'conversation'")) {
                    alter.executeUpdate();
                }
            }
            if (!hasSourceId) {
                try (PreparedStatement alter = connection.prepareStatement(
                        "ALTER TABLE user_memory ADD COLUMN source_id TEXT NOT NULL DEFAULT ''")) {
                    alter.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not migrate user_memory table.", e);
        }
    }

    /**
     * Registra uma transição de memória (chamado em cada ACCEPT que altera um
     * campo do perfil, e em cada edição manual guardada).
     *
     * @param field             campo canónico (ex.: location)
     * @param value             novo valor aceite
     * @param previousValue     valor anterior (pode ser null/vazio)
     * @param source            conversation | note | manual_edit (fonte lógica)
     * @param sourceId          id da proposta/nota/conversa de origem (spec #9)
     * @param sourceType        subtipo detalhado da fonte
     * @param sourceContext     citação/frase de origem
     * @param confidence        confiança 0..1
     * @param classification    FACT | INFERENCE | SUGGESTION
     * @return o registo criado, ou {@code null} em caso de falha (não bloqueia
     *         o fluxo de aceitação — o perfil já foi atualizado)
     */
    public UserMemory record(String field, String value, String previousValue,
                             String source, String sourceId, String sourceType,
                             String sourceContext,
                             double confidence, String classification) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String confirmation = "ACCEPTED";
        String sql = """
                INSERT INTO user_memory
                    (id, field, value, previous_value, source, source_id, source_type, source_context,
                     confidence, classification, confirmation_status, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, field == null ? "" : field);
            statement.setString(3, value == null ? "" : value);
            statement.setString(4, previousValue);
            statement.setString(5, source == null || source.isBlank() ? "conversation" : source);
            statement.setString(6, sourceId == null ? "" : sourceId);
            statement.setString(7, sourceType == null || sourceType.isBlank() ? "conversation" : sourceType);
            statement.setString(8, sourceContext == null ? "" : sourceContext);
            statement.setDouble(9, confidence);
            statement.setString(10, classification == null || classification.isBlank() ? "FACT" : classification);
            statement.setString(11, confirmation);
            statement.setString(12, now.format(ISO));
            statement.executeUpdate();
            return new UserMemory(id, field, value, previousValue, source, sourceId, sourceType,
                    sourceContext, confidence, classification, confirmation, now);
        } catch (SQLException e) {
            LOGGER.warning("Could not record user memory (field=" + field + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Histórico completo de um campo, do mais recente para o mais antigo.
     */
    public List<UserMemory> history(String field) {
        String sql = """
                SELECT id, field, value, previous_value, source, source_id, source_type, source_context,
                       confidence, classification, confirmation_status, timestamp
                FROM user_memory
                WHERE field = ?
                ORDER BY timestamp DESC, rowid DESC
                """;
        List<UserMemory> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, field);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(fromRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Could not read user memory history (field=" + field + "): " + e.getMessage());
        }
        return out;
    }

    /**
     * Última memória registada de um campo (valor atual com proveniência).
     */
    public UserMemory latest(String field) {
        List<UserMemory> h = history(field);
        return h.isEmpty() ? null : h.get(0);
    }

    /**
     * Todas as memórias registadas, da mais recente para a mais antiga
     * (usado para construir a Timeline do utilizador no Vault).
     */
    public List<UserMemory> all() {
        String sql = """
                SELECT id, field, value, previous_value, source, source_id, source_type, source_context,
                       confidence, classification, confirmation_status, timestamp
                FROM user_memory
                ORDER BY timestamp DESC, rowid DESC
                """;
        List<UserMemory> out = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(fromRow(rs));
            }
        } catch (SQLException e) {
            LOGGER.warning("Could not read user memories: " + e.getMessage());
        }
        return out;
    }

    private static UserMemory fromRow(ResultSet rs) throws SQLException {
        String ts = rs.getString("timestamp");
        LocalDateTime time;
        try {
            time = ts == null ? null : LocalDateTime.parse(ts, ISO);
        } catch (Exception e) {
            time = null;
        }
        return new UserMemory(
                rs.getString("id"),
                rs.getString("field"),
                rs.getString("value"),
                rs.getString("previous_value"),
                rs.getString("source"),
                rs.getString("source_id"),
                rs.getString("source_type"),
                rs.getString("source_context"),
                rs.getDouble("confidence"),
                rs.getString("classification"),
                rs.getString("confirmation_status"),
                time);
    }

    /** Formata uma data ISO da tabela para exibição. */
    public static String format(LocalDateTime time) {
        return time == null ? "" : time.format(ISO);
    }

    /** Converte Instant (proveniência de proposta) para LocalDateTime local. */
    public static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
