package ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * JsonActionParser — converte a resposta JSON do modelo numa lista de
 * {@link ParsedAction}.
 * <p>
 * <b>Defensivo por conceção.</b> Este parser é escrito à mão (sem dependência
 * de biblioteca de JSON) e assume que o modelo pode devolver JSON imperfeito.
 * Qualquer erro de parsing faz com que a ação inválida seja simplesmente
 * ignorada — nunca lançada, nunca executada. Isto garante que o modelo nunca
 * consegue produzir uma ação perigosa ou inexecutável através de JSON malformado.
 * </p>
 * <p>
 * Schema esperado (um array de objetos):
 * </p>
 * <pre>{@code
 * [
 *   {
 *     "action": "CREATE_ENTITY",
 *     "entity": "TASK",
 *     "fields": { "title": "Preparar apresentação", "deadline": "2026-09-06" },
 *     "relationships": ["AETHER"],
 *     "target": "",
 *     "reason": "O utilizador pediu para criar a tarefa."
 *   }
 * ]
 * }</pre>
 *
 * @author AETHER
 */
public final class JsonActionParser {

    private static final Logger LOGGER = Logger.getLogger(JsonActionParser.class.getName());

    private JsonActionParser() {
        // Classe de utilitário estático.
    }

    /**
     * Faz o parse da resposta JSON numa lista de ações.
     *
     * @param json a resposta do modelo
     * @return lista de ações extraídas (vazia se nenhuma ou inválida)
     */
    public static List<ParsedAction> parse(String json) {
        List<ParsedAction> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        String array = findOuterArray(json);
        if (array == null) {
            return out;
        }
        List<String> objects = splitTopLevelObjects(array);
        int rejected = 0;
        for (String obj : objects) {
            ParsedAction a = parseObject(obj);
            if (a != null) {
                out.add(a);
            } else {
                rejected++;
                // Diagnóstico: regista que um objeto do JSON do modelo foi
                // rejeitado pelo parser (action/entity em falta, JSON malformado,
                // etc.) — sem perda silenciosa.
                LOGGER.warning("PARSER_REJECTED: objeto JSON inválido/ignorado: "
                        + truncate(obj, 120));
            }
        }
        if (objects.size() != out.size()) {
            LOGGER.info("JsonActionParser: objetos recebidos=" + objects.size()
                    + ", parseados=" + out.size() + ", rejeitados=" + rejected + ".");
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    /** Encontra o conteúdo do array de topo mais externo {@code [ ... ]}. */
    private static String findOuterArray(String json) {
        int start = json.indexOf('[');
        if (start < 0) return null;
        int end = json.lastIndexOf(']');
        if (end <= start) return null;
        return json.substring(start + 1, end);
    }

    /** Divide o conteúdo de um array em substrings de objetos de topo {@code { ... }}. */
    private static List<String> splitTopLevelObjects(String arrayContent) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int objStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    out.add(arrayContent.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return out;
    }

    /** Faz o parse de um objeto de ação individual. Devolve {@code null} se inválido. */
    private static ParsedAction parseObject(String obj) {
        if (obj == null || obj.isBlank()) return null;
        String action = extractStringValue(obj, "action");
        String entity = extractStringValue(obj, "entity");
        String target = extractStringValue(obj, "target");
        String reason = extractStringValue(obj, "reason");
        Map<String, String> fields = extractStringMap(obj, "fields");
        List<String> relationships = extractStringArray(obj, "relationships");
        // Campos enriquecidos (note understanding). Todos opcionais e defensivos:
        // se o modelo não os fornecer, ficam vazios/zero — a proposta segue
        // como SUGGESTED com confiança por defeito, sem nunca saltar a aprovação.
        String classification = extractStringValue(obj, "classification");
        if (classification == null) classification = extractStringValue(obj, "class");
        String evidence = extractStringValue(obj, "evidence");
        double confidence = extractNumberValue(obj, "confidence");
        if (action == null || action.isBlank() || entity == null || entity.isBlank()) {
            return null;
        }
        return new ParsedAction(action.trim(), entity.trim(), fields, relationships,
                target, reason, classification, confidence, evidence);
    }

    /**
     * Extrai um valor numérico de um campo {@code "key":0.96}. Devolve 0 se
     * não existir ou não for um número válido. Usado para {@code confidence}.
     */
    static double extractNumberValue(String obj, String key) {
        String raw = extractRawValue(obj, key);
        if (raw == null) return 0;
        raw = raw.trim();
        if (raw.isEmpty()) return 0;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extrai o valor string de um campo {@code "key":"value"} de um objeto JSON.
     * Devolve o valor sem escapes, ou {@code null} se não existir.
     */
    static String extractStringValue(String obj, String key) {
        String raw = extractRawValue(obj, key);
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty() || raw.charAt(0) != '"') return null;
        return unescapeString(raw);
    }

    /** Extrai o valor cru (não interpretado) a seguir a {@code "key":}. */
    static String extractRawValue(String obj, String key) {
        String quotedKey = "\"" + key + "\"";
        int kidx = indexOfKey(obj, quotedKey);
        if (kidx < 0) return null;
        // avança para depois da chave
        int i = kidx + quotedKey.length();
        // salta espaços e ':'
        while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == ':' || obj.charAt(i) == '\t' || obj.charAt(i) == '\n' || obj.charAt(i) == '\r')) {
            i++;
        }
        if (i >= obj.length()) return null;
        // o valor é uma string (mantém as aspas; extractStringValue faz o unescape)
        if (obj.charAt(i) == '"') {
            return readQuotedString(obj, i);
        }
        // valor numérico (true/false/literal) — lê até à próxima vírgula/chave
        int j = i;
        while (j < obj.length()
                && obj.charAt(j) != ','
                && obj.charAt(j) != '}'
                && obj.charAt(j) != '\n'
                && obj.charAt(j) != '\r') {
            j++;
        }
        return obj.substring(i, j).trim();
    }

    /** Encontra a chave apenas fora de strings (para não confundir com conteúdo). */
    private static int indexOfKey(String obj, String quotedKey) {
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i <= obj.length() - quotedKey.length(); i++) {
            char c = obj.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                // tenta match da chave a partir de i
                if (obj.startsWith(quotedKey, i)) return i;
                inString = true;
            }
        }
        return -1;
    }

    /** Lê uma string JSON com aspas, devolvendo o conteúdo incluindo as aspas. */
    private static String readQuotedString(String obj, int start) {
        // start aponta para a aspa inicial
        int i = start + 1;
        boolean escape = false;
        StringBuilder sb = new StringBuilder("\"");
        while (i < obj.length()) {
            char c = obj.charAt(i);
            sb.append(c);
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            }
            i++;
        }
        return sb.toString();
    }

    /** Remove as aspas exteriores e desfaz escapes simples. */
    private static String unescapeString(String quoted) {
        if (quoted == null || quoted.length() < 2) return "";
        String inner = quoted.substring(1, quoted.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(next);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extrai um sub-objeto {@code "key":{ ... }} como um mapa de strings. */
    static Map<String, String> extractStringMap(String obj, String key) {
        Map<String, String> map = new LinkedHashMap<>();
        String subObject = extractSubObject(obj, key);
        if (subObject == null) return map;
        // Itera todas as chaves no sub-objeto.
        int idx = 0;
        while (true) {
            int kStart = findNextKeyStart(subObject, idx);
            if (kStart < 0) break;
            int keyEnd = subObject.indexOf('"', kStart + 1);
            if (keyEnd < 0) break;
            String fieldKey = unescapeString("\"" + subObject.substring(kStart + 1, keyEnd) + "\"");
            String raw = extractRawValue(subObject, fieldKey);
            if (raw != null && !raw.trim().isEmpty() && raw.trim().charAt(0) == '"') {
                String val = unescapeString(raw.trim());
                map.put(fieldKey, val);
            }
            idx = keyEnd + 1;
        }
        return map;
    }

    /** Extrai um array de strings {@code "key":[ "a", "b" ]}. */
    static List<String> extractStringArray(String obj, String key) {
        List<String> out = new ArrayList<>();
        String quotedKey = "\"" + key + "\"";
        int kidx = indexOfKey(obj, quotedKey);
        if (kidx < 0) return out;
        int i = kidx + quotedKey.length();
        while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == ':' || obj.charAt(i) == '\t' || obj.charAt(i) == '\n' || obj.charAt(i) == '\r')) i++;
        if (i >= obj.length() || obj.charAt(i) != '[') return out;
        int arrStart = i + 1;
        int end = obj.indexOf(']', arrStart);
        if (end < 0) return out;
        String inner = obj.substring(arrStart, end);
        // divide por vírgulas de topo (fora de strings)
        int from = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int j = 0; j < inner.length(); j++) {
            char c = inner.charAt(j);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                int close = inner.indexOf('"', j + 1);
                if (close > j) {
                    out.add(unescapeString(inner.substring(j, close + 1)));
                    j = close;
                    inStr = false;
                }
            }
        }
        return out;
    }

    /** Extrai o sub-objeto JSON {@code { ... }} que é valor de {@code key}. */
    private static String extractSubObject(String obj, String key) {
        String quotedKey = "\"" + key + "\"";
        int kidx = indexOfKey(obj, quotedKey);
        if (kidx < 0) return null;
        int i = kidx + quotedKey.length();
        while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == ':' || obj.charAt(i) == '\t' || obj.charAt(i) == '\n' || obj.charAt(i) == '\r')) i++;
        if (i >= obj.length() || obj.charAt(i) != '{') return null;
        int start = i;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int j = start; j < obj.length(); j++) {
            char c = obj.charAt(j);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return obj.substring(start, j + 1);
            }
        }
        return null;
    }

    /** Encontra o início da próxima chave (aspas de abertura) a partir de idx. */
    private static int findNextKeyStart(String obj, int idx) {
        boolean inStr = false;
        boolean esc = false;
        for (int i = idx; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') return i;
        }
        return -1;
    }
}
