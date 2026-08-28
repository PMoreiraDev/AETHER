package domain.entities;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Classe base de todas as entidades do domínio AETHER.
 * <p>
 * Centraliza a identificação e a auditoria temporal (criação e atualização)
 * comuns a todas as entidades que fazem parte do Context Graph. O identificador
 * é um {@link UUID} textual, simples de armazenar em SQLite e adequado para
 * relações locais entre entidades.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public abstract class AetherEntity {

    /** Identificador único da entidade; nunca {@code null} nem vazio depois de atribuído. */
    private String id;

    /** Momento de criação da entidade; {@code null} enquanto não atribuído. */
    private LocalDateTime createdAt;

    /** Momento da última atualização da entidade; {@code null} enquanto não atribuído. */
    private LocalDateTime updatedAt;

    /**
     * Cria uma nova entidade, gerando um identificador único automaticamente.
     */
    protected AetherEntity() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Cria uma entidade a partir de um identificador já existente.
     * <p>
     * Útil ao reconstruir entidades a partir da base de dados.
     * </p>
     *
     * @param id        o identificador existente; se {@code null}, é gerado um novo
     * @param createdAt o momento de criação; pode ser {@code null}
     * @param updatedAt o momento de atualização; pode ser {@code null}
     */
    protected AetherEntity(String id, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Devolve o identificador da entidade.
     *
     * @return o identificador único
     */
    public String getId() {
        return id;
    }

    /**
     * Define o identificador da entidade.
     *
     * @param id o identificador; se {@code null} ou vazio, mantém o atual
     */
    public void setId(String id) {
        if (id != null && !id.isBlank()) {
            this.id = id;
        }
    }

    /**
     * Devolve o momento de criação da entidade.
     *
     * @return o momento de criação, ou {@code null} se não definido
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Define o momento de criação da entidade.
     *
     * @param createdAt o momento de criação, ou {@code null} para limpar
     */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Devolve o momento da última atualização da entidade.
     *
     * @return o momento de atualização, ou {@code null} se não definido
     */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Define o momento da última atualização da entidade.
     *
     * @param updatedAt o momento de atualização, ou {@code null} para limpar
     */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Marca a entidade como atualizada neste momento.
     * <p>
     * Atualiza {@link #updatedAt} para o instante atual. Deve ser chamado
     * sempre que uma entidade é modificada.
     * </p>
     */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Compara esta entidade a outra com base no identificador.
     *
     * @param o o objeto a comparar
     * @return {@code true} se forem a mesma entidade (mesmo id e classe)
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AetherEntity that = (AetherEntity) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Devolve o hash code com base no identificador.
     *
     * @return o hash code do identificador
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
