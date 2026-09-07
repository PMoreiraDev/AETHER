package util;

/**
 * ProfileFieldLabels — traduz chaves técnicas de campos do UserProfile em
 * etiquetas legíveis, partilhadas entre o Profile e o painel de notificações.
 *
 * @author AETHER
 */
public final class ProfileFieldLabels {

    private ProfileFieldLabels() {
        // Utilitário — não instanciável.
    }

    /** Devolve uma etiqueta legível para uma chave de campo do perfil. */
    public static String label(String field) {
        if (field == null) return "";
        return switch (field) {
            case "fullName" -> "Nome completo";
            case "preferredName" -> "Nome preferido";
            case "about" -> "Sobre";
            case "occupation" -> "Ocupação";
            case "studies" -> "Estudos";
            case "experience" -> "Experiência";
            case "skills" -> "Competências";
            case "interests" -> "Interesses";
            case "objectives" -> "Objetivos";
            case "preferences" -> "Preferências";
            case "projects" -> "Projetos";
            case "workStyle" -> "Estilo de trabalho";
            case "location" -> "Localização";
            case "aiContext" -> "Contexto do utilizador";
            case "inferredContext" -> "Contexto inferido";
            case "profileSummary" -> "Resumo";
            default -> field;
        };
    }
}
