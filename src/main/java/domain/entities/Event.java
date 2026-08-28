package domain.entities;

import java.time.LocalDateTime;

/**
 * Um evento de calendário no AETHER.
 * <p>
 * Representa compromissos, reuniões e outros acontecimentos com data e hora
 * de início e fim. Os eventos podem ser ligados a pessoas, projetos e tarefas
 * através do Context Graph.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class Event extends AetherEntity {

    /** Título do evento; nunca {@code null}. */
    private String title = "";

    /** Data e hora de início do evento; {@code null} quando não indicada. */
    private LocalDateTime startDateTime = null;

    /** Data e hora de fim do evento; {@code null} quando não indicada. */
    private LocalDateTime endDateTime = null;

    /** Local do evento; nunca {@code null}. */
    private String location = "";

    /** Descrição do evento; nunca {@code null}. */
    private String description = "";

    /**
     * Cria um evento vazio com identificador e timestamps gerados automaticamente.
     */
    public Event() {
        super();
    }

    /**
     * Cria um evento a partir de dados persistidos.
     *
     * @param id             o identificador existente
     * @param title          o título do evento
     * @param startDateTime  a data e hora de início
     * @param endDateTime    a data e hora de fim
     * @param location       o local
     * @param description    a descrição
     * @param createdAt      o momento de criação
     * @param updatedAt      o momento da última atualização
     */
    public Event(String id, String title, LocalDateTime startDateTime, LocalDateTime endDateTime,
                 String location, String description,
                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        super(id, createdAt, updatedAt);
        this.title = title != null ? title : "";
        this.startDateTime = startDateTime;
        this.endDateTime = endDateTime;
        this.location = location != null ? location : "";
        this.description = description != null ? description : "";
    }

    /**
     * Devolve o título do evento.
     *
     * @return o título, ou string vazia se não definido
     */
    public String getTitle() {
        return title;
    }

    /**
     * Define o título do evento.
     *
     * @param title o título a armazenar
     */
    public void setTitle(String title) {
        this.title = title != null ? title : "";
    }

    /**
     * Devolve a data e hora de início do evento.
     *
     * @return o início, ou {@code null} se não definido
     */
    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }

    /**
     * Define a data e hora de início do evento.
     *
     * @param startDateTime o início, ou {@code null} para limpar
     */
    public void setStartDateTime(LocalDateTime startDateTime) {
        this.startDateTime = startDateTime;
    }

    /**
     * Devolve a data e hora de fim do evento.
     *
     * @return o fim, ou {@code null} se não definido
     */
    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }

    /**
     * Define a data e hora de fim do evento.
     *
     * @param endDateTime o fim, ou {@code null} para limpar
     */
    public void setEndDateTime(LocalDateTime endDateTime) {
        this.endDateTime = endDateTime;
    }

    /**
     * Devolve o local do evento.
     *
     * @return o local, ou string vazia se não definido
     */
    public String getLocation() {
        return location;
    }

    /**
     * Define o local do evento.
     *
     * @param location o local a armazenar
     */
    public void setLocation(String location) {
        this.location = location != null ? location : "";
    }

    /**
     * Devolve a descrição do evento.
     *
     * @return a descrição, ou string vazia se não definida
     */
    public String getDescription() {
        return description;
    }

    /**
     * Define a descrição do evento.
     *
     * @param description a descrição a armazenar
     */
    public void setDescription(String description) {
        this.description = description != null ? description : "";
    }

    /**
     * Devolve uma representação textual do evento.
     *
     * @return os campos do evento formatados
     */
    @Override
    public String toString() {
        return "Event{" +
                "id='" + getId() + '\'' +
                ", title='" + title + '\'' +
                ", startDateTime=" + startDateTime +
                ", endDateTime=" + endDateTime +
                ", location='" + location + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
