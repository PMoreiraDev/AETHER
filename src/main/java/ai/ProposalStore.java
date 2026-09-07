package ai;

import persistence.AetherPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * ProposalStore — persistência e idempotência das propostas de IA.
 * <p>
 * Garante que:
 * <ul>
 *   <li><b>Idempotência</b>: analisar a mesma nota duas vezes não cria
 *       propostas pendentes duplicadas para a mesma ação lógica. Ações já
 *       decididas (aceites/rejeitadas/falhadas) não voltam a ser propostas.</li>
 *   <li><b>Persistência/restart</b>: as propostas pendentes sobrevivem a um
 *       reinício da aplicação (são recarregadas do disco para a memória).</li>
 *   <li><b>Atomicidade</b>: cada mutação é escrita com write-temp + atomic
 *       move, para nunca corromper o ficheiro a meio.</li>
 * </ul>
 * </p>
 * <p>
 * O armazenamento é um único ficheiro JSON no diretório de dados da aplicação.
 * A IA <b>nunca</b> escreve no vault — este ficheiro só guarda o estado das
 * <i>propostas</i> (pendente/aceite/rejeitada/falhada), não entidades
 * confirmadas. As entidades só existem no vault depois de aceites e executadas
 * pelo {@link ActionExecutor}.
 * </p>
 *
 * @author AETHER
 */
public final class ProposalStore {

    private static final Logger LOGGER = Logger.getLogger(ProposalStore.class.getName());
    private static final String FILE_NAME = "ai-proposals.json";

    private final Path file;
    private final Map<String, Snapshot> byId = new LinkedHashMap<>();

    /** Snapshot persistível de uma proposta. */
    public static final class Snapshot {
        public final String id;
        public final String status;       // PENDING | ACCEPTED | REJECTED | FAILED
        public final String actionType;  // AiActionType.name()
        public final String entityType; // ContextEntityType.name()
        public final String identifier;  // nome/título alvo
        public final Map<String, String> fields;
        public final List<String> relationships;
        public final String reason;
        public final String trustLevel;  // TrustLevel.name()
        /** Classificação semântica (FACT/INFERENCE/SUGGESTION) — persiste entre restarts. */
        public final String semanticClassification;
        public final double confidence;
        public final String sourceContext;
        public final long timestamp;

        public Snapshot(String id, String status, String actionType, String entityType,
                 String identifier, Map<String, String> fields, List<String> relationships,
                 String reason, String trustLevel, double confidence,
                 String sourceContext, long timestamp) {
            this(id, status, actionType, entityType, identifier, fields, relationships,
                    reason, trustLevel, confidence, sourceContext, timestamp, "SUGGESTION");
        }

        public Snapshot(String id, String status, String actionType, String entityType,
                 String identifier, Map<String, String> fields, List<String> relationships,
                 String reason, String trustLevel, double confidence,
                 String sourceContext, long timestamp, String semanticClassification) {
            this.id = id;
            this.status = status;
            this.actionType = actionType;
            this.entityType = entityType;
            this.identifier = identifier;
            this.fields = fields;
            this.relationships = relationships;
            this.reason = reason;
            this.trustLevel = trustLevel;
            this.confidence = confidence;
            this.sourceContext = sourceContext;
            this.timestamp = timestamp;
            this.semanticClassification = semanticClassification == null || semanticClassification.isBlank()
                    ? "SUGGESTION" : semanticClassification;
        }
    }

    /** Desfecho de uma tentativa de propor. */
    public enum Decision {
        /** Nova proposta pendente adicionada. */
        NEW_PENDING,
        /** Já existe uma proposta pendente idêntica — não se duplica. */
        ALREADY_PENDING,
        /** Já foi aceite anteriormente — não se volta a propor. */
        ALREADY_ACCEPTED,
        /** Já foi rejeitada anteriormente — não se volta a propor. */
        ALREADY_REJECTED,
        /** Já falhou a execução anteriormente — pode tentar-se de novo. */
        ALREADY_FAILED
    }

    private static volatile ProposalStore instance;

    /** Instância partilhada (ficheiro no diretório de dados). */
    public static ProposalStore getInstance() {
        if (instance == null) {
            synchronized (ProposalStore.class) {
                if (instance == null) {
                    instance = new ProposalStore(AetherPaths.dataDirectory().resolve(FILE_NAME));
                }
            }
        }
        return instance;
    }

    /** Construtor de teste (caminho explícito). */
    public ProposalStore(Path file) {
        this.file = file;
        load();
    }

    /** Recarrega do disco (para testes ou refresh manual). */
    public void load() {
        byId.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file);
            parseJson(json).forEach(s -> byId.put(s.id, s));
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Não foi possível carregar propostas de " + file + ": " + e.getMessage());
        }
    }

    /** Id estável para uma proposta lógica (mesmo conteúdo → mesmo id). */
    public static String idFor(String actionType, String entityType, String identifier,
                               Map<String, String> fields) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (fields != null) sorted.putAll(fields);
        StringBuilder sb = new StringBuilder();
        sb.append(actionType).append('|').append(entityType).append('|')
          .append(identifier == null ? "" : identifier).append('|');
        sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        // Use a cryptographic digest instead of String.hashCode(): proposal IDs
        // are persisted across restarts and must not suffer the frequent
        // collisions of a 32-bit hash.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Regista uma proposta. Idempotente: se já existe com o mesmo id, devolve
     * o estado existente em vez de duplicar.
     * <p>
     * Exceção: uma proposta que <b>falhou</b> a execução não é uma decisão do
     * utilizador — é um erro do sistema. Re-analisar a mesma nota volta a
     * propô-la como pendente, para o utilizador poder tentar de novo.
     * Aceites/rejeitadas, pelo contrário, são decisões do utilizador e não são
     * re-propostas.
     * </p>
     */
    public synchronized Decision propose(Snapshot snapshot) {
        Snapshot existing = byId.get(snapshot.id);
        if (existing != null) {
            switch (existing.status) {
                case "PENDING" -> { return Decision.ALREADY_PENDING; }
                case "ACCEPTED" -> { return Decision.ALREADY_ACCEPTED; }
                case "REJECTED" -> { return Decision.ALREADY_REJECTED; }
                case "FAILED" -> {
                    // Re-propõe como pendente — o utilizador não decidiu, o sistema falhou.
                    byId.put(snapshot.id, asPending(snapshot));
                    save();
                    return Decision.NEW_PENDING;
                }
                default -> {}
            }
        }
        byId.put(snapshot.id, snapshot);
        save();
        return Decision.NEW_PENDING;
    }

    private static Snapshot asPending(Snapshot s) {
        return new Snapshot(s.id, "PENDING", s.actionType, s.entityType, s.identifier,
                s.fields, s.relationships, s.reason, s.trustLevel, s.confidence,
                s.sourceContext, System.currentTimeMillis(), s.semanticClassification);
    }

    public synchronized void markAccepted(String id) {
        updateStatus(id, "ACCEPTED");
    }

    public synchronized void markRejected(String id) {
        updateStatus(id, "REJECTED");
    }

    public synchronized void markFailed(String id) {
        updateStatus(id, "FAILED");
    }

    private void updateStatus(String id, String status) {
        Snapshot s = byId.get(id);
        if (s == null) return;
        byId.put(id, new Snapshot(s.id, status, s.actionType, s.entityType, s.identifier,
                s.fields, s.relationships, s.reason, s.trustLevel, s.confidence,
                s.sourceContext, System.currentTimeMillis(), s.semanticClassification));
        save();
    }

    public synchronized List<Snapshot> pending() {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : byId.values()) if ("PENDING".equals(s.status)) out.add(s);
        return out;
    }

    public synchronized List<Snapshot> accepted() {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : byId.values()) if ("ACCEPTED".equals(s.status)) out.add(s);
        return out;
    }

    public synchronized List<Snapshot> rejected() {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : byId.values()) if ("REJECTED".equals(s.status)) out.add(s);
        return out;
    }

    public synchronized int pendingCount() {
        int n = 0;
        for (Snapshot s : byId.values()) if ("PENDING".equals(s.status)) n++;
        return n;
    }

    public synchronized Optional<Snapshot> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Limpa tudo (usado em testes). */
    public synchronized void clear() {
        byId.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    // ------------------------------------------------------------------
    // Serialização JSON (hand-rolled, sem dependências externas)
    // ------------------------------------------------------------------

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warning("Não foi possível guardar propostas em " + file + ": " + e.getMessage());
        }
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"proposals\":[");
        boolean first = true;
        for (Snapshot s : byId.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            appendKV(sb, "id", s.id); sb.append(',');
            appendKV(sb, "status", s.status); sb.append(',');
            appendKV(sb, "actionType", s.actionType); sb.append(',');
            appendKV(sb, "entityType", s.entityType); sb.append(',');
            appendKV(sb, "identifier", s.identifier == null ? "" : s.identifier); sb.append(',');
            sb.append("\"fields\":{");
            boolean fFirst = true;
            if (s.fields != null) {
                for (var e : s.fields.entrySet()) {
                    if (!fFirst) sb.append(',');
                    fFirst = false;
                    appendKV(sb, e.getKey(), e.getValue());
                }
            }
            sb.append("},");
            sb.append("\"relationships\":[");
            if (s.relationships != null) {
                boolean rFirst = true;
                for (String r : s.relationships) {
                    if (!rFirst) sb.append(',');
                    rFirst = false;
                    appendStr(sb, r);
                }
            }
            sb.append("],");
            appendKV(sb, "reason", s.reason == null ? "" : s.reason); sb.append(',');
            appendKV(sb, "trustLevel", s.trustLevel == null ? "" : s.trustLevel); sb.append(',');
            appendKV(sb, "semanticClassification", s.semanticClassification == null ? "SUGGESTION" : s.semanticClassification); sb.append(',');
            sb.append("\"confidence\":").append(s.confidence).append(',');
            appendKV(sb, "sourceContext", s.sourceContext == null ? "" : s.sourceContext); sb.append(',');
            sb.append("\"timestamp\":").append(s.timestamp);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendKV(StringBuilder sb, String k, String v) {
        appendStr(sb, k);
        sb.append(':');
        appendStr(sb, v == null ? "" : v);
    }

    private static void appendStr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Parse defensivo do ficheiro (usa JsonActionParser para os valores). */
    private static List<Snapshot> parseJson(String json) {
        List<Snapshot> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        int arrStart = json.indexOf('[');
        int arrEnd = json.lastIndexOf(']');
        if (arrStart < 0 || arrEnd <= arrStart) return out;
        String body = json.substring(arrStart + 1, arrEnd);
        // divide em objetos de topo { ... }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        int objStart = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = body.substring(objStart, i + 1);
                    Snapshot s = parseSnapshot(obj);
                    if (s != null) out.add(s);
                    objStart = -1;
                }
            }
        }
        return out;
    }

    private static Snapshot parseSnapshot(String obj) {
        String id = JsonActionParser.extractStringValue(obj, "id");
        String status = JsonActionParser.extractStringValue(obj, "status");
        String actionType = JsonActionParser.extractStringValue(obj, "actionType");
        String entityType = JsonActionParser.extractStringValue(obj, "entityType");
        String identifier = JsonActionParser.extractStringValue(obj, "identifier");
        String reason = JsonActionParser.extractStringValue(obj, "reason");
        String trustLevel = JsonActionParser.extractStringValue(obj, "trustLevel");
        String semanticClassification = JsonActionParser.extractStringValue(obj, "semanticClassification");
        String sourceContext = JsonActionParser.extractStringValue(obj, "sourceContext");
        double confidence = JsonActionParser.extractNumberValue(obj, "confidence");
        long timestamp = (long) JsonActionParser.extractNumberValue(obj, "timestamp");
        Map<String, String> fields = JsonActionParser.extractStringMap(obj, "fields");
        List<String> relationships = JsonActionParser.extractStringArray(obj, "relationships");
        if (id == null || status == null) return null;
        return new Snapshot(id, status,
                actionType == null ? "" : actionType,
                entityType == null ? "" : entityType,
                identifier == null ? "" : identifier,
                fields, relationships,
                reason == null ? "" : reason,
                trustLevel == null ? "" : trustLevel,
                confidence,
                sourceContext == null ? "" : sourceContext,
                timestamp,
                semanticClassification == null ? "SUGGESTION" : semanticClassification);
    }
}
