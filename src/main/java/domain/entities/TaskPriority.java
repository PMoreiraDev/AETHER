package domain.entities;

/**
 * Níveis de prioridade de uma tarefa no AETHER.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public enum TaskPriority {

    /** Sem prioridade definida. */
    NONE("None", 0),

    /** Prioridade baixa. */
    LOW("Low", 1),

    /** Prioridade média. */
    MEDIUM("Medium", 2),

    /** Prioridade alta. */
    HIGH("High", 3),

    /** Prioridade urgente. */
    URGENT("Urgent", 4);

    /** Nome apresentado ao utilizador na interface. */
    private final String displayName;

    /** Peso numérico usado para ordenação; valores maiores são mais prioritários. */
    private final int weight;

    /**
     * Cria um nível de prioridade.
     *
     * @param displayName o nome a apresentar na interface
     * @param weight      o peso para ordenação
     */
    TaskPriority(String displayName, int weight) {
        this.displayName = displayName;
        this.weight = weight;
    }

    /**
     * Devolve o nome de exibição da prioridade.
     *
     * @return o nome apresentado na interface
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Devolve o peso numérico da prioridade.
     *
     * @return o peso para ordenação
     */
    public int getWeight() {
        return weight;
    }

    /**
     * Devolve o nome de exibição como representação textual da prioridade.
     *
     * @return o nome de exibição
     */
    @Override
    public String toString() {
        return displayName;
    }
}
