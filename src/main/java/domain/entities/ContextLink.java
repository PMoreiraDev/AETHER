package domain.entities;

import java.util.Objects;

/**
 * Representa uma relação direcionada entre duas entidades do Context Graph.
 * <p>
 * Em vez de cada entidade manter listas de identificadores das entidades
 * relacionadas, o AETHER armazena as relações como ligações explícitas e
 * genéricas. Isto mantém as entidades simples e permite que o grafo evolua
 * sem alterar as suas classes.
 * </p>
 * <p>
 * Exemplo: uma tarefa que é preparação para uma reunião é representada por
 * uma ligação {@code TASK -> EVENT} com a relação {@code "preparation for"}.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class ContextLink {

    /** Tipo da entidade de origem da relação. */
    private ContextEntityType sourceType;

    /** Identificador da entidade de origem. */
    private String sourceId;

    /** Tipo da entidade de destino da relação. */
    private ContextEntityType targetType;

    /** Identificador da entidade de destino. */
    private String targetId;

    /** Nome da relação, por exemplo "owns", "assigned", "references". */
    private String relation;

    /**
     * Cria uma ligação vazia, necessária para (des)serialização e frameworks.
     */
    public ContextLink() {
    }

    /**
     * Cria uma ligação entre duas entidades.
     *
     * @param sourceType o tipo da origem
     * @param sourceId   o identificador da origem
     * @param targetType o tipo do destino
     * @param targetId   o identificador do destino
     * @param relation   o nome da relação
     */
    public ContextLink(ContextEntityType sourceType, String sourceId,
                       ContextEntityType targetType, String targetId,
                       String relation) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.relation = relation;
    }

    /**
     * Devolve o tipo da entidade de origem.
     *
     * @return o tipo da origem
     */
    public ContextEntityType getSourceType() {
        return sourceType;
    }

    /**
     * Define o tipo da entidade de origem.
     *
     * @param sourceType o tipo da origem
     */
    public void setSourceType(ContextEntityType sourceType) {
        this.sourceType = sourceType;
    }

    /**
     * Devolve o identificador da entidade de origem.
     *
     * @return o identificador da origem
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * Define o identificador da entidade de origem.
     *
     * @param sourceId o identificador da origem
     */
    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    /**
     * Devolve o tipo da entidade de destino.
     *
     * @return o tipo do destino
     */
    public ContextEntityType getTargetType() {
        return targetType;
    }

    /**
     * Define o tipo da entidade de destino.
     *
     * @param targetType o tipo do destino
     */
    public void setTargetType(ContextEntityType targetType) {
        this.targetType = targetType;
    }

    /**
     * Devolve o identificador da entidade de destino.
     *
     * @return o identificador do destino
     */
    public String getTargetId() {
        return targetId;
    }

    /**
     * Define o identificador da entidade de destino.
     *
     * @param targetId o identificador do destino
     */
    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    /**
     * Devolve o nome da relação.
     *
     * @return a relação
     */
    public String getRelation() {
        return relation;
    }

    /**
     * Define o nome da relação.
     *
     * @param relation a relação
     */
    public void setRelation(String relation) {
        this.relation = relation;
    }

    /**
     * Compara duas ligações com base na origem, destino e relação.
     *
     * @param o o objeto a comparar
     * @return {@code true} se representarem a mesma ligação
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContextLink that = (ContextLink) o;
        return sourceType == that.sourceType
                && Objects.equals(sourceId, that.sourceId)
                && targetType == that.targetType
                && Objects.equals(targetId, that.targetId)
                && Objects.equals(relation, that.relation);
    }

    /**
     * Devolve o hash code da ligação.
     *
     * @return o hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(sourceType, sourceId, targetType, targetId, relation);
    }
}
