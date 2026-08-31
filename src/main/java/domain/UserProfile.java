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
 * <p>
 * Os dados estruturados (about, studies, experience, skills, interests,
 * objectives, preferences, projects, workStyle) são informação confirmada
 * pelo utilizador. O campo {@code inferredContext} contém informação inferida
 * pela IA, claramente separada. O campo {@code suggestedUpdates} contém
 * sugestões da IA que o utilizador ainda não confirmou.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.2
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
     * Tratado como dados confirmados do perfil.
     */
    private String about = "";

    /**
     * Contexto central do utilizador para a IA — a nota principal que serve de
     * fundação ao sistema de memória/contexto do AETHER. O utilizador escreve
     * livremente informações sobre si próprio que considera importantes para a
     * IA conhecer. Nunca {@code null}.
     */
    private String aiContext = "";

    /** Local path to the user's profile photo; empty when no photo is set. */
    private String profilePhotoPath = "";

    // --- Structured profile data (confirmed by user) ---

    /** Estudos / formação académica; nunca {@code null}. */
    private String studies = "";

    /** Experiência profissional resumida; nunca {@code null}. */
    private String experience = "";

    /** Competências / skills; nunca {@code null}. */
    private String skills = "";

    /** Interesses e hobbies; nunca {@code null}. */
    private String interests = "";

    /** Objetivos e metas; nunca {@code null}. */
    private String objectives = "";

    /** Preferências gerais; nunca {@code null}. */
    private String preferences = "";

    /** Projetos atuais ou passados; nunca {@code null}. */
    private String projects = "";

    /** Estilo de trabalho preferido; nunca {@code null}. */
    private String workStyle = "";

    /**
     * Resumo compacto do utilizador para contexto geral da IA.
     * Editável pelo utilizador. Se vazio, o ContextManager gera um resumo
     * derivado dos outros campos. Nunca {@code null}.
     */
    private String profileSummary = "";

    // --- AI-inferred data (clearly separated from confirmed data) ---

    /**
     * Informação inferida pela IA sobre o utilizador, claramente separada
     * dos dados confirmados. Nunca {@code null}.
     */
    private String inferredContext = "";

    /**
     * Sugestões da IA para atualização do perfil. Estas sugestões nunca são
     * aplicadas automaticamente — o utilizador deve confirmar explicitamente.
     * Nunca {@code null}.
     */
    private String suggestedUpdates = "";

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
     * Devolve o contexto central de IA do utilizador.
     * <p>
     * Esta é a nota principal do utilizador — a fundação do sistema de
     * contexto do AETHER. O conteúdo é usado como nó central do Context Graph
     * e como fonte de contexto pela IA.
     * </p>
     *
     * @return o contexto de IA, ou string vazia se não definido
     */
    public String getAiContext() {
        return aiContext;
    }

    /**
     * Define o contexto central de IA do utilizador.
     *
     * @param aiContext o contexto de IA; se {@code null}, é guardado como vazio
     */
    public void setAiContext(String aiContext) {
        this.aiContext = aiContext != null ? aiContext : "";
    }

    /**
     * Returns the local path of the profile photo.
     *
     * @return profile photo path, or an empty string when none is configured
     */
    public String getProfilePhotoPath() {
        return profilePhotoPath;
    }

    /**
     * Sets the local profile photo path.
     *
     * @param profilePhotoPath local photo path
     */
    public void setProfilePhotoPath(String profilePhotoPath) {
        this.profilePhotoPath = profilePhotoPath != null ? profilePhotoPath : "";
    }

    // --- Structured profile data getters/setters ---

    public String getStudies() {
        return studies;
    }

    public void setStudies(String studies) {
        this.studies = studies != null ? studies : "";
    }

    public String getExperience() {
        return experience;
    }

    public void setExperience(String experience) {
        this.experience = experience != null ? experience : "";
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills != null ? skills : "";
    }

    public String getInterests() {
        return interests;
    }

    public void setInterests(String interests) {
        this.interests = interests != null ? interests : "";
    }

    public String getObjectives() {
        return objectives;
    }

    public void setObjectives(String objectives) {
        this.objectives = objectives != null ? objectives : "";
    }

    public String getPreferences() {
        return preferences;
    }

    public void setPreferences(String preferences) {
        this.preferences = preferences != null ? preferences : "";
    }

    public String getProjects() {
        return projects;
    }

    public void setProjects(String projects) {
        this.projects = projects != null ? projects : "";
    }

    public String getWorkStyle() {
        return workStyle;
    }

    public void setWorkStyle(String workStyle) {
        this.workStyle = workStyle != null ? workStyle : "";
    }

    /**
     * Devolve o resumo compacto do utilizador para contexto geral da IA.
     * Se vazio, o ContextManager deve gerar um resumo derivado.
     *
     * @return resumo do perfil ou string vazia
     */
    public String getProfileSummary() {
        return profileSummary;
    }

    /**
     * Define o resumo compacto do utilizador.
     *
     * @param profileSummary resumo do perfil
     */
    public void setProfileSummary(String profileSummary) {
        this.profileSummary = profileSummary != null ? profileSummary : "";
    }

    /**
     * Devolve o resumo do perfil para exibição no Context Graph.
     * Se o utilizador definiu um resumo, usa-o. Caso contrário,
     * deriva um resumo do campo About ou occupations.
     *
     * @return resumo para exibição
     */
    public String getDisplaySummary() {
        if (profileSummary != null && !profileSummary.isBlank()) {
            return profileSummary.trim();
        }
        if (about != null && !about.isBlank()) {
            return about.trim();
        }
        if (!occupations.isEmpty()) {
            return String.join(", ", occupations);
        }
        return "";
    }

    // --- AI-inferred data getters/setters ---

    public String getInferredContext() {
        return inferredContext;
    }

    public void setInferredContext(String inferredContext) {
        this.inferredContext = inferredContext != null ? inferredContext : "";
    }

    public String getSuggestedUpdates() {
        return suggestedUpdates;
    }

    public void setSuggestedUpdates(String suggestedUpdates) {
        this.suggestedUpdates = suggestedUpdates != null ? suggestedUpdates : "";
    }

    /**
     * Builds the complete context that should be supplied to AETHER.
     * The structured profile data is always included automatically;
     * {@link #getAiContext()} contains only the user's additional free-form notes.
     * <p>
     * Note: This method builds the full context. The ContextManager may
     * select only relevant portions for a given message.
     * </p>
     *
     * @return complete AI context assembled from the profile
     */
    public String buildAiContext() {
        StringBuilder context = new StringBuilder();
        context.append("USER PROFILE (CONFIRMED DATA)\n");
        appendContextLine(context, "Full name", fullName);
        appendContextLine(context, "Preferred name", preferredName);
        if (birthDate != null) {
            appendContextLine(context, "Birthday", birthDate.toString());
        }
        if (occupations != null && !occupations.isEmpty()) {
            appendContextLine(context, "Occupation / roles", String.join(", ", occupations));
        }
        appendContextLine(context, "About", about);
        appendContextLine(context, "Studies", studies);
        appendContextLine(context, "Experience", experience);
        appendContextLine(context, "Skills", skills);
        appendContextLine(context, "Interests", interests);
        appendContextLine(context, "Objectives", objectives);
        appendContextLine(context, "Preferences", preferences);
        appendContextLine(context, "Projects", projects);
        appendContextLine(context, "Work style", workStyle);

        if (aiContext != null && !aiContext.isBlank()) {
            context.append("\nADDITIONAL USER CONTEXT\n");
            context.append(aiContext.trim());
            context.append('\n');
        }

        if (inferredContext != null && !inferredContext.isBlank()) {
            context.append("\nINFERRED CONTEXT (AI-generated, not confirmed by user)\n");
            context.append(inferredContext.trim());
            context.append('\n');
        }

        if (suggestedUpdates != null && !suggestedUpdates.isBlank()) {
            context.append("\nSUGGESTED PROFILE UPDATES (pending user confirmation)\n");
            context.append(suggestedUpdates.trim());
            context.append('\n');
        }

        return context.toString().trim();
    }

    /** Appends a non-empty profile field to an AI context. */
    private void appendContextLine(StringBuilder context, String label, String value) {
        if (value != null && !value.isBlank()) {
            context.append(label).append(": ").append(value.trim()).append('\n');
        }
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
                (about == null || about.isBlank()) &&
                (aiContext == null || aiContext.isBlank()) &&
                (profilePhotoPath == null || profilePhotoPath.isBlank()) &&
                (studies == null || studies.isBlank()) &&
                (experience == null || experience.isBlank()) &&
                (skills == null || skills.isBlank()) &&
                (interests == null || interests.isBlank()) &&
                (objectives == null || objectives.isBlank()) &&
                (preferences == null || preferences.isBlank()) &&
                (projects == null || projects.isBlank()) &&
                (workStyle == null || workStyle.isBlank()) &&
                (profileSummary == null || profileSummary.isBlank()) &&
                (inferredContext == null || inferredContext.isBlank()) &&
                (suggestedUpdates == null || suggestedUpdates.isBlank());
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
                ", aiContext='" + aiContext + '\'' +
                ", studies='" + studies + '\'' +
                ", experience='" + experience + '\'' +
                ", skills='" + skills + '\'' +
                ", interests='" + interests + '\'' +
                ", objectives='" + objectives + '\'' +
                ", preferences='" + preferences + '\'' +
                ", projects='" + projects + '\'' +
                ", workStyle='" + workStyle + '\'' +
                ", profileSummary='" + profileSummary + '\'' +
                ", inferredContext='" + inferredContext + '\'' +
                ", suggestedUpdates='" + suggestedUpdates + '\'' +
                '}';
    }
}