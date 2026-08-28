package domain.entities;

/**
 * Uma nota de texto livre no AETHER.
 * <p>
 * As notas mantêm-se totalmente livres: o utilizador nunca é obrigado a
 * estruturá-las antes de o AETHER as poder utilizar. O conteúdo pode ser
 * posteriormente enriquecido com metadados e ligações a outras entidades
 * através do Context Graph.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class Note extends AetherEntity {

    /** Conteúdo textual da nota; nunca {@code null}. */
    private String content = "";

    /**
     * Cria uma nota vazia com identificador e timestamps gerados automaticamente.
     */
    public Note() {
        super();
    }

    /**
     * Cria uma nota a partir de dados persistidos.
     *
     * @param id        o identificador existente
     * @param content   o conteúdo da nota
     * @param createdAt o momento de criação
     * @param updatedAt o momento da última atualização
     */
    public Note(String id, String content, java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
        super(id, createdAt, updatedAt);
        this.content = content != null ? content : "";
    }

    /**
     * Devolve o conteúdo da nota.
     *
     * @return o conteúdo, ou string vazia se não definido
     */
    public String getContent() {
        return content;
    }

    /**
     * Define o conteúdo da nota.
     *
     * @param content o conteúdo a armazenar
     */
    public void setContent(String content) {
        this.content = content != null ? content : "";
    }

    /**
     * Devolve uma representação textual da nota.
     *
     * @return os campos da nota formatados
     */
    @Override
    public String toString() {
        return "Note{" +
                "id='" + getId() + '\'' +
                ", content='" + content + '\'' +
                ", createdAt=" + getCreatedAt() +
                ", updatedAt=" + getUpdatedAt() +
                '}';
    }
}
