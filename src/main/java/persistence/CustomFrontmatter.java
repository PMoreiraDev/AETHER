package persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CustomFrontmatter — preservação de metadados definidos pelo utilizador nos
 * ficheiros Markdown de entidades.
 * <p>
 * O AETHER é autoritário para os campos que modela (whitelist por tipo); todo
 * o resto do frontmatter pertence ao utilizador (ex.: {@code tags},
 * {@code client}, {@code priority} num projeto) e DEVE sobreviver a
 * re-projeções, renomes e sincronizações. Esta classe define os conjuntos de
 * chaves AETHER por tipo, extrai as chaves desconhecidas e serializa-as como
 * JSON ordenado para a coluna {@code custom_frontmatter} do repositório.
 * </p>
 * <p>
 * Sem dependências externas (congelamento de arquitetura): codificador e
 * descodificador JSON mínimos, apenas para mapas planos string→string.
 * Falhas de descodificação degradam para mapa vazio — nunca lançam exceção.
 * </p>
 *
 * @author AETHER
 */
final class CustomFrontmatter {

    private CustomFrontmatter() {
        // Utility class.
    }

    /** Chaves AETHER comuns a todos os tipos de entidade. */
    private static final Set<String> COMMON_OWNED = Set.of(
            "type", "id", "created", "updated");

    private static final Set<String> PERSON_OWNED = Set.of(
            "name", "birthday", "occupation", "about");

    private static final Set<String> EVENT_OWNED = Set.of(
            "title", "start", "end", "location", "description");

    private static final Set<String> TASK_OWNED = Set.of(
            "title", "deadline", "status", "priority", "description");

    private static final Set<String> NOTE_OWNED = Set.of(
            "title", "source", "original_text");

    private static final Set<String> PROJECT_OWNED = Set.of(
            "name", "deadline", "status", "description");

    /**
     * As chaves AETHER do tipo de entidade dado (sempre incluem as comuns).
     * Uma chave fora deste conjunto é do utilizador e é preservada.
     */
    static Set<String> ownedKeys(String type) {
        Set<String> specific = switch (type == null ? "" : type) {
            case "person" -> PERSON_OWNED;
            case "event" -> EVENT_OWNED;
            case "task" -> TASK_OWNED;
            case "note" -> NOTE_OWNED;
            case "project" -> PROJECT_OWNED;
            default -> Set.of();
        };
        var all = new java.util.HashSet<>(COMMON_OWNED);
        all.addAll(specific);
        return all;
    }

    /**
     * Extrai, pela ordem original, as chaves não-AETHER de um frontmatter
     * completo. As chaves AETHER são ignoradas — o utilizador nunca as pode
     * sobrepor por esta via.
     */
    static LinkedHashMap<String, String> extract(String type, Map<String, String> frontmatter) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (frontmatter == null) {
            return out;
        }
        Set<String> owned = ownedKeys(type);
        for (Map.Entry<String, String> e : frontmatter.entrySet()) {
            // Valores vazios são preservados: "reviewed:" (vazio) é um campo
            // do utilizador tão válido como qualquer outro.
            if (e.getKey() != null && !owned.contains(e.getKey())
                    && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // JSON minimal (flat string → string, ordem preservada)
    // ------------------------------------------------------------------

    /** Serializa um mapa (possivelmente vazio) como objeto JSON plano. */
    static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * Descodifica um objeto JSON plano string→string. Tolerante a falhas:
     * {@code null}, vazio ou malformado devolvem mapa vazio.
     */
    static LinkedHashMap<String, String> fromJson(String json) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        String s = json.trim();
        if (s.length() < 2 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
            return out;
        }
        int i = 1;
        while (i < s.length() - 1) {
            // key
            int[] pos = new int[1];
            String key = parseString(s, i, pos);
            if (key == null) {
                return out; // malformado: degradar para vazio
            }
            i = pos[0];
            while (i < s.length() - 1 && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= s.length() - 1 || s.charAt(i) != ':') {
                return out;
            }
            i++;
            while (i < s.length() - 1 && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            String value = parseString(s, i, pos);
            if (value == null) {
                return out;
            }
            i = pos[0];
            out.put(key, value);
            while (i < s.length() - 1 && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i < s.length() - 1 && s.charAt(i) == ',') {
                i++;
                while (i < s.length() - 1 && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            } else if (i < s.length() - 1) {
                return out; // separador inesperado
            }
        }
        return out;
    }

    /** Lê uma string JSON começada em {@code from}; devolve null se inválida. */
    private static String parseString(String s, int from, int[] endPos) {
        if (from >= s.length() || s.charAt(from) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = from + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                endPos[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 4 >= s.length()) {
                            return null;
                        }
                        try {
                            sb.append((char) Integer.parseInt(
                                    s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    }
                    default -> {
                        return null;
                    }
                }
            } else if (c < 0x20) {
                return null; // carácter de controlo não escapado
            } else {
                sb.append(c);
            }
            i++;
        }
        return null; // sem fecho
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
