package ai;

import domain.UserProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ProfileFieldSchema — esquema canónico dos campos de perfil do utilizador.
 * <p>
 * ÚNICA fonte de verdade para os nomes canónicos de campo, os seus aliases e a
 * ligação a {@link UserProfile}. Todas as camadas que leem ou escrevem campos
 * de perfil (extractores, {@link AiActionOrchestrator}, {@link ActionExecutor},
 * {@link util.ContextManager}, prompt do {@link OllamaActionExtractor}) usam
 * esta classe — elimina divergências em que um campo é suportado por uma
 * camada mas não por outra (ex.: {@code location} existia no executor mas
 * faltava no prompt e no extract heurístico).
 * </p>
 * <p>
 * Campos especiais:
 * <ul>
 *   <li>{@code inferredContext} — acumula informação aceite sem campo
 *       estruturado (bullets); nunca substitui, acrescenta.</li>
 *   <li>{@code aiContext} — texto MANUAL do utilizador; a IA nunca escreve
 *       aqui (remapeado para {@code inferredContext}).</li>
 * </ul>
 * </p>
 *
 * @author AETHER
 */
public final class ProfileFieldSchema {

    private ProfileFieldSchema() {
        // Classe de utilitário estático.
    }

    /** Ordem canónica dos campos estruturados do perfil. */
    private static final List<String> CANONICAL = List.of(
            "fullName", "preferredName", "birthDate", "about", "occupation", "studies",
            "experience", "skills", "interests", "objectives", "preferences",
            "projects", "workStyle", "location", "profileSummary",
            "inferredContext");

    /** Aliases aceites (chave antiga/alternativa → chave canónica). */
    private static final Map<String, String> ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("education", "studies"),
            java.util.Map.entry("competencies", "skills"),
            java.util.Map.entry("work_style", "workStyle"),
            java.util.Map.entry("profession", "occupation"),
            java.util.Map.entry("role", "occupation"),
            java.util.Map.entry("city", "location"),
            java.util.Map.entry("home", "location"),
            java.util.Map.entry("birthday", "birthDate"),
            java.util.Map.entry("birth_date", "birthDate"),
            java.util.Map.entry("inferred_context", "inferredContext"),
            java.util.Map.entry("summary", "profileSummary"),
            java.util.Map.entry("bio", "about"));

    /** Campos de contexto que acumulam bullets (nunca substituem). */
    public static final Set<String> CONTEXT_FIELDS = Set.of("inferredContext");

    /**
     * Lista canónica dos campos de perfil (nomes exatos a usar em prompts,
     * propostas, executor e validador).
     *
     * @return lista imutável de nomes canónicos
     */
    public static List<String> canonicalFields() {
        return CANONICAL;
    }

    /**
     * Normaliza um nome de campo para o nome canónico. Desconhecidos ficam
     * inalterados (o executor encaminha-os para {@code inferredContext}).
     *
     * @param key o nome de campo (pode ser alias)
     * @return o nome canónico
     */
    public static String canonical(String key) {
        if (key == null) return "";
        String k = key.trim();
        return ALIASES.getOrDefault(k, k);
    }

    /**
     * Verifica se a chave é um campo canónico conhecido.
     */
    public static boolean isKnown(String key) {
        return key != null && CANONICAL.contains(canonical(key));
    }

    /**
     * Devolve o valor atual de um campo canónico no perfil.
     *
     * @param profile o perfil do utilizador
     * @param key     nome canónico (ou alias)
     * @return o valor atual, ou {@code null} se o campo for desconhecido
     */
    public static String valueOf(UserProfile profile, String key) {
        if (profile == null || key == null) return null;
        return switch (canonical(key)) {
            case "fullName" -> profile.getFullName();
            case "preferredName" -> profile.getPreferredName();
            case "birthDate" -> profile.getBirthDate() == null
                    ? "" : profile.getBirthDate().toString();
            case "about" -> profile.getAbout();
            case "occupation" -> profile.getOccupation();
            case "studies" -> profile.getStudies();
            case "experience" -> profile.getExperience();
            case "skills" -> profile.getSkills();
            case "interests" -> profile.getInterests();
            case "objectives" -> profile.getObjectives();
            case "preferences" -> profile.getPreferences();
            case "projects" -> profile.getProjects();
            case "workStyle" -> profile.getWorkStyle();
            case "location" -> profile.getLocation();
            case "profileSummary" -> profile.getProfileSummary();
            case "aiContext" -> profile.getAiContext();
            case "inferredContext" -> profile.getInferredContext();
            default -> null;
        };
    }

    /**
     * Define o valor de um campo canónico no perfil.
     *
     * @param profile o perfil do utilizador
     * @param key     nome canónico (ou alias)
     * @param value   o novo valor
     * @return {@code true} se o campo é conhecido e foi definido
     */
    public static boolean setValue(UserProfile profile, String key, String value) {
        if (profile == null || key == null) return false;
        String v = value == null ? "" : value;
        switch (canonical(key)) {
            case "fullName" -> { profile.setFullName(v); return true; }
            case "preferredName" -> { profile.setPreferredName(v); return true; }
            case "birthDate" -> {
                try {
                    profile.setBirthDate(v.isBlank() ? null : java.time.LocalDate.parse(v.trim()));
                    return true;
                } catch (java.time.format.DateTimeParseException e) {
                    return false; // formato inválido — não escreve
                }
            }
            case "about" -> { profile.setAbout(v); return true; }
            case "occupation" -> { profile.setOccupation(v); return true; }
            case "studies" -> { profile.setStudies(v); return true; }
            case "experience" -> { profile.setExperience(v); return true; }
            case "skills" -> { profile.setSkills(v); return true; }
            case "interests" -> { profile.setInterests(v); return true; }
            case "objectives" -> { profile.setObjectives(v); return true; }
            case "preferences" -> { profile.setPreferences(v); return true; }
            case "projects" -> { profile.setProjects(v); return true; }
            case "workStyle" -> { profile.setWorkStyle(v); return true; }
            case "location" -> { profile.setLocation(v); return true; }
            case "profileSummary" -> { profile.setProfileSummary(v); return true; }
            // aiContext é MANUAL: a IA nunca o sobrescreve (redireciona).
            case "aiContext", "inferredContext" -> { profile.setInferredContext(v); return true; }
            default -> { return false; }
        }
    }

    /**
     * Descrição legível de um campo, para prompts e contexto de IA.
     *
     * @param key nome canónico
     * @return rótulo em inglês (a língua das secções de contexto do prompt)
     */
    public static String label(String key) {
        return switch (canonical(key)) {
            case "fullName" -> "Full name";
            case "preferredName" -> "Preferred name";
            case "birthDate" -> "Birth date";
            case "about" -> "About";
            case "occupation" -> "Occupation / roles";
            case "studies" -> "Studies";
            case "experience" -> "Experience";
            case "skills" -> "Skills";
            case "interests" -> "Interests";
            case "objectives" -> "Objectives";
            case "preferences" -> "Preferences";
            case "location" -> "Location";
            case "projects" -> "Projects";
            case "workStyle" -> "Work style";
            case "profileSummary" -> "Profile summary";
            case "aiContext" -> "Additional context";
            case "inferredContext" -> "Inferred context";
            default -> key;
        };
    }

    /**
     * Mapa ordenado dos campos estruturados não vazios do perfil
     * (campo canónico → valor), usado para construir o contexto base.
     *
     * @param profile o perfil
     * @return mapa ordenado com apenas os campos preenchidos
     */
    public static Map<String, String> nonEmptyStructuredFields(UserProfile profile) {
        Map<String, String> out = new LinkedHashMap<>();
        if (profile == null) return out;
        for (String key : List.of("fullName", "preferredName", "about", "occupation",
                "studies", "experience", "skills", "interests", "objectives",
                "preferences", "projects", "workStyle", "location", "profileSummary")) {
            String v = valueOf(profile, key);
            if (v != null && !v.isBlank()) {
                out.put(key, v.trim());
            }
        }
        return out;
    }

    /**
     * Normalização para comparação de valores (sem acentos, minúsculas,
     * espaços colapsados) — usada na deduplicação anti-repetição.
     */
    public static String normalizeForCompare(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("^[\\s•\\-*·]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
