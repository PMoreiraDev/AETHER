package domain.entities;

import java.time.LocalDate;

/**
 * Uma pessoa do círculo pessoal ou profissional do utilizador.
 * <p>
 * Diferencia-se de {@link Profile}: {@code Profile} é o próprio utilizador
 * dono do AETHER, enquanto {@code Person} representa terceiros — contactos,
 * colegas, amigos — que podem ser ligados a notas, projetos, eventos e
 * memórias através do Context Graph.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class Person extends AetherEntity {

    /** Nome da pessoa; nunca {@code null}. */
    private String name = "";

    /** Data de nascimento; {@code null} quando não indicada. */
    private LocalDate birthDate = null;

    /** Ocupação da pessoa; nunca {@code null}. */
    private String occupation = "";

    /** Descrição livre sobre a pessoa; nunca {@code null}. */
    private String about = "";

    /**
     * Cria uma pessoa vazia com identificador e timestamps gerados automaticamente.
     */
    public Person() {
        super();
    }

    /**
     * Cria uma pessoa a partir de dados persistidos.
     *
     * @param id         o identificador existente
     * @param name       o nome da pessoa
     * @param birthDate  a data de nascimento
     * @param occupation a ocupação
     * @param about      a descrição
     * @param createdAt  o momento de criação
     * @param updatedAt  o momento da última atualização
     */
    public Person(String id, String name, LocalDate birthDate, String occupation,
                  String about, java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
        super(id, createdAt, updatedAt);
        this.name = name != null ? name : "";
        this.birthDate = birthDate;
        this.occupation = occupation != null ? occupation : "";
        this.about = about != null ? about : "";
    }

    /**
     * Devolve o nome da pessoa.
     *
     * @return o nome, ou string vazia se não definido
     */
    public String getName() {
        return name;
    }

    /**
     * Define o nome da pessoa.
     *
     * @param name o nome a armazenar
     */
    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    /**
     * Devolve a data de nascimento da pessoa.
     *
     * @return a data de nascimento, ou {@code null} se não definida
     */
    public LocalDate getBirthDate() {
        return birthDate;
    }

    /**
     * Define a data de nascimento da pessoa.
     *
     * @param birthDate a data de nascimento, ou {@code null} para limpar
     */
    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    /**
     * Devolve a ocupação da pessoa.
     *
     * @return a ocupação, ou string vazia se não definida
     */
    public String getOccupation() {
        return occupation;
    }

    /**
     * Define a ocupação da pessoa.
     *
     * @param occupation a ocupação a armazenar
     */
    public void setOccupation(String occupation) {
        this.occupation = occupation != null ? occupation : "";
    }

    /**
     * Devolve a descrição sobre a pessoa.
     *
     * @return a descrição, ou string vazia se não definida
     */
    public String getAbout() {
        return about;
    }

    /**
     * Define a descrição sobre a pessoa.
     *
     * @param about a descrição a armazenar
     */
    public void setAbout(String about) {
        this.about = about != null ? about : "";
    }

    /**
     * Devolve uma representação textual da pessoa.
     *
     * @return os campos da pessoa formatados
     */
    @Override
    public String toString() {
        return "Person{" +
                "id='" + getId() + '\'' +
                ", name='" + name + '\'' +
                ", birthDate=" + birthDate +
                ", occupation='" + occupation + '\'' +
                ", about='" + about + '\'' +
                ", createdAt=" + getCreatedAt() +
                ", updatedAt=" + getUpdatedAt() +
                '}';
    }
}
