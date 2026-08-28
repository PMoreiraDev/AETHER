package domain.entities;

/**
 * Tipos de entidades que podem participar no Context Graph do AETHER.
 * <p>
 * Usado por {@link ContextLink} para identificar, de forma livre de ciclos,
 * o tipo da origem e do destino de uma relação. Manter os tipos num enum
 * centralizado evita referências circulares entre as classes de domínio.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public enum ContextEntityType {

    /** O perfil do utilizador dono do AETHER. */
    PROFILE,

    /** Uma pessoa do círculo pessoal ou profissional do utilizador. */
    PERSON,

    /** Uma nota de texto livre. */
    NOTE,

    /** Um evento de calendário. */
    EVENT,

    /** Uma tarefa. */
    TASK,

    /** Um projeto. */
    PROJECT
}
