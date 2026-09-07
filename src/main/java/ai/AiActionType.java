package ai;

/**
 * Tipos de ação que a IA do AETHER pode propor.
 * <p>
 * A IA NUNCA altera dados persistentes silenciosamente. Produz apenas
 * propostas estruturadas ({@link AiActionProposal}) que têm de passar por
 * validação e aprovação explícita do utilizador antes de ser executadas.
 * </p>
 * <p>
 * {@link #DELETE_ENTITY} é deliberadamente tratado à parte: exige confirmação
 * muito explícita e nunca é executado em lote sem revisão individual.
 * </p>
 *
 * @author AETHER
 */
public enum AiActionType {

    /** Criar uma nova entidade (Person, Project, Event, Task, Note). */
    CREATE_ENTITY,

    /** Atualizar campos de uma entidade existente. */
    UPDATE_ENTITY,

    /** Ligar duas entidades (criar uma relação). */
    LINK_ENTITIES,

    /** Desligar duas entidades (remover uma relação). */
    UNLINK_ENTITIES,

    /**
     * Eliminar uma entidade. Exige confirmação muito explícita e nunca é
     * executada de forma autónoma silenciosa.
     */
    DELETE_ENTITY
}
