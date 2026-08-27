package domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Representa o perfil de utilizador recolhido durante o onboarding do AETHER.
 * <p>
 * Armazena dados pessoais como nome, data de nascimento, ocupações e uma
 * descrição livre. É usada pelo {@code UserSession} para manter os dados em
 * memória enquanto a aplicação está em execução.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class UserProfile {

    /**
     * Cria um perfil vazio, com todos os campos de texto inicializados a vazio.
     */
    public UserProfile() {
        // Construtor por omissão explícito, documentado para o Javadoc.
    }

    /** Nome completo do utilizador; nunca {@code null}. */
    private String fullName = "";

    /** Nome pelo qual o utilizador prefere ser tratado; nunca {@code null}. */
    private String preferredName = "";

    /** Data de nascimento; {@code null} quando não indicada. */
    private LocalDate birthDate = null;

    /** Ocupações selecionadas ou escritas pelo utilizador; nunca {@code null}. */
    private List<String> occupations = new ArrayList<>();

    /** Descrição pessoal livre; nunca {@code null}. */
    private String aboutYou = "";

    /**
     * Devolve o nome completo do utilizador.
     *
     * @return o nome completo, ou string vazia se não definido
     */
    public String getFullName() {
        return fullName;
    }

    /**
     * Define o nome completo do utilizador.
     *
     * @param fullName o nome completo a armazenar
     */
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /**
     * Devolve o nome preferido (como o utilizador gosta de ser chamado).
     *
     * @return o nome preferido, ou string vazia se não definido
     */
    public String getPreferredName() {
        return preferredName;
    }

    /**
     * Define o nome preferido do utilizador.
     *
     * @param preferredName o nome preferido a armazenar
     */
    public void setPreferredName(String preferredName) {
        this.preferredName = preferredName;
    }

    /**
     * Devolve a data de nascimento do utilizador.
     *
     * @return a data de nascimento, ou {@code null} se não definida
     */
    public LocalDate getBirthDate() {
        return birthDate;
    }

    /**
     * Define a data de nascimento do utilizador.
     *
     * @param birthDate a data de nascimento, ou {@code null} para limpar
     */
    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    /**
     * Devolve a lista de ocupações do utilizador.
     *
     * @return a lista de ocupações (nunca {@code null})
     */
    public List<String> getOccupations() {
        return occupations;
    }

    /**
     * Devolve a categoria predefinida correspondente à ocupação principal.
     * <p>
     * Ocupações personalizadas, que não coincidem com nenhuma categoria
     * conhecida, são classificadas como {@link Occupation#OTHER}.
     * </p>
     *
     * @return a categoria da ocupação principal, ou {@link Occupation#OTHER} se não existir nenhuma
     */
    public Occupation getPrimaryOccupationCategory() {
        if (occupations.isEmpty()) {
            return Occupation.OTHER;
        }
        return Occupation.fromDisplayName(occupations.get(0));
    }

    /**
     * Substitui a lista de ocupações do utilizador.
     *
     * @param occupations a nova lista de ocupações; se {@code null}, é substituída por uma lista vazia
     */
    public void setOccupations(List<String> occupations) {
        this.occupations = occupations != null ? occupations : new ArrayList<>();
    }

    /**
     * Devolve a descrição pessoal ("About You") do utilizador.
     *
     * @return a descrição, ou string vazia se não definida
     */
    public String getAboutYou() {
        return aboutYou;
    }

    /**
     * Define a descrição pessoal do utilizador.
     *
     * @param aboutYou a descrição a armazenar
     */
    public void setAboutYou(String aboutYou) {
        this.aboutYou = aboutYou;
    }

    /**
     * Verifica se o perfil está completamente vazio (sem nenhum dado preenchido).
     *
     * @return {@code true} se todos os campos estiverem vazios ou nulos
     */
    public boolean isEmpty() {
        return (fullName == null || fullName.isBlank()) &&
                (preferredName == null || preferredName.isBlank()) &&
                birthDate == null &&
                occupations.isEmpty() &&
                (aboutYou == null || aboutYou.isBlank());
    }

    /**
     * Devolve uma representação textual do perfil, útil para registo e depuração.
     *
     * @return os campos do perfil formatados numa única linha
     */
    @Override
    public String toString() {
        return "UserProfile{" +
                "fullName='" + fullName + '\'' +
                ", preferredName='" + preferredName + '\'' +
                ", birthDate=" + birthDate +
                ", occupations=" + occupations +
                ", aboutYou='" + aboutYou + '\'' +
                '}';
    }
}
