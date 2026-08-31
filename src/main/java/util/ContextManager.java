package util;

import domain.UserProfile;
import session.UserSession;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Context Manager — arquitetura de contexto para a IA do AETHER.
 * <p>
 * Implementa o fluxo:
 * <pre>
 *   User Profile → User Memory → Context Manager → Relevant Context → AI Prompt
 * </pre>
 * <p>
 * O Context Manager mantém os dados completos do utilizador persistidos
 * localmente, estrutura a informação por categorias, e seleciona apenas a
 * informação relevante para cada pedido. Mantém um resumo compacto do
 * utilizador para situações gerais.
 * <p>
 * O Context Manager nunca envia indiscriminadamente toda a base de dados
 * do utilizador em todas as mensagens. Em vez disso, envia sempre:
 * <ul>
 *   <li>Resumo compacto do utilizador (para contexto geral)</li>
 *   <li>Data e hora atuais dinâmicas (com timezone)</li>
 *   <li>System prompt robusto</li>
 *   <li>Contexto relevante selecionado com base na mensagem do utilizador</li>
 * </ul>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class ContextManager {

    /** Timezone do utilizador — Europe/Lisbon. */
    private static final ZoneId USER_TIMEZONE = ZoneId.of("Europe/Lisbon");

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Construtor privado — esta é uma classe de utilitário estático.
     */
    private ContextManager() {
        // Não instanciável.
    }

    /**
     * Constrói o prompt de sistema completo para a IA, incluindo:
     * <ul>
     *   <li>Instruções de sistema robustas</li>
     *   <li>Data e hora atuais dinâmicas com timezone</li>
     *   <li>Resumo compacto do utilizador (sempre incluído)</li>
     *   <li>Contexto relevante selecionado com base na mensagem</li>
     * </ul>
     *
     * @param userMessage a mensagem do utilizador (para seleção de contexto relevante)
     * @return o prompt de sistema completo, ou {@code null} se não houver perfil
     */
    public static String buildSystemPrompt(String userMessage) {
        UserProfile profile = UserSession.getInstance().getUserProfile();
        if (profile == null) {
            return buildSystemPromptWithoutProfile();
        }

        StringBuilder prompt = new StringBuilder();

        // 1. System prompt robusto
        prompt.append(buildSystemInstructions());

        // 2. Dynamic current date/time with timezone
        prompt.append(buildDynamicDateTime());

        // 3. Compact user summary (always included)
        prompt.append(buildCompactSummary(profile));

        // 4. Relevant context selection based on user message
        prompt.append(selectRelevantContext(profile, userMessage));

        return prompt.toString().trim();
    }

    /**
     * Constrói o prompt de sistema robusto — as instruções base da IA.
     *
     * @return instruções de sistema
     */
    private static String buildSystemInstructions() {
        return """
                AETHER SYSTEM INSTRUCTIONS

                You are AETHER AI, a personal intelligent assistant.
                Think carefully before answering.
                Always use the provided current date and time.
                Never invent personal information about the user.

                CONTEXT HANDLING RULES:
                1. CONFIRMED information: data the user explicitly provided in their profile or during conversation. You may use this freely.
                2. INFERRED information: context the AI has inferred but the user has not explicitly confirmed. You may use this cautiously, but never present it as fact.
                3. TEMPORARY information: details relevant only to the current conversation. Do not persist these.

                PROFILE UPDATE RULES:
                - When the user provides new persistent personal information, identify it as a possible profile update.
                - Suggest the user add it to their profile, but do NOT assume it has been added.
                - The AI can suggest changes to the profile, but only the user can confirm them.
                - Never auto-save profile changes. Always ask for explicit confirmation.
                - Maintain a clear separation between confirmed information and inferred information.

                DATE AND TIME RULES:
                - Always calculate age from the birthday and the current date provided.
                - Interpret relative dates (today, tomorrow, next week, etc.) using the current date.
                - When discussing deadlines or time-sensitive topics, reference the current date.

                RESPONSE RULES:
                - Be concise but thorough.
                - Ask a focused clarification only when the request genuinely cannot be answered safely or accurately.
                - Use the user's profile information when relevant to the conversation.
                - Do not mention that you have access to profile data unless it is relevant to do so.

                """;
    }

    /**
     * Constrói a secção de data e hora atuais dinâmicas, com timezone.
     * Gerada dinamicamente a cada chamada.
     *
     * @return secção de data/hora atual
     */
    private static String buildDynamicDateTime() {
        ZonedDateTime now = ZonedDateTime.now(USER_TIMEZONE);

        StringBuilder dt = new StringBuilder();
        dt.append("CURRENT DATE: ").append(now.format(DATE_FORMATTER)).append("\n");
        dt.append("CURRENT TIME: ").append(now.format(TIME_FORMATTER)).append("\n");
        dt.append("CURRENT DATE AND TIME: ").append(now.format(DATETIME_FORMATTER)).append("\n");
        dt.append("TIMEZONE: ").append(USER_TIMEZONE.toString()).append("\n");
        dt.append("DAY OF WEEK: ").append(now.getDayOfWeek().toString()).append("\n");

        return dt.toString();
    }

    /**
     * Constrói o resumo compacto do utilizador — sempre incluído no contexto.
     * Usa o profileSummary se definido, caso contrário deriva um resumo.
     *
     * @param profile o perfil do utilizador
     * @return resumo compacto formatado
     */
    private static String buildCompactSummary(UserProfile profile) {
        StringBuilder summary = new StringBuilder();
        summary.append("\nUSER SUMMARY\n");

        // Nome
        String displayName = profile.getPreferredName();
        if (displayName == null || displayName.isBlank()) {
            displayName = profile.getFullName();
        }
        if (displayName != null && !displayName.isBlank()) {
            summary.append("Name: ").append(displayName).append("\n");
        }

        // Data de nascimento + idade calculada (usando timezone do utilizador)
        if (profile.getBirthDate() != null) {
            LocalDate today = ZonedDateTime.now(USER_TIMEZONE).toLocalDate();
            long age = ChronoUnit.YEARS.between(profile.getBirthDate(), today);
            summary.append("Birthday: ").append(profile.getBirthDate()).append(" (age: ").append(age).append(")\n");
        }

        // Ocupação principal
        if (!profile.getOccupations().isEmpty()) {
            summary.append("Occupation: ").append(String.join(", ", profile.getOccupations())).append("\n");
        }

        // Resumo do utilizador (profileSummary ou derivado de about)
        String displaySummary = profile.getDisplaySummary();
        if (displaySummary != null && !displaySummary.isBlank()) {
            summary.append("Summary: ").append(displaySummary).append("\n");
        }

        return summary.toString();
    }

    /**
     * Seleciona contexto relevante com base na mensagem do utilizador.
     * <p>
     * Em vez de enviar toda a base de dados do utilizador, esta metodologia
     * seleciona apenas as secções relevantes com base em palavras-chave
     * na mensagem do utilizador.
     * </p>
     *
     * @param profile o perfil do utilizador
     * @param userMessage a mensagem do utilizador
     * @return contexto relevante selecionado
     */
    private static String selectRelevantContext(UserProfile profile, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            // Sem mensagem, enviar apenas contexto compacto
            return "";
        }

        String msg = userMessage.toLowerCase();
        StringBuilder context = new StringBuilder();
        int relevantSections = 0;

        // Selecionar com base em palavras-chave
        if (containsAny(msg, "study", "studies", "school", "education", "university", "college", "curso", "escola", "universidade", "formação")) {
            appendIfPresent(context, "Studies", profile.getStudies());
            relevantSections++;
        }

        if (containsAny(msg, "work", "job", "career", "experience", "professional", "trabalho", "carreira", "experiência", "profissão")) {
            appendIfPresent(context, "Experience", profile.getExperience());
            relevantSections++;
        }

        if (containsAny(msg, "skill", "skills", "competence", "ability", "competência", "habilidade", "qualificação")) {
            appendIfPresent(context, "Skills", profile.getSkills());
            relevantSections++;
        }

        if (containsAny(msg, "interest", "hobby", "hobbies", "passion", "hobby", "interesse", "paixão")) {
            appendIfPresent(context, "Interests", profile.getInterests());
            relevantSections++;
        }

        if (containsAny(msg, "goal", "objective", "target", "plan", "future", "meta", "objetivo", "plano", "futuro", "planeamento")) {
            appendIfPresent(context, "Objectives", profile.getObjectives());
            relevantSections++;
        }

        if (containsAny(msg, "prefer", "like", "dislike", "taste", "preferência", "gosto", "não gosto", "gostos")) {
            appendIfPresent(context, "Preferences", profile.getPreferences());
            relevantSections++;
        }

        if (containsAny(msg, "project", "task", "work on", "projeto", "tarefa", "a trabalhar")) {
            appendIfPresent(context, "Projects", profile.getProjects());
            relevantSections++;
        }

        if (containsAny(msg, "style", "how i work", "workflow", "process", "estilo", "forma de trabalhar", "processo")) {
            appendIfPresent(context, "Work style", profile.getWorkStyle());
            relevantSections++;
        }

        // Determinar se a mensagem é uma conversa geral (sem tópicos específicos)
        boolean isGeneral = relevantSections == 0 || msg.length() < 25 || containsAny(msg,
                "who am i", "tell me about", "about me", "who am", "quem sou", "sobre mim",
                "hello", "hi ", "olá", "ola");

        // Contexto adicional livre — incluído quando relevante (conversa geral,
        // sobre o utilizador, ou quando curto)
        String aiContext = profile.getAiContext();
        if (aiContext != null && !aiContext.isBlank()) {
            boolean includeFull = isGeneral || aiContext.length() < 300 || relevantSections > 0;
            if (includeFull) {
                context.append(buildAdditionalContext(profile));
            }
        }

        // Contexto inferido pela IA — só incluído quando relevante e claramente separado
        if (profile.getInferredContext() != null && !profile.getInferredContext().isBlank()
                && (msg.contains("about") || msg.contains("who am") || msg.contains("remember")
                        || msg.contains("pergunt") || msg.contains("quem") || msg.contains("sobre"))) {
            context.append("\nINFERRED CONTEXT (AI-generated, not confirmed by user):\n");
            context.append(profile.getInferredContext().trim()).append("\n");
        }

        // Sugestões pendentes — só incluídas quando relevante
        if (profile.getSuggestedUpdates() != null && !profile.getSuggestedUpdates().isBlank()
                && (msg.contains("update") || msg.contains("profile") || msg.contains("perfil")
                        || msg.contains("atualiz") || msg.contains("about"))) {
            context.append("\nSUGGESTED PROFILE UPDATES (pending user confirmation):\n");
            context.append(profile.getSuggestedUpdates().trim()).append("\n");
        }

        return context.toString();
    }

    /**
     * Constrói a secção de contexto adicional livre do utilizador.
     *
     * @param profile o perfil do utilizador
     * @return secção de contexto adicional
     */
    private static String buildAdditionalContext(UserProfile profile) {
        StringBuilder ctx = new StringBuilder();
        if (profile.getAiContext() != null && !profile.getAiContext().isBlank()) {
            ctx.append("\nADDITIONAL USER CONTEXT\n");
            ctx.append(profile.getAiContext().trim()).append("\n");
        }
        return ctx.toString();
    }

    /**
     * Verifica se a mensagem contém alguma das palavras-chave.
     *
     * @param message a mensagem em minúsculas
     * @param keywords as palavras-chave a procurar
     * @return {@code true} se alguma palavra-chave for encontrada
     */
    private static boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Anexa um campo ao contexto se o valor não for vazio.
     *
     * @param context o StringBuilder do contexto
     * @param label o rótulo do campo
     * @param value o valor do campo
     */
    private static void appendIfPresent(StringBuilder context, String label, String value) {
        if (value != null && !value.isBlank()) {
            context.append("\n").append(label).append(": ").append(value.trim()).append("\n");
        }
    }

    /**
     * Constrói um prompt de sistema mínimo quando não há perfil de utilizador.
     *
     * @return prompt de sistema sem perfil
     */
    private static String buildSystemPromptWithoutProfile() {
        StringBuilder prompt = new StringBuilder();
        prompt.append(buildSystemInstructions());
        prompt.append(buildDynamicDateTime());
        return prompt.toString().trim();
    }
}
