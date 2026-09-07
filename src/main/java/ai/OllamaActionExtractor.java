package ai;

import domain.entities.ContextEntityType;
import util.OllamaService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * OllamaActionExtractor — implementa {@link ActionExtractor} com uma segunda
 * chamada (não-streaming, formato JSON) ao modelo local do Ollama.
 * <p>
 * Arquitetura de duas fases:
 * </p>
 * <ol>
 *   <li>Fase 1 — o controlador faz o streaming da resposta natural (conversa).</li>
 *   <li>Fase 2 — este extrator pede ao modelo que devolva apenas as ações
 *       estruturadas implícitas na conversa, em JSON. A resposta natural já
 *       foi mostrada; esta chamada serve só para detetar ações.</li>
 * </ol>
 * <p>
 * O modelo é instruído para devolver {@code []} quando não há ações claras,
 * para não propor ações a cada mensagem. Mesmo assim, o
 * {@link JsonActionParser} é defensivo: JSON imperfeito → nenhuma ação (seguro).
 * </p>
 *
 * @author AETHER
 */
public final class OllamaActionExtractor implements ActionExtractor {

    private final String modelId;
    private final Supplier<List<OllamaService.ChatMessage>> historySupplier;

    /**
     * Cria o extrator.
     *
     * @param modelId        modelo Ollama ativo
     * @param historySupplier fornece o histórico atual da conversa
     */
    public OllamaActionExtractor(String modelId, Supplier<List<OllamaService.ChatMessage>> historySupplier) {
        this.modelId = modelId;
        this.historySupplier = historySupplier == null ? List::of : historySupplier;
    }

    @Override
    public List<ParsedAction> extract(String userMessage, String assistantReply) {
        if (modelId == null || modelId.isBlank()) {
            return List.of();
        }
        if ((userMessage == null || userMessage.isBlank())
                && (assistantReply == null || assistantReply.isBlank())) {
            return List.of();
        }
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.ZonedDateTime current = java.time.ZonedDateTime.now(zone);
        String now = current.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String weekday = current.getDayOfWeek().toString();
        List<OllamaService.ChatMessage> messages = new ArrayList<>();
        messages.add(new OllamaService.ChatMessage("system", buildExtractionInstructions()));
        messages.add(new OllamaService.ChatMessage("user", buildExtractionPrompt(userMessage, assistantReply, now, weekday)));

        String json = OllamaService.chatComplete(modelId, messages, null, true);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JsonActionParser.parse(json);
    }

    /** Instruções do extrator — schema JSON exato + regras de segurança. */
    static String buildExtractionInstructions() {
        return """
                You are AETHER's action extraction layer. You receive a user message and the assistant's natural reply.
                You output ONLY a JSON array of structured actions. Most actions must correspond to an explicit user request, but PERSONAL MEMORY is special: when the user clearly states a fact/preferences about themselves (for example, "Eu estudo no ISEP", "sou programador", "prefiro respostas diretas"), propose an UPDATE_ENTITY on PROFILE. Do not create a profile entity.
                Do not treat third-party statements (for example, "O ISEP tem um curso interessante", "Rafael vive em Amarante") as a fact about the user. Return [] when there is no actionable personal memory or explicit entity action.

                Rules:
                - Output ONLY the JSON array. No prose, no markdown, no code fences.
                - Only include an action when the user's intent is explicit and unambiguous.
                - Never invent entities, dates, or relationships not present in the conversation.
                - Only extract a description/occupation/date/time when that information is clearly stated or unambiguously implied by the user's words. Never invent a description from a bare mention. For example, "Falei com o Manel" yields only name=Manel (no description); "O Manel é meu colega de trabalho" yields name=Manel and about=Colega de trabalho.
                - DELETE only when the user explicitly asked to delete/remove something.
                - Map entities exactly: PROFILE, PERSON, PROJECT, EVENT, TASK, NOTE.
                - Map actions exactly: CREATE_ENTITY, UPDATE_ENTITY, LINK_ENTITIES, UNLINK_ENTITIES, DELETE_ENTITY.
                - For personal memory use UPDATE_ENTITY + PROFILE and canonical fields: fullName, preferredName, birthDate(yyyy-MM-dd), about, occupation, studies, experience, skills, interests, objectives, preferences, projects, workStyle, location, inferredContext, profileSummary. Put only the newly stated information in the fields. Do not overwrite unrelated profile fields.
                - Personal memory examples for location: "Eu vivo em Amarante" -> PROFILE.location="Amarante"; "Moro no Porto" -> PROFILE.location="Porto"; "Agora vivo no Porto" (user previously lived in Amarante) -> PROFILE.location="Porto" (the proposal layer keeps history).
                - Personal memory is NOT limited to the word "eu" (I). First-person verb forms count even without the pronoun: "Estudo no ISEP" (studies=ISEP), "Sou programador" (occupation=Programador), "Gosto de futebol" (interests=futebol), "Quero aprender Rust" (objectives=aprender Rust), "Vivo em Amarante" (location=Amarante), "Estou a aprender Python" (skills=Python), "Prefiro trabalhar à noite" (preferences=trabalhar à noite), "O meu projeto chama-se AETHER" (projects=AETHER). The subject is the user whenever the verb is first-person.
                - Third-person statements are NOT about the user: "O João estuda no ISEP", "A Maria é engenheira", "Ele gosta de futebol", "Rafael vive em Amarante", "O ISEP tem um curso interessante" must NOT produce a PROFILE action — the subject is a third party, not the user. A PERSON field of the profile must never be filled from a statement about someone else.
                - A bare mention of an institution is NOT a personal fact: "O ISEP tem um curso interessante" -> [] (the user did not say they study there).
                - Speculation about the user is INFERENCE, never FACT: "Acho que vivo em Amarante" -> classification INFERENCE (not FACT), location=Amarante. Never silently present speculation as fact; never convert an INFERENCE into a FACT.
                - Family members stated by the user are PERSON entities, not profile fields: "Tenho um irmão chamado Rafael" -> CREATE_ENTITY PERSON {name: Rafael, about: Irmão do utilizador}.
                - If the personal memory has no canonical structured field (e.g. an activity, habit, or descriptive preference like "gosto de desenvolver aplicações desktop"), put it under the inferredContext field — NEVER under aiContext (aiContext is reserved for the user's own manual notes).
                - Examples: "Eu estudo no ISEP" -> PROFILE.studies="ISEP"; "Estudo no ISEP" -> PROFILE.studies="ISEP"; "Sou estudante de Engenharia Informática" -> PROFILE.studies="Engenharia Informática"; "Sou programador" -> PROFILE.occupation="Programador"; "Prefiro respostas diretas" -> PROFILE.preferences="respostas diretas"; "Gosto de desenvolver aplicações desktop" -> PROFILE.inferredContext="Gosta de desenvolver aplicações desktop"; "O João estuda no ISEP" -> [] (third party); "Rafael vive em Amarante" -> [] (third party, never PROFILE.location).
                - Resolve relative dates ("tomorrow", "next Friday", "amanhã", "sexta-feira") to concrete ISO dates using the current date provided in the prompt.
                - Dates and times in ISO format: date "yyyy-MM-dd", datetime "yyyy-MM-dd HH:mm".

                Canonical field names per entity (use these exact keys):
                - PERSON: name, about (the person's description/bio/role-in-context), occupation, birthday(yyyy-MM-dd)
                - PROJECT: name, description, deadline(yyyy-MM-dd), status
                - EVENT: title, description, location, start(yyyy-MM-dd HH:mm), end(yyyy-MM-dd HH:mm)
                - TASK: title, description, deadline(yyyy-MM-dd HH:mm), status(TODO|IN_PROGRESS|DONE), priority(NONE|LOW|MEDIUM|HIGH)
                - NOTE: content, original_text

                - 'relationships' is a list of existing entity NAMES to link (for LINK) or to relate a new entity to.
                - 'target' is the NAME/TITLE of an existing entity to update, delete or link from.
                - 'reason' is a short natural explanation of why the action is proposed.

                Schema (array of objects):
                [
                  {"action":"CREATE_ENTITY","entity":"TASK","fields":{"title":"...","deadline":"...","priority":"HIGH"},"relationships":["AETHER"],"target":"","reason":"..."}
                ]
                """;
    }

    /** Prompt do extrator com a conversa e a data atual. */
    static String buildExtractionPrompt(String userMessage, String assistantReply, String now, String weekday) {
        return "Current date/time: " + now + " (" + weekday + "). Use this to resolve relative dates.\n\n"
                + "User message: " + nullSafe(userMessage) + "\n\n"
                + "Assistant reply: " + nullSafe(assistantReply) + "\n\n"
                + "Extract the explicit actions the user asked for, as a JSON array. "
                + "Return [] if there are none.";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
