package domain.entities;

import java.time.LocalDateTime;

/**
 * Um projeto no AETHER.
 * <p>
 * Agrega tarefas, deadlines, pessoas e decisões relacionadas, podendo ser
 * ligado a outras entidades através do Context Graph.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class Project extends AetherEntity {

    /** Nome do projeto; nunca {@code null}. */
    private String name = "";

    /** Deadline do projeto; {@code null} quando não indicada. */
    private LocalDateTime deadline = null;

    /** Estado atual do projeto; nunca {@code null}. */
    private ProjectStatus status = ProjectStatus.PLANNED;

    /** Descrição do projeto; nunca {@code null}. */
    private String description = "";

    /**
     * Cria um projeto vazio com identificador e timestamps gerados automaticamente.
     */
    public Project() {
        super();
    }

    /**
     * Cria um projeto a partir de dados persistidos.
     *
     * @param id          o identificador existente
     * @param name        o nome do projeto
     * @param deadline    a deadline
     * @param status      o estado
     * @param description a descrição
     * @param createdAt   o momento de criação
     * @param updatedAt   o momento da última atualização
     */
    public Project(String id, String name, LocalDateTime deadline, ProjectStatus status,
                   String description, LocalDateTime createdAt, LocalDateTime updatedAt) {
        super(id, createdAt, updatedAt);
        this.name = name != null ? name : "";
        this.deadline = deadline;
        this.status = status != null ? status : ProjectStatus.PLANNED;
        this.description = description != null ? description : "";
    }

    /**
     * Devolve o nome do projeto.
     *
     * @return o nome, ou string vazia se não definido
     */
    public String getName() {
        return name;
    }

    /**
     * Define o nome do projeto.
     *
     * @param name o nome a armazenar
     */
    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    /**
     * Devolve a deadline do projeto.
     *
     * @return a deadline, ou {@code null} se não definida
     */
    public LocalDateTime getDeadline() {
        return deadline;
    }

    /**
     * Define a deadline do projeto.
     *
     * @param deadline a deadline, ou {@code null} para limpar
     */
    public void setDeadline(LocalDateTime deadline) {
        this.deadline = deadline;
    }

    /**
     * Devolve o estado do projeto.
     *
     * @return o estado
     */
    public ProjectStatus getStatus() {
        return status;
    }

    /**
     * Define o estado do projeto.
     *
     * @param status o estado; se {@code null}, usa {@link ProjectStatus#PLANNED}
     */
    public void setStatus(ProjectStatus status) {
        this.status = status != null ? status : ProjectStatus.PLANNED;
    }

    /**
     * Devolve a descrição do projeto.
     *
     * @return a descrição, ou string vazia se não definida
     */
    public String getDescription() {
        return description;
    }

    /**
     * Define a descrição do projeto.
     *
     * @param description a descrição a armazenar
     */
    public void setDescription(String description) {
        this.description = description != null ? description : "";
    }

    /**
     * Devolve uma representação textual do projeto.
     *
     * @return os campos do projeto formatados
     */
    @Override
    public String toString() {
        return "Project{" +
                "id='" + getId() + '\'' +
                ", name='" + name + '\'' +
                ", deadline=" + deadline +
                ", status=" + status +
                ", description='" + description + '\'' +
                '}';
    }
}
