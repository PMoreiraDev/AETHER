package ai;

import domain.entities.AetherEntity;
import util.OllamaService;
import util.VaultIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * NoteUnderstandingExtractor — implementa {@link ActionExtractor} orientado à
 * <b>compreensão semântica da nota inteira</b>.
 * <p>
 * Recebe o texto livre de uma nota e pede ao modelo que compreenda
 * semanticamente TODO o seu conteúdo: identificar TODAS as entidades
 * relevantes — PERSON, PROJECT, TASK, EVENT — sem qualquer limite artificial,
 * com sourceQuote literal, classificação epistémica (FACT / INFERENCE /
 * SUGGESTION) e confiança. O modelo produz JSON estruturado; o
 * {@link JsonActionParser} é defensivo (JSON imperfeito → nenhuma ação). A IA
 * nunca escreve no vault — só produz propostas que o utilizador aprova.
 * </p>
 *
 * @author AETHER
 */
public final class NoteUnderstandingExtractor implements ActionExtractor {

    private static final Logger LOGGER = Logger.getLogger(NoteUnderstandingExtractor.class.getName());

    private final String modelId;
    private final Supplier<List<OllamaService.ChatMessage>> historySupplier;

    private static final int MAX_EXISTING_NAMES = 40;

    public NoteUnderstandingExtractor(String modelId,
                                     Supplier<List<OllamaService.ChatMessage>> historySupplier) {
        this.modelId = modelId;
        this.historySupplier = historySupplier == null ? List::of : historySupplier;
    }

    @Override
    public List<ParsedAction> extract(String noteText, String sourceLabel) {
        if (modelId == null || modelId.isBlank()) {
            return List.of();
        }
        if (noteText == null || noteText.isBlank()) {
            return List.of();
        }
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        String now = util.TemporalParser.nowDescription(zone);
        List<String> existingPeople = existingNames(VaultIndex.getInstance().people());
        List<String> existingProjects = existingNames(VaultIndex.getInstance().projects());

        List<OllamaService.ChatMessage> messages = new ArrayList<>();
        messages.add(new OllamaService.ChatMessage("system", buildInstructions()));
        messages.add(new OllamaService.ChatMessage("user",
                buildPrompt(noteText, sourceLabel, now, existingPeople, existingProjects)));

        String json = OllamaService.chatComplete(modelId, messages, null, true, 3);
        if (json == null || json.isBlank()) {
            LOGGER.warning("NoteUnderstandingExtractor: o modelo nao devolveu conteudo (timeout ou servidor indisponivel).");
            return List.of();
        }
        // Diagnostico: tamanho da resposta bruta. Ajuda a detetar truncamento
        // (respostas curtas com poucas entidades podem indicar num_predict baixo).
        LOGGER.info("NoteUnderstandingExtractor: resposta bruta do Ollama tem "
                + json.length() + " caracteres.");
        List<ParsedAction> parsed = JsonActionParser.parse(json);
        LOGGER.info("NoteUnderstandingExtractor: entidades/acoes parseadas = " + parsed.size()
                + " (de uma resposta de " + json.length() + " chars).");
        return parsed;
    }

    /** Instrucoes do extrator — schema JSON exato + regras + 4 exemplos concretos. */
    public static String buildInstructions() {
        return """
                You are AETHER's note understanding engine. You read a personal note written in natural language \
                (Portuguese or English) and you produce a STRICT JSON object describing ALL the structured knowledge \
                that can be inferred from it. You never write to any database. You only PROPOSE.

                === CORE MISSION ===
                Analyze the ENTIRE note semantically and identify EVERY relevant entity of EVERY type. There is NO \
                LIMIT on the number of entities. If the note contains 5 people, 3 projects, 7 tasks and 4 events, \
                output all 19. Do NOT stop after finding a few. Do NOT privilege any type over another. PERSON, \
                PROJECT, TASK and EVENT are equally important.

                === ENTITY TYPES (use these exact "entity" values) ===
                - PERSON   : a person mentioned in the note.
                - PROJECT  : a project, product, initiative or endeavour mentioned.
                - TASK     : an action item, to-do, responsibility or piece of work to be done.
                - EVENT    : a meeting, appointment, occurrence or scheduled happening.

                === MANDATORY FIELDS ON EVERY ENTITY (no exceptions) ===
                - "action": always "CREATE_ENTITY" (or "UPDATE_ENTITY" if the name matches an existing entity listed below).
                - "entity": one of PERSON | PROJECT | TASK | EVENT.
                - "fields": a JSON object with the canonical fields for that type (see below).
                - "classification": one of "FACT" (explicitly stated in the note), "INFERENCE" (strongly implied), \
                  "SUGGESTION" (possible but uncertain). Never silently turn an INFERENCE or SUGGESTION into a FACT.
                - "confidence": a number in [0,1] reflecting how clearly the note supports this entity.
                - "evidence": a SHORT VERBATIM QUOTE copied LITERALY from the note, between quotes, that justifies \
                  this entity. This field is MANDATORY. It is PROHIBITED to invent or paraphrase a quote that does not \
                  appear literally in the note text. If you cannot find a literal quote, do NOT include the entity.

                === CANONICAL FIELDS PER TYPE (use these exact keys in "fields") ===
                - PERSON : "name", "about" (role/description in context), "occupation", "birthday"(yyyy-MM-dd)
                - PROJECT: "name", "description", "deadline"(yyyy-MM-dd), "status"
                - TASK   : "title", "description", "deadline"(yyyy-MM-dd HH:mm), "status"(TODO|IN_PROGRESS|DONE), "priority"(NONE|LOW|MEDIUM|HIGH)
                - EVENT  : "title", "description", "location", "start"(yyyy-MM-dd HH:mm), "end"(yyyy-MM-dd HH:mm)
                NOTE: PERSON and PROJECT use the "name" field. TASK and EVENT use the "title" field. This is mandatory.

                === DATES, DEADLINES AND TIMES ===
                Resolve relative dates ("ontem", "hoje", "amanha", "sexta-feira", "proxima terca", "daqui a duas \
                semanas") to concrete ISO values using the current date/time provided in the prompt. Use date \
                "yyyy-MM-dd" and datetime "yyyy-MM-dd HH:mm". If a date is genuinely ambiguous (cannot be pinned to a \
                single day), OMIT the date field and set confidence low. Associate each date/time/deadline with the \
                entity it refers to.

                === RELATIONS ===
                Also identify relations between entities (e.g. a person WORKS_ON a project, is RESPONSIBLE_FOR a task, \
                a task PART_OF a project). Put them in the "relations" array. Each relation MUST have "sourceQuote" \
                (literal), "confidence" and a relation type (e.g. WORKS_ON, RESPONSIBLE_FOR, PART_OF, ATTENDS).

                === DUPLICATES ===
                If a name matches an existing entity listed in the prompt, propose "UPDATE_ENTITY" with "target" set to \
                the existing name, rather than creating a duplicate.

                === OUTPUT SCHEMA (a single JSON object; do not limit the arrays) ===
                {
                  "entities": [
                    {
                      "action": "CREATE_ENTITY",
                      "entity": "PERSON",
                      "fields": {"name": "Joao", "about": "Colega de trabalho"},
                      "target": "",
                      "relationships": ["AETHER"],
                      "classification": "FACT",
                      "confidence": 0.97,
                      "evidence": "Falei com o Joao, meu colega de trabalho, sobre o AETHER."
                    },
                    {
                      "action": "CREATE_ENTITY",
                      "entity": "PROJECT",
                      "fields": {"name": "AETHER", "description": "Projeto de IA local"},
                      "target": "",
                      "relationships": [],
                      "classification": "FACT",
                      "confidence": 0.98,
                      "evidence": "O AETHER e um projeto de IA local."
                    },
                    {
                      "action": "CREATE_ENTITY",
                      "entity": "TASK",
                      "fields": {"title": "Terminar a integracao do Ollama", "deadline": "2026-09-12 00:00"},
                      "target": "",
                      "relationships": ["AETHER"],
                      "classification": "FACT",
                      "confidence": 0.95,
                      "evidence": "Precisamos de terminar a integracao do Ollama ate sexta-feira."
                    },
                    {
                      "action": "CREATE_ENTITY",
                      "entity": "EVENT",
                      "fields": {"title": "Reuniao para rever o progresso", "start": "2026-09-09 15:00"},
                      "target": "",
                      "relationships": ["AETHER"],
                      "classification": "FACT",
                      "confidence": 0.93,
                      "evidence": "Na terca-feira temos uma reuniao as 15h para rever o progresso."
                    }
                  ],
                  "relations": [
                    {
                      "action": "LINK_ENTITIES",
                      "entity": "PERSON",
                      "fields": {},
                      "target": "Joao",
                      "relationships": ["AETHER"],
                      "classification": "INFERENCE",
                      "confidence": 0.82,
                      "evidence": "sobre o projeto AETHER"
                    }
                  ]
                }

                === SAFETY ===
                - Output ONLY the JSON object. No prose, no markdown, no code fences.
                - NEVER invent entities, dates, deadlines, responsibilities, relations or quotes not supported by the note.
                - If the note does not support enough information for an item, DO NOT include that item. Prefer omission \
                  over invention.
                - Every entity MUST have a literal "evidence" quote that appears verbatim in the note.
                """;
    }

    /** Prompt do extrator com a nota, a fonte, a data atual e as entidades existentes. */
    public static String buildPrompt(String noteText, String sourceLabel, String now,
                              List<String> existingPeople, List<String> existingProjects) {
        return "Current date/time: " + now + " (Europe/Lisbon). Use this to resolve relative dates.\n\n"
                + "Existing people (use these exact names to avoid duplicates): "
                + (existingPeople.isEmpty() ? "(none yet)" : String.join(", ", existingPeople)) + "\n"
                + "Existing projects (use these exact names to avoid duplicates): "
                + (existingProjects.isEmpty() ? "(none yet)" : String.join(", ", existingProjects)) + "\n\n"
                + "Note source: " + nullSafe(sourceLabel) + "\n\n"
                + "=== NOTE TEXT (this is the ONLY source of truth; every evidence quote MUST be copied literally from here) ===\n"
                + nullSafe(noteText) + "\n\n"
                + "=== END OF NOTE ===\n\n"
                + "Understand the ENTIRE note and output the JSON object described in the instructions. "
                + "Extract ALL entities of ALL types (PERSON, PROJECT, TASK, EVENT) — there is no limit. "
                + "Every entity must include a literal evidence quote. Do not invent anything.";
    }

    private static List<String> existingNames(List<? extends AetherEntity> entities) {
        if (entities == null || entities.isEmpty()) return List.of();
        return entities.stream()
                .map(NoteUnderstandingExtractor::displayName)
                .filter(n -> n != null && !n.isBlank())
                .limit(MAX_EXISTING_NAMES)
                .collect(Collectors.toList());
    }

    private static String displayName(AetherEntity e) {
        return switch (e) {
            case domain.entities.Person p -> p.getName();
            case domain.entities.Project p -> p.getName();
            default -> "";
        };
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
