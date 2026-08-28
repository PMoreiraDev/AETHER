package domain.entities;

/**
 * Estados possíveis de um projeto no AETHER.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public enum ProjectStatus {

    /** O projeto está planeado mas ainda não começou. */
    PLANNED("Planned"),

    /** O projeto está em curso. */
    ACTIVE("Active"),

    /** O projeto está temporariamente parado. */
    ON_HOLD("On Hold"),

    /** O projeto está concluído. */
    COMPLETED("Completed"),

    /** O projeto foi arquivado. */
    ARCHIVED("Archived");

    /** Nome apresentado ao utilizador na interface. */
    private final String displayName;

    /**
     * Cria um estado de projeto.
     *
     * @param displayName o nome a apresentar na interface
     */
    ProjectStatus(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Devolve o nome de exibição do estado.
     *
     * @return o nome apresentado na interface
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Devolve o nome de exibição como representação textual do estado.
     *
     * @return o nome de exibição
     */
    @Override
    public String toString() {
        return displayName;
    }
}
