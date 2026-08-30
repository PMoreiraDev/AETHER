package domain;

import domain.Occupation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Representa o perfil pessoal do utilizador do AETHER.
 * <p>
 * Contém os dados pessoais recolhidos durante o onboarding e posteriormente
 * utilizados pela aplicação. Este é o modelo canónico do perfil do utilizador.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class UserProfile {

    /**
     * Nome completo do utilizador; nunca {@code null}.
     */
    private String fullName = "";

    /**
     * Nome pelo qual o utilizador prefere ser tratado; nunca {@code null}.
     */
    private String preferredName = "";

    /**
     * Data de nascimento; {@code null} quando não indicada.
     */
    private LocalDate birthDate = null;

    /**
     * Ocupações do utilizador.
     * Nunca {@code null}.
     */
    private List<String> occupations = new ArrayList<>();

    /**
     * Descrição pessoal livre do utilizador; nunca {@code null}.
     */
    private String about = "";

    /**
     * Cria um perfil vazio.
     */
    public UserProfile() {
        // Construtor por omissão explícito.
    }

    /**
     * Devolve o nome completo do utilizador.
     *
     * @return o nome completo
     */
    public String getFullName() {
        return fullName;
    }

    /**
     * Define o nome completo do utilizador.
     *
     * @param fullName o nome completo
     */
    public void setFullName(String fullName) {
        this.fullName = fullName != null ? fullName : "";
    }

    /**
     * Devolve o nome preferido do utilizador.
     *
     * @return o nome preferido
     */
    public String getPreferredName() {
        return preferredName;
    }

    /**
     * Define o nome preferido do utilizador.
     *
     * @param preferredName o nome preferido
     */
    public void setPreferredName(String preferredName) {
        this.preferredName = preferredName != null ? preferredName : "";
    }

    /**
     * Devolve a data de nascimento.
     *
     * @return a data de nascimento ou {@code null}
     */
    public LocalDate getBirthDate() {
        return birthDate;
    }

    /**
     * Define a data de nascimento.
     *
     * @param birthDate a data de nascimento
     */
    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    /**
     * Devolve as ocupações do utilizador.
     *
     * @return lista de ocupações, nunca {@code null}
     */
    public List<String> getOccupations() {
        return occupations;
    }

    /**
     * Substitui as ocupações do utilizador.
     *
     * @param occupations nova lista de ocupações
     */
    public void setOccupations(List<String> occupations) {
        this.occupations = occupations != null
                ? occupations
                : new ArrayList<>();
    }

    /**
     * Devolve a ocupação principal.
     * <p>
     * A ocupação principal corresponde ao primeiro elemento da lista.
     * </p>
     *
     * @return a ocupação principal ou string vazia
     */
    public String getOccupation() {
        return occupations.isEmpty() ? "" : occupations.get(0);
    }

    /**
     * Define a ocupação principal.
     * <p>
     * Este método limpa as ocupações existentes e coloca a ocupação indicada
     * como primeira ocupação.
     * </p>
     *
     * @param occupation ocupação principal
     */
    public void setOccupation(String occupation) {
        occupations.clear();

        if (occupation != null && !occupation.isBlank()) {
            occupations.add(occupation);
        }
    }

    /**
     * Devolve a categoria da ocupação principal.
     *
     * @return categoria da ocupação ou {@link Occupation#OTHER}
     */
    public Occupation getPrimaryOccupationCategory() {
        if (occupations.isEmpty()) {
            return Occupation.OTHER;
        }

        return Occupation.fromDisplayName(occupations.get(0));
    }

    /**
     * Devolve a descrição pessoal do utilizador.
     *
     * @return descrição pessoal
     */
    public String getAbout() {
        return about;
    }

    /**
     * Define a descrição pessoal do utilizador.
     *
     * @param about descrição pessoal
     */
    public void setAbout(String about) {
        this.about = about != null ? about : "";
    }

    /**
     * Alias para {@link #getAbout()}.
     *
     * @return descrição pessoal
     */
    public String getAboutYou() {
        return about;
    }

    /**
     * Alias para {@link #setAbout(String)}.
     *
     * @param aboutYou descrição pessoal
     */
    public void setAboutYou(String aboutYou) {
        this.about = aboutYou != null ? aboutYou : "";
    }

    /**
     * Verifica se o perfil está completamente vazio.
     *
     * @return {@code true} se nenhum dado tiver sido preenchido
     */
    public boolean isEmpty() {
        return (fullName == null || fullName.isBlank()) &&
                (preferredName == null || preferredName.isBlank()) &&
                birthDate == null &&
                occupations.isEmpty() &&
                (about == null || about.isBlank());
    }

    /**
     * Devolve uma representação textual do perfil.
     *
     * @return perfil formatado
     */
    @Override
    public String toString() {
        return "Profile{" +
                "fullName='" + fullName + '\'' +
                ", preferredName='" + preferredName + '\'' +
                ", birthDate=" + birthDate +
                ", occupations=" + occupations +
                ", about='" + about + '\'' +
                '}';
    }
}