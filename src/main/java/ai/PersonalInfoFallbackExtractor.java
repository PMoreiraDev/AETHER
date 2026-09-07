package ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PersonalInfoFallbackExtractor — detetor heurístico DETERMINÍSTICO de
 * informação pessoal (memória persistente do utilizador), complemento/fallback
 * ao extrator baseado em LLM (Ollama).
 * <p>
 * Quando o Ollama não está disponível, não devolve ações, ou não reconhece uma
 * afirmação pessoal, este extrator analisa a mensagem do utilizador à procura
 * de informação sobre o PRÓPRIO utilizador (estudos, ocupação, localização,
 * competências, interesses, objetivos, preferências, projetos, família) e
 * produz {@link ParsedAction}s de {@code UPDATE_ENTITY + PROFILE} (ou
 * {@code CREATE_ENTITY + PERSON} para familiares) que entram no MESMO
 * pipeline: {@code AiActionOrchestrator → ProposalStore → sino → Profile}.
 * </p>
 * <p>
 * Este extrator é o caminho separado de PERSONAL MEMORY DETECTION (spec #6):
 * deteta factos persistentes declarados SEM comando explícito. A extração de
 * AÇÕES explícitas (criar tarefa, marcar reunião...) fica ao cargo do extrator
 * LLM — esta classe nunca propõe ações sobre entidades não-pessoais.
 * </p>
 * <p>
 * Características (specs #4, #5, #7, #8, #21, #22):
 * <ul>
 *   <li>Reconhece verbos na 1ª pessoa sem pronome ("Estudo no ISEP.").</li>
 *   <li>Deteta TODOS os campos da mensagem (não para no primeiro).</li>
 *   <li>Marca cada deteção com classificação FACT/INFERENCE e confiança
 *       (spec #8); evidência literal = a frase que despoletou a deteção.</li>
 *   <li>Guardas de terceiros e especulação EM CÓDIGO: "Rafael vive em
 *       Amarante." e "O ISEP tem um curso interessante." nunca geram
 *       propostas de perfil (o {@link PersonalMemoryGuard} reforça no
 *       orchestrator).</li>
 *   <li>Cobre o esquema canónico completo (location, skills, projetos,
 *       família), não só estudos/ocupação.</li>
 * </ul>
 * A IA apenas sugere — a informação fica PENDING até o utilizador aceitar.
 *
 * @author AETHER
 */
public class PersonalInfoFallbackExtractor implements ActionExtractor {

    // ------------------------------------------------------------------
    // Padrões de memória pessoal (1ª pessoa, PT-PT/PT-BR)
    // ------------------------------------------------------------------

    private static final Pattern STUDY_PLACE =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:estudo|frequento)\\s+(?:no|na|nos|nas|em|no\\s+instituto|na\\s+universidade)\\s+(.+?)(?:[.!?]|$)");
    private static final Pattern STUDY_SUBJECT =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:sou|estou)\\s+(?:estudante|aluno|aluna)\\s+(?:de|do|da)\\s+(.+?)(?:[.!?]|$)");
    private static final Pattern STUDY_DIRECT =
            Pattern.compile("(?i)\\b(?:eu\\s+)?estudo\\s+([A-ZÀ-Ú][\\p{L}]+(?:\\s+[\\p{L}]+){0,4}?)(?:[.!?]|$)");

    private static final Pattern LOCATION_LIVE =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:vivo|moramos?|resido)\\s+(?:em|no|na|nos|nas)\\s+(.+?)(?:[.!?]|$)");
    private static final Pattern LOCATION_MORO =
            Pattern.compile("(?i)\\b(?:eu\\s+)?moro\\s+(?:em|no|na|nos|nas)\\s+(.+?)(?:[.!?]|$)");
    private static final Pattern LOCATION_FROM =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:sou|vivo|moro)\\s+(?:de|em)\\s+([A-ZÀ-Ú][\\p{L}]+(?:\\s+[\\p{L}]+){0,3}?)\\s*$");

    private static final Pattern OCCUPATION_SOY =
            Pattern.compile("(?i)\\b(?:eu\\s+)?sou\\s+([\\p{L}]+(?:\\s+[\\p{L}]+){0,3}?)(?:[.!?]|$)");
    private static final Pattern OCCUPATION_WORK =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:trabalho|atuo)\\s+(?:como|em)\\s+(.+?)(?:[.!?]|$)");

    private static final Pattern SKILL_LEARNING =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:estou\\s+a\\s+aprender|estou\\s+aprendendo|ando\\s+a\\s+aprender|aprendi)\\s+(.+?)(?:[.!?]|$)");
    private static final Pattern SKILL_KNOW =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:sei|domino|programo\\s+em|uso)\\s+(.+?)(?:[.!?]|$)");

    private static final Pattern INTEREST =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:gosto|adoro|aprecio)\\s+(?:de|do|da|dos|das)\\s+(.+?)(?:[.!?]|$)");
    private static final Pattern OBJECTIVE =
            Pattern.compile("(?i)\\b(?:eu\\s+)?(?:quero|pretendo|desejo|planeio|tenho\\s+como\\s+objetivo|o\\s+meu\\s+objetivo\\s+é)\\s+(.+?)(?:[.!?]|$)");
    private static final Pattern PREFERENCE =
            Pattern.compile("(?i)\\b(?:eu\\s+)?prefiro\\s+(.+?)(?:[.!?]|$)");

    private static final Pattern PROJECT_NAME =
            Pattern.compile("(?i)\\b(?:o\\s+|a\\s+)?(?:meu|minha)\\s+(?:projeto|project)\\s+(?:chama-se|chamasse|chama|é\\s+o|é\\s+a)\\s+(.+?)(?:[.!?]|$)");

    /** "Tenho um irmão chamado Rafael." → CREATE PERSON (spec #21). */
    private static final Pattern FAMILY_MEMBER =
            Pattern.compile("(?i)\\b(?:eu\\s+)?tenho\\s+(?:um|uma)\\s+(irmão|irmao|irmã|irma|primo|prima|tio|tia|avô|avo|avó|avoa|sobrinho|sobrinha|cunhado|cunhada|filho|filha|pai|mãe|mae)\\s+(?:chamado|chamada|que\\s+se\\s+chama)\\s+([A-ZÀ-Ú][\\p{L}]+(?:\\s+[A-ZÀ-Ú][\\p{L}]+)*)");

    /** "Estou no segundo ano." → info de progresso académico sem campo próprio. */
    private static final Pattern SCHOOL_YEAR =
            Pattern.compile("(?i)\\b(?:eu\\s+)?estou\\s+no\\s+(primeiro|segundo|terceiro|quarto|quinto|último|ultimo)\\s+ano\\b");

    // ------------------------------------------------------------------
    // Guardas (3ª pessoa / especulação)
    // ------------------------------------------------------------------

    /** Especulação — classificação INFERENCE, nunca FACT (spec #8). */
    private static final Pattern SPECULATION =
            Pattern.compile("(?i)\\b(talvez|acho\\s+que|penso\\s+que|possivelmente|deve\\s+ser|podia\\s+ser|parece\\s+que)\\b");

    private static final List<String> OCCUPATION_NOUNS = List.of(
            "programador", "programadora", "engenheiro", "engenheira", "médico", "médica",
            "professor", "professora", "advogado", "advogada", "enfermeiro", "enfermeira",
            "designer", "arquiteto", "arquiteta", "contador", "contabilista", "gestor",
            "gestora", "analista", "consultor", "consultora", "desenvolvedor", "desenvolvedora",
            "cientista", "pesquisador", "pesquisadora", "estudante", "empresário", "empresária",
            "designer", "musicista", "músico", "escritor", "escritora", "fotógrafo", "fotógrafa");

    @Override
    public List<ParsedAction> extract(String userMessage, String assistantReply) {
        List<ParsedAction> actions = new ArrayList<>();
        if (userMessage == null || userMessage.isBlank()) return actions;
        String msg = userMessage.trim();

        // NOTA: NÃO se rejeita a mensagem inteira por conter frases sobre
        // terceiros — mensagens mistas ("Rafael vive em Amarante. Eu vivo no
        // Porto.") continuam a detetar a parte do utilizador. Cada PATTERN é
        // ancorado em verbos de 1ª pessoa (vivo/estudo/gosto...), frases de
        // terceiros não casam com nenhum padrão; e o PersonalMemoryGuard no
        // orchestrator revalida campo a campo a âncora em 1ª pessoa.

        boolean fact = !isSpeculation(msg);
        String classification = fact ? "FACT" : "INFERENCE";
        double confidence = fact ? 0.9 : 0.5;

        // Estudos — instituição ("Eu estudo no ISEP").
        String study = matchGroup(STUDY_PLACE, msg);
        if (study != null) {
            actions.add(profileAction("studies", capitalize(cleanValue(study)), msg, classification, confidence));
        }
        // Estudos — curso/área ("Sou estudante de Engenharia Informática").
        String subject = matchGroup(STUDY_SUBJECT, msg);
        if (subject != null && study == null) {
            actions.add(profileAction("studies", capitalize(cleanValue(subject)), msg, classification, confidence));
        }
        // "Estudo Engenharia Informática"
        String direct = matchGroup(STUDY_DIRECT, msg);
        if (direct != null && study == null && subject == null && !isVerbPhrase(direct)) {
            actions.add(profileAction("studies", cleanValue(direct), msg, classification, confidence));
        }

        // Localização (spec #4): "Eu vivo em Amarante." / "Moro em Amarante."
        // / "Agora vivo no Porto." (atualização, não duplicação — a deduplicação
        // e o histórico ficam a cargo do orchestrator/executor).
        String location = matchGroup(LOCATION_LIVE, msg);
        if (location == null) location = matchGroup(LOCATION_MORO, msg);
        if (location == null) location = matchGroup(LOCATION_FROM, msg);
        if (location != null) {
            actions.add(profileAction("location", cleanValue(location), msg, classification, confidence));
        }

        // Ocupação ("Sou programador" / "Trabalho como X").
        String work = matchGroup(OCCUPATION_WORK, msg);
        if (work != null) {
            actions.add(profileAction("occupation", capitalize(cleanValue(work)), msg, classification, confidence));
        } else {
            String soy = matchGroup(OCCUPATION_SOY, msg);
            if (soy != null && isOccupationNoun(soy)) {
                actions.add(profileAction("occupation", capitalize(cleanValue(soy)), msg, classification, confidence));
            }
        }

        // Competências (spec #21): "Estou a aprender Python."
        String learning = matchGroup(SKILL_LEARNING, msg);
        if (learning != null && !isVerbPhrase(learning) && !startsWithPreposition(learning)) {
            actions.add(profileAction("skills", cleanValue(learning), msg, classification, confidence));
        } else {
            String knows = matchGroup(SKILL_KNOW, msg);
            if (knows != null && !isVerbPhrase(knows) && !startsWithPreposition(knows)) {
                actions.add(profileAction("skills", cleanValue(knows), msg, classification, confidence));
            }
        }

        // Objetivos ("Quero trabalhar em cybersecurity.").
        String obj = matchGroup(OBJECTIVE, msg);
        if (obj != null) {
            actions.add(profileAction("objectives", cleanValue(obj), msg, classification, confidence));
        }

        // Preferências ("Prefiro trabalhar à noite.").
        String pref = matchGroup(PREFERENCE, msg);
        if (pref != null) {
            actions.add(profileAction("preferences", cleanValue(pref), msg, classification, confidence));
        }

        // Projetos ("O meu projeto chama-se AETHER.").
        String project = matchGroup(PROJECT_NAME, msg);
        if (project != null) {
            actions.add(profileAction("projects", cleanValue(project), msg, classification, confidence));
        }

        // Interesses — atividade longa → inferredContext; curto → interests.
        String interest = matchGroup(INTEREST, msg);
        if (interest != null) {
            String cleaned = cleanValue(interest);
            if (isActivityPhrase(cleaned)) {
                actions.add(profileAction("inferredContext", capitalizeActivity(cleaned), msg, classification, confidence));
            } else {
                actions.add(profileAction("interests", cleaned, msg, classification, confidence));
            }
        }

        // Ano escolar ("Estou no segundo ano.") — sem campo estruturado
        // próprio; vai para inferredContext como bullet (não sobrescreve studies).
        String year = matchGroup(SCHOOL_YEAR, msg);
        if (year != null) {
            actions.add(profileAction("inferredContext",
                    "Está no " + cleanValue(year) + " ano", msg, classification, confidence));
        }

        // Família (spec #21): "Tenho um irmão chamado Rafael." → PERSON.
        Matcher fam = FAMILY_MEMBER.matcher(msg);
        while (fam.find()) {
            String relation = fam.group(1);
            String name = fam.group(2);
            if (name != null && !name.isBlank()) {
                Map<String, String> personFields = new LinkedHashMap<>();
                personFields.put("name", name.trim());
                personFields.put("about", capitalize(normalizeRelation(relation)) + " do utilizador");
                actions.add(ParsedAction.of("CREATE_ENTITY", "PERSON", personFields, List.of(), "",
                        "Familiar mencionado na conversa.", classification, confidence, fam.group()));
            }
        }

        return actions;
    }

    private static boolean isSpeculation(String msg) {
        return SPECULATION.matcher(msg).find();
    }

    private static String matchGroup(Pattern p, String input) {
        Matcher m = p.matcher(input);
        if (m.find()) {
            String g = m.group(1);
            return g == null ? null : g.trim();
        }
        return null;
    }

    private static String cleanValue(String v) {
        // Remove pronomes finais soltos e aspas; normaliza espaços.
        String s = v.replaceAll("(?i)\\b(?:porque|pois|mas|e|então)\\b.*$", "").trim();
        s = s.replace("\"", "").replace("'", "").trim();
        s = s.replaceAll("\\s{2,}", " ");
        return s;
    }

    private static boolean isOccupationNoun(String v) {
        String lower = v.toLowerCase(Locale.ROOT).trim();
        return OCCUPATION_NOUNS.stream().anyMatch(lower::startsWith);
    }

    /** "desenvolver aplicações desktop" → atividade (verbo); "futebol" → nome curto. */
    private static boolean isActivityPhrase(String v) {
        long words = v.trim().split("\\s+").length;
        return words >= 3 || v.toLowerCase().matches(".*(desenvolver|criar|programar|melhorar|aprender|estudar|construir|trabalhar|escrever|praticar).*");
    }

    /** Evita que "estou a aprender no ISEP" seja tratado como skill. */
    private static boolean startsWithPreposition(String v) {
        return v != null && v.toLowerCase(Locale.ROOT).trim()
                .matches("^(no|na|nos|nas|em|a|o|as|os|num|numa)\\b.*");
    }

    private static boolean isVerbPhrase(String v) {
        // Evita que "estudo muito" seja tratado como curso.
        return v.toLowerCase().matches(".*(muito|bastante|sempre|nunca|às vezes).*");
    }

    private static String capitalize(String v) {
        if (v == null || v.isEmpty()) return v;
        return Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    private static String capitalizeActivity(String v) {
        // "desenvolver aplicações desktop" → "Gosta de desenvolver aplicações desktop"
        return "Gosta de " + v;
    }

    /** "irmao" → "irmão" para a descrição da pessoa ficar legível. */
    private static String normalizeRelation(String relation) {
        return switch (relation.toLowerCase(Locale.ROOT)) {
            case "irmao" -> "irmão";
            case "irma" -> "irmã";
            case "avo" -> "avô";
            case "avoa" -> "avó";
            case "mae" -> "mãe";
            default -> relation.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Cria a ParsedAction de perfil com classificação, confiança e evidência
     * (a frase que contém o valor — spec #9 proveniência desde a origem).
     */
    private static ParsedAction profileAction(String field, String value, String source,
                                              String classification, double confidence) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, value);
        return ParsedAction.of("UPDATE_ENTITY", "PROFILE", fields, List.of(), "",
                "Informação pessoal detetada na conversa.", classification, confidence,
                evidenceSentence(source, value));
    }

    /**
     * A frase da mensagem que contém o valor detetado (evidência literal,
     * necessária no caminho de notas com {@code enforceSourceQuote}).
     */
    private static String evidenceSentence(String msg, String value) {
        if (msg == null || msg.isBlank()) return "";
        String vNorm = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        String best = "";
        for (String s : msg.trim().split("(?<=[.!?])\\s+|\\n+")) {
            if (s.isBlank()) continue;
            if (best.isEmpty()) best = s;
            if (!vNorm.isEmpty() && s.toLowerCase(Locale.ROOT).contains(vNorm)) {
                return s.length() > 240 ? s.substring(0, 240) : s;
            }
        }
        return best.length() > 240 ? best.substring(0, 240) : best;
    }
}
