package domain.entities;

import java.time.LocalDateTime;

/**
 * Uma tarefa no AETHER.
 * <p>
 * As tarefas podem ser ligadas a projetos, pessoas e eventos através do
 * Context Graph. A deadline é um {@link LocalDateTime} para suportar tarefas
 * com hora específica, tal como mostrado na dashboard.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class Task extends AetherEntity {

    /** Título da tarefa; nunca {@code null}. */
    private String title = "";

    /** Deadline da tarefa; {@code null} quando não indicada. */
    private LocalDateTime deadline = null;

    /** Estado atual da tarefa; nunca {@code null}. */
    private TaskStatus status = TaskStatus.TODO;

    /** Prioridade da tarefa; nunca {@code null}. */
    private TaskPriority priority = TaskPriority.NONE;

    /** Descrição da tarefa; nunca {@code null}. */
    private String description = "";

    /**
     * Cria uma tarefa vazia com identificador e timestamps gerados automaticamente.
     */
    public Task() {
        super();
    }

    /**
     * Cria uma tarefa a partir de dados persistidos.
     *
     * @param id          o identificador existente
     * @param title       o título da tarefa
     * @param deadline    a deadline
     * @param status      o estado
     * @param priority    a prioridade
     * @param description a descrição
     * @param createdAt   o momento de criação
     * @param updatedAt   o momento da última atualização
     */
    public Task(String id, String title, LocalDateTime deadline, TaskStatus status,
                TaskPriority priority, String description,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        super(id, createdAt, updatedAt);
        this.title = title != null ? title : "";
        this.deadline = deadline;
        this.status = status != null ? status : TaskStatus.TODO;
        this.priority = priority != null ? priority : TaskPriority.NONE;
        this.description = description != null ? description : "";
    }

    /**
     * Devolve o título da tarefa.
     *
     * @return o título, ou string vazia se não definido
     */
    public String getTitle() {
        return title;
    }

    /**
     * Define o título da tarefa.
     *
     * @param title o título a armazenar
     */
    public void setTitle(String title) {
        this.title = title != null ? title : "";
    }

    /**
     * Devolve a deadline da tarefa.
     *
     * @return a deadline, ou {@code null} se não definida
     */
    public LocalDateTime getDeadline() {
        return deadline;
    }

    /**
     * Define a deadline da tarefa.
     *
     * @param deadline a deadline, ou {@code null} para limpar
     */
    public void setDeadline(LocalDateTime deadline) {
        this.deadline = deadline;
    }

    /**
     * Devolve o estado da tarefa.
     *
     * @return o estado
     */
    public TaskStatus getStatus() {
        return status;
    }

    /**
     * Define o estado da tarefa.
     *
     * @param status o estado; se {@code null}, usa {@link TaskStatus#TODO}
     */
    public void setStatus(TaskStatus status) {
        this.status = status != null ? status : TaskStatus.TODO;
    }

    /**
     * Devolve a prioridade da tarefa.
     *
     * @return a prioridade
     */
    public TaskPriority getPriority() {
        return priority;
    }

    /**
     * Define a prioridade da tarefa.
     *
     * @param priority a prioridade; se {@code null}, usa {@link TaskPriority#NONE}
     */
    public void setPriority(TaskPriority priority) {
        this.priority = priority != null ? priority : TaskPriority.NONE;
    }

    /**
     * Devolve a descrição da tarefa.
     *
     * @return a descrição, ou string vazia se não definida
     */
    public String getDescription() {
        return description;
    }

    /**
     * Define a descrição da tarefa.
     *
     * @param description a descrição a armazenar
     */
    public void setDescription(String description) {
        this.description = description != null ? description : "";
    }

    /**
     * Devolve uma representação textual da tarefa.
     *
     * @return os campos da tarefa formatados
     */
    @Override
    public String toString() {
        return "Task{" +
                "id='" + getId() + '\'' +
                ", title='" + title + '\'' +
                ", deadline=" + deadline +
                ", status=" + status +
                ", priority=" + priority +
                ", description='" + description + '\'' +
                '}';
    }
}
