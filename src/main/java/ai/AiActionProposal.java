package ai;

import domain.entities.ContextEntityType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AiActionProposal — uma alteração estruturada proposta pela IA.
 * <p>
 * A IA não escreve diretamente ficheiros Markdown. Em vez disso, produz
 * instâncias desta classe, que são validadas e apresentadas ao utilizador
 * para aprovação antes de serem executadas pelo {@link ActionExecutor} contra
 * o {@link persistence.VaultManager}.
 * </p>
 * <p>
 * Cada proposta inclui: o tipo de ação, o tipo de entidade, o id quando
 * aplicável, os campos a criar/atualizar, as relações a estabelecer, a razão
 * da sugestão, a confiança e a fonte/contexto de onde foi inferida.
 * </p>
 *
 * @author AETHER
 */
public final class AiActionProposal {

    private final AiActionType actionType;
    private final ContextEntityType entityType;
    private final String entityId;
    private final Map<String, String> fields;
    private final List<String> relationships;
    private final String reason;
    private final double confidence;
    private final String sourceContext;
    private final TrustLevel trustLevel;
    /** Classificacao semantica (FACT/INFERENCE/SUGGESTION) tal como o modelo a atribuiu. */
    private final String semanticClassification;

    private AiActionProposal(Builder b) {
        this.actionType = Objects.requireNonNull(b.actionType, "actionType");
        this.entityType = Objects.requireNonNull(b.entityType, "entityType");
        this.entityId = b.entityId == null ? "" : b.entityId;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
        this.relationships = Collections.unmodifiableList(b.relationships);
        this.reason = b.reason == null ? "" : b.reason;
        this.confidence = clamp(b.confidence);
        this.sourceContext = b.sourceContext == null ? "" : b.sourceContext;
        this.trustLevel = b.trustLevel == null ? TrustLevel.SUGGESTED : b.trustLevel;
        this.semanticClassification = b.semanticClassification == null || b.semanticClassification.isBlank()
                ? "SUGGESTION" : b.semanticClassification;
    }

    private static double clamp(double c) {
        if (c < 0) return 0;
        if (c > 1) return 1;
        return c;
    }

    public AiActionType getActionType() {
        return actionType;
    }

    public ContextEntityType getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public List<String> getRelationships() {
        return relationships;
    }

    public String getReason() {
        return reason;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getSourceContext() {
        return sourceContext;
    }

    public TrustLevel getTrustLevel() {
        return trustLevel;
    }

    /** Classificacao semantica (FACT / INFERENCE / SUGGESTION). */
    public String getSemanticClassification() {
        return semanticClassification;
    }

    /** Indica se a ação é destrutiva (exige confirmação extra). */
    public boolean isDestructive() {
        return actionType == AiActionType.DELETE_ENTITY;
    }

    /**
     * Devolve uma descrição legível da proposta, para a UI de aprovação.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(actionType).append(" ").append(entityType);
        if (!entityId.isBlank()) {
            sb.append(" (").append(entityId).append(")");
        }
        if (!fields.isEmpty()) {
            sb.append(" — ");
            boolean first = true;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
        }
        if (!relationships.isEmpty()) {
            sb.append(" — links: ").append(String.join(", ", relationships));
        }
        return sb.toString();
    }

    /**
     * Construtor fluente para propostas.
     */
    public static final class Builder {
        private AiActionType actionType;
        private ContextEntityType entityType;
        private String entityId = "";
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final List<String> relationships = new java.util.ArrayList<>();
        private String reason = "";
        private double confidence = 0.5;
        private String sourceContext = "";
        private TrustLevel trustLevel = TrustLevel.SUGGESTED;
        private String semanticClassification = "SUGGESTION";

        public Builder actionType(AiActionType t) {
            this.actionType = t;
            return this;
        }

        public Builder entityType(ContextEntityType t) {
            this.entityType = t;
            return this;
        }

        public Builder entityId(String id) {
            this.entityId = id;
            return this;
        }

        public Builder field(String key, String value) {
            if (key != null && !key.isBlank()) {
                this.fields.put(key, value == null ? "" : value);
            }
            return this;
        }

        public Builder relationships(List<String> rels) {
            if (rels != null) {
                this.relationships.addAll(rels);
            }
            return this;
        }

        public Builder reason(String r) {
            this.reason = r;
            return this;
        }

        public Builder confidence(double c) {
            this.confidence = c;
            return this;
        }

        public Builder sourceContext(String s) {
            this.sourceContext = s;
            return this;
        }

        public Builder trustLevel(TrustLevel t) {
            this.trustLevel = t;
            return this;
        }

        public Builder semanticClassification(String c) {
            this.semanticClassification = c;
            return this;
        }

        public AiActionProposal build() {
            return new AiActionProposal(this);
        }
    }
}
