package ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * PersonalMemoryGuard — validação determinística EM CÓDIGO (não em prompt) das
 * propostas de memória pessoal (UPDATE_ENTITY + PROFILE).
 * <p>
 * Anti-alucinação (spec #5): um valor de perfil só pode ser proposto se estiver
 * <b>ancorado</b> numa frase em 1ª pessoa da mensagem do utilizador. Isto
 * bloqueia em código, independentemente do que o modelo diga:
 * </p>
 * <ul>
 *   <li>"O ISEP tem um curso interessante." → NÃO pode propor
 *       {@code studies=ISEP} — a frase é sobre uma terceira parte (o ISEP),
 *       sem marcador de 1ª pessoa.</li>
 *   <li>"Rafael vive em Amarante." → NÃO pode propor {@code location=Amarante}
 *       — o sujeito é terceiro, não o utilizador.</li>
 * </ul>
 * <p>
 * O guard é tolerante a reformulação leve pelo modelo (ex. o valor
 * "Programador" para "sou programador"; "Gosta de X" para "gosto de X"):
 * a âncora é verificada por sobreposição de palavras de conteúdo, não por
 * igualdade literal. Valores que não aparecem na mensagem de forma nenhuma
 * são rejeitados — a IA não pode inventar valores.
 * </p>
 * <p>
 * Aplica-se a TODAS as fontes de propostas de perfil (Ollama do chat,
 * compreensão de notas, fallback heurístico), no {@link AiActionOrchestrator},
 * antes da criação da proposta. A classificação epistémica
 * (FACT/INFERENCE/SUGGESTION) nunca é alterada aqui (spec #8) — apenas a
 * admissão da proposta é decidida.
 * </p>
 *
 * @author AETHER
 */
public final class PersonalMemoryGuard {

    private static final Logger LOGGER = Logger.getLogger(PersonalMemoryGuard.class.getName());

    /**
     * Marcadores de 1ª pessoa (PT + EN). A frase precisa de conter UM destes
     * para que o valor nela contado se considere uma afirmação sobre o
     * próprio utilizador.
     */
    private static final String[] FIRST_PERSON_MARKERS = {
            // pronomes/determinantes
            "eu ", " meu ", " minha ", " meus ", " minhas ", "migo",
            // verbos 1ª pessoa (sem pronome)
            "estudo", "frequento", "sou", "estou", "trabalho", "vivo", "moro",
            "gosto", "adoro", "aprecio", "prefiro", "quero", "pretendo",
            "desejo", "planeio", "tenho", "aprendo", "sei", "domino",
            "faço", "chamo", "graduei", "licenciei", "mestr", "nasci",
            // inglês
            "i ", "i'm", "i am", "my ", "i live", "i study", "i work", "i like"
    };

    /** Palavras de conteúdo irrelevantes para a sobreposição. */
    private static final Set<String> CONTENT_STOPWORDS = Set.of(
            "que", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
            "um", "uma", "the", "a", "an", "of", "in", "at", "on", "para", "com",
            "como", "por", "aos", "às", "à", "ao", "os", "as", "sobre");

    /** Sobreposição mínima de palavras de conteúdo (0..1). */
    private static final double MIN_OVERLAP = 0.6;

    private PersonalMemoryGuard() {
        // Utilitário estático.
    }

    /**
     * Comando explícito de atualização de perfil (spec #6): "Atualiza o meu
     * perfil: estudos ISEP", "Define a minha localização: Lisboa". Quando o
     * utilizador dá uma ordem explícita, a âncora de 1ª pessoa não é exigida
     * — MAS o valor ainda tem de aparecer na mensagem (anti-invenção), e os
     * negativos de terceiros continuam bloqueados ("Rafael vive em..." não é
     * um comando, nem "O ISEP tem...").
     */
    private static final java.util.regex.Pattern EXPLICIT_PROFILE_COMMAND =
            java.util.regex.Pattern.compile("(?i)\\b(?:atualiza|atualize|atualizar|define|definir|"
                    + "guarda|guardar|regista|registe|registar|registra|update|set)\\b[^.!?]*\\b"
                    + "(?:perfil|profile|localiza[cç][aã]o|location|estudos|studies|habilidades|skills|nome|name)\\b");

    /**
     * Filtra os campos de uma proposta de perfil, mantendo apenas os que
     * estão ancorados numa frase em 1ª pessoa da mensagem — ou, quando a
     * mensagem é um COMANDO EXPLÍCITO de atualização de perfil, ancorados
     * em qualquer frase da mensagem.
     *
     * @param fields      campos propostos (campo → valor)
     * @param userMessage a mensagem/nota original do utilizador
     * @return campos admitidos (pode ser vazio → a proposta é descartada)
     */
    public static Map<String, String> filterGroundedProfileFields(Map<String, String> fields,
                                                                  String userMessage) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fields == null || fields.isEmpty()) return out;
        if (userMessage == null || userMessage.isBlank()) return out;

        List<String> sentences = splitSentences(userMessage);
        boolean explicitCommand = EXPLICIT_PROFILE_COMMAND.matcher(userMessage).find();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (value == null || value.isBlank()) continue;
            if (isGrounded(value, sentences, !explicitCommand)) {
                out.put(key, value.trim());
            } else {
                LOGGER.warning("REJECTED_UNGROUNDED_PROFILE_FIELD: campo=" + key
                        + " — o valor nao esta ancorado numa afirmacao em 1a pessoa do utilizador.");
            }
        }
        return out;
    }

    /**
     * Verifica se o valor proposto está ancorado: existe alguma frase (em
     * 1ª pessoa quando {@code requireFirstPerson}) cujo conteúdo cobre o
     * valor (por substring normalizada ou sobreposição de palavras).
     */
    private static boolean isGrounded(String value, List<String> sentences,
                                      boolean requireFirstPerson) {
        String vNorm = normalize(value);
        if (vNorm.isEmpty()) return false;
        List<String> vTokens = contentTokens(value);
        for (String sentence : sentences) {
            if (requireFirstPerson && !isFirstPerson(sentence)) continue;
            String sNorm = normalize(sentence);
            if (sNorm.contains(vNorm)) return true;
            if (!vTokens.isEmpty() && overlap(sNorm, vTokens) >= MIN_OVERLAP) return true;
        }
        return false;
    }

    /** Frase é em 1ª pessoa? (contém um marcador de 1ª pessoa) */
    private static boolean isFirstPerson(String sentence) {
        String s = " " + normalize(sentence) + " ";
        for (String marker : FIRST_PERSON_MARKERS) {
            if (s.contains(marker)) return true;
        }
        return false;
    }

    /** Tokens de conteúdo (sem stopwords, comprimento >= 3). */
    private static List<String> contentTokens(String s) {
        List<String> tokens = new ArrayList<>();
        for (String t : normalize(s).split("[^a-z0-9]+")) {
            if (t.length() >= 3 && !CONTENT_STOPWORDS.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** Fração de tokens do valor presentes na frase normalizada. */
    private static double overlap(String sentenceNorm, List<String> valueTokens) {
        int present = 0;
        for (String t : valueTokens) {
            if (sentenceNorm.contains(t)) present++;
        }
        return (double) present / valueTokens.size();
    }

    /** Divide a mensagem em frases (., !, ?, ; e novas linhas). */
    private static List<String> splitSentences(String message) {
        List<String> out = new ArrayList<>();
        for (String s : message.split("(?<=[.!?;])\\s+|\\n+")) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        if (out.isEmpty() && message != null && !message.isBlank()) {
            out.add(message.trim());
        }
        return out;
    }

    /** Normaliza: minúsculas, sem diacríticos, espaços colapsados. */
    private static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
