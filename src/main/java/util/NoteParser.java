package util;

import domain.entities.ParsedNote;
import session.UserSession;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NoteParser — extrai entidades de notas em linguagem natural usando o Ollama.
 * <p>
 * Recebe texto livre do utilizador (ex: "reunião com o joão sobre o projeto
 * aether as 10:00 de hoje") e envia-o ao modelo local com um prompt de
 * extração estruturada. O modelo retorna JSON com entidades, relações e
 * níveis de confiança.
 * </p>
 * <p>
 * O NoteParser usa a mesma fonte de data dinâmica do ContextManager
 * (system timezone) para resolver datas relativas.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class NoteParser {

    /** Logger. */
    private static final Logger LOGGER = Logger.getLogger(NoteParser.class.getName());

    /** Timezone do utilizador. */
    private static final ZoneId USER_TIMEZONE = ZoneId.systemDefault();

    /**
     * Construtor privado — classe utilitária.
     */
    private NoteParser() {
        // Classe utilitária.
    }

    /**
     * Faz o parse de uma nota em linguagem natural.
     * <p>
     * Envia o texto ao Ollama com um prompt de extração e retorna um
     * ParsedNote com as entidades extraídas. Este método é bloqueante e
     * deve ser chamado fora do fio da interface.
     * </p>
     *
     * @param text o texto em linguagem natural
     * @return o ParsedNote com as extrações, ou null se falhou
     */
    public static ParsedNote parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String modelId = UserSession.getInstance().getAppSettings().getActiveModelId();
        if (modelId == null || modelId.isBlank()) {
            LOGGER.warning("NoteParser: nenhum modelo configurado.");
            return null;
        }

        String prompt = buildExtractionPrompt(text.trim());

        String response = OllamaService.chat(modelId, prompt);
        if (response == null || response.isBlank()) {
            LOGGER.warning("NoteParser: resposta vazia do Ollama.");
            return null;
        }

        return parseJsonResponse(response, text.trim());
    }

    /**
     * Faz o parse de uma nota em linguagem natural com callback de streaming.
     *
     * @param text o texto em linguagem natural
     * @param onResult chamado quando o parse termina (no fio de chamada)
     */
    public static void parseAsync(String text, Consumer<ParsedNote> onResult) {
        new Thread(() -> {
            ParsedNote result = parse(text);
            onResult.accept(result);
        }, "aether-noteparser").start();
    }

    /**
     * Constrói o prompt de extração para o Ollama.
     *
     * @param noteText o texto da nota do utilizador
     * @return o prompt completo
     */
    private static String buildExtractionPrompt(String noteText) {
        ZonedDateTime now = ZonedDateTime.now(USER_TIMEZONE);
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

        return """
                You are AETHER's note parser. Extract structured information from the user's natural language note.

                CURRENT DATE: %s
                CURRENT TIME: %s
                TIMEZONE: Europe/Lisbon

                RULES:
                - Extract people, projects, events, tasks, dates, times, locations.
                - Resolve relative dates (today, tomorrow, next week) using the current date above.
                - Mark each extraction as "confirmed" (explicitly stated) or "inferred" (guessed from context).
                - Return ONLY valid JSON, no other text, no markdown code blocks.
                - If you cannot extract a field, omit it or set it to null.

                NOTE: "%s"

                Return JSON with this structure:
                {
                  "type": "event" | "task" | "note" | "person" | "project",
                  "title": "short descriptive title",
                  "date": "YYYY-MM-DD" or null,
                  "time": "HH:mm" or null,
                  "location": "string or null",
                  "people": ["Name1", "Name2"],
                  "projects": ["Project1"],
                  "tags": ["tag1", "tag2"],
                  "relationships": [
                    {"from": "Name1", "to": "Project1", "type": "related_to"}
                  ],
                  "confidence": {
                    "people": "confirmed" | "inferred",
                    "projects": "confirmed" | "inferred",
                    "time": "confirmed" | "inferred"
                  },
                  "summary": "brief description of what was extracted"
                }
                """.formatted(
                now.format(dateFmt),
                now.format(timeFmt),
                escapeForPrompt(noteText)
        );
    }

    /**
     * Faz o parse da resposta JSON do Ollama para um ParsedNote.
     * <p>
     * Usa parsing manual de JSON (sem bibliotecas externas), seguindo o
     * mesmo padrão do OllamaService.
     * </p>
     *
     * @param json a resposta JSON
     * @param originalText o texto original do utilizador
     * @return o ParsedNote, ou null se inválido
     */
    private static ParsedNote parseJsonResponse(String json, String originalText) {
        ParsedNote note = new ParsedNote();
        note.setOriginalText(originalText);

        // Limpar markdown code blocks se existirem
        String clean = json.trim();
        if (clean.startsWith("```")) {
            int start = clean.indexOf('\n');
            int end = clean.lastIndexOf("```");
            if (start > 0 && end > start) {
                clean = clean.substring(start + 1, end).trim();
            }
        }

        note.setType(extractStringField(clean, "type", "note"));
        note.setTitle(extractStringField(clean, "title", ""));
        note.setDate(extractStringFieldOrNull(clean, "date"));
        note.setTime(extractStringFieldOrNull(clean, "time"));
        note.setLocation(extractStringFieldOrNull(clean, "location"));

        note.setPeople(extractStringArray(clean, "people"));
        note.setProjects(extractStringArray(clean, "projects"));
        note.setTags(extractStringArray(clean, "tags"));

        note.setConfidence(extractConfidenceMap(clean));
        note.setRelationships(extractRelationships(clean));

        // Gerar resumo se não vier no JSON
        String summary = extractStringField(clean, "summary", "");
        if (summary.isBlank()) {
            summary = generateSummary(note);
        }
        note.setSummary(summary);

        LOGGER.info(() -> "NoteParser: " + note);
        return note;
    }

    /**
     * Gera um resumo do que foi extraído, para mostrar ao utilizador.
     */
    private static String generateSummary(ParsedNote note) {
        List<String> parts = new ArrayList<>();

        if (!note.getPeople().isEmpty()) {
            parts.add("Pessoa(s): " + String.join(", ", note.getPeople()));
        }
        if (!note.getProjects().isEmpty()) {
            parts.add("Projeto(s): " + String.join(", ", note.getProjects()));
        }
        if (note.getDate() != null) {
            String dt = note.getDate();
            if (note.getTime() != null) {
                dt += " " + note.getTime();
            }
            parts.add("Data: " + dt);
        }
        if (note.getLocation() != null) {
            parts.add("Local: " + note.getLocation());
        }
        if (!note.getTags().isEmpty()) {
            parts.add("Tags: " + String.join(", ", note.getTags()));
        }

        if (parts.isEmpty()) {
            return "Nenhuma entidade extraída.";
        }
        return String.join(" | ", parts);
    }

    // ------------------------------------------------------------------
    // JSON parsing helpers (manual, sem bibliotecas externas)
    // ------------------------------------------------------------------

    /**
     * Extrai um campo string de um JSON.
     */
    private static String extractStringField(String json, String key, String defaultValue) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return defaultValue;
        }
        int start = idx + marker.length();
        // Saltar espaços
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        if (start >= json.length()) {
            return defaultValue;
        }
        if (json.charAt(start) == '"') {
            start++;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(next);
                    }
                    i++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString().trim();
        }
        // Valor sem aspas (null, número, etc.)
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != '\n') {
            end++;
        }
        String value = json.substring(start, end).trim();
        if ("null".equals(value)) {
            return defaultValue;
        }
        return value;
    }

    /**
     * Extrai um campo string que pode ser null.
     */
    private static String extractStringFieldOrNull(String json, String key) {
        String value = extractStringField(json, key, "");
        return value.isBlank() ? null : value;
    }

    /**
     * Extrai um array de strings de um JSON.
     */
    private static List<String> extractStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return result;
        }
        int bracketStart = json.indexOf('[', idx);
        if (bracketStart < 0) {
            return result;
        }
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd < 0) {
            return result;
        }
        String arrayContent = json.substring(bracketStart + 1, bracketEnd);

        // Extrair cada string entre aspas
        int pos = 0;
        while (pos < arrayContent.length()) {
            int quoteStart = arrayContent.indexOf('"', pos);
            if (quoteStart < 0) break;
            int quoteEnd = quoteStart + 1;
            StringBuilder sb = new StringBuilder();
            for (int i = quoteStart + 1; i < arrayContent.length(); i++) {
                char c = arrayContent.charAt(i);
                if (c == '\\' && i + 1 < arrayContent.length()) {
                    char next = arrayContent.charAt(i + 1);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(next);
                    }
                    i++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            if (!sb.toString().isBlank()) {
                result.add(sb.toString().trim());
            }
            pos = quoteEnd + sb.length() + 2;
            if (pos >= arrayContent.length()) break;
        }
        return result;
    }

    /**
     * Extrai o mapa de confiança do JSON.
     */
    private static Map<String, String> extractConfidenceMap(String json) {
        Map<String, String> result = new HashMap<>();
        String marker = "\"confidence\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return result;
        }
        int braceStart = json.indexOf('{', idx);
        if (braceStart < 0) {
            return result;
        }
        int braceEnd = json.indexOf('}', braceStart);
        if (braceEnd < 0) {
            return result;
        }
        String content = json.substring(braceStart + 1, braceEnd);
        for (String pair : content.split(",")) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx > 0) {
                String key = pair.substring(0, colonIdx).trim().replace("\"", "");
                String value = pair.substring(colonIdx + 1).trim().replace("\"", "");
                if (!key.isBlank()) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    /**
     * Extrai a lista de relações do JSON.
     */
    private static List<Map<String, String>> extractRelationships(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        String marker = "\"relationships\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return result;
        }
        int bracketStart = json.indexOf('[', idx);
        if (bracketStart < 0) {
            return result;
        }
        // Encontrar o bracket de fecho correspondente
        int depth = 0;
        int bracketEnd = -1;
        for (int i = bracketStart; i < json.length(); i++) {
            if (json.charAt(i) == '[') depth++;
            else if (json.charAt(i) == ']') {
                depth--;
                if (depth == 0) {
                    bracketEnd = i;
                    break;
                }
            }
        }
        if (bracketEnd < 0) {
            return result;
        }
        String arrayContent = json.substring(bracketStart + 1, bracketEnd);

        // Extrair cada objeto {from, to, type}
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = arrayContent.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = arrayContent.substring(objStart + 1, objEnd);
            Map<String, String> rel = new HashMap<>();
            for (String pair : obj.split(",")) {
                int colonIdx = pair.indexOf(':');
                if (colonIdx > 0) {
                    String key = pair.substring(0, colonIdx).trim().replace("\"", "");
                    String value = pair.substring(colonIdx + 1).trim().replace("\"", "");
                    if (!key.isBlank()) {
                        rel.put(key, value);
                    }
                }
            }
            if (!rel.isEmpty()) {
                result.add(rel);
            }
            pos = objEnd + 1;
        }
        return result;
    }

    /**
     * Escapa texto para uso dentro do prompt.
     */
    private static String escapeForPrompt(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
