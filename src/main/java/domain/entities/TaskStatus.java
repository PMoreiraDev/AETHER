package domain.entities;

/**
 * Estados possíveis de uma tarefa no AETHER.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public enum TaskStatus {

    /** A tarefa ainda não foi iniciada. */
    TODO("To Do"),

    /** A tarefa está a ser executada. */
    IN_PROGRESS("In Progress"),

    /** A tarefa foi concluída. */
    DONE("Done"),

    /** A tarefa foi bloqueada por uma dependência. */
    BLOCKED("Blocked"),

    /** A tarefa foi cancelada. */
    CANCELLED("Cancelled");

    /** Nome apresentado ao utilizador na interface. */
    private final String displayName;

    /**
     * Cria um estado de tarefa.
     *
     * @param displayName o nome a apresentar na interface
     */
    TaskStatus(String displayName) {
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
