package ai;

import domain.entities.AetherEntity;
import domain.entities.ContextEntityType;
import util.VaultIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AiActionOrchestrator — converte ações extraídas ({@link ParsedAction}) em
 * propostas validadas ({@link AiActionProposal}) prontas para aprovação.
 * <p>
 * É o ponto de integração entre o extrator (que fala com o modelo) e o fluxo de
 * aprovação (que fala com a UI e o executor). Recebe um {@link ActionExtractor}
 * injetável, pelo que é testável sem Ollama: os testes usam um extrator falso
 * que devolve {@link ParsedAction} controlados.
 * </p>
 * <p>
 * Responsabilidades:
 * <ul>
 *   <li>Mapear textos do modelo para os enums {@link AiActionType} e
 *       {@link ContextEntityType} (valores desconhecidos são ignorados).</li>
 *   <li>Resolver o nome de uma entidade existente (target) num id real via
 *       {@link VaultIndex}. Se a entidade não existir, a ação é descartada —
 *       nunca se age sobre uma entidade desconhecida.</li>
 *   <li>Para CREATE, detetar duplicados via {@link DuplicateResolver}.</li>
 *   <li>Validar cada proposta via {@link ActionValidator}; inválidas são descartadas.</li>
 * </ul>
 * </p>
 *
 * @author AETHER
 */
public final class AiActionOrchestrator {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(AiActionOrchestrator.class.getName());

    private final ActionExtractor extractor;

    /**
     * Cria o orquestrador com um extrator. Em produção usa-se
     * {@link OllamaActionExtractor}; em testes, um extrator falso.
     *
     * @param extractor o extrator de ações
     */
    public AiActionOrchestrator(ActionExtractor extractor) {
        this.extractor = extractor == null ? (um, ar) -> List.of() : extractor;
    }

    /**
     * Orquestra a extração e validação de ações (caminho do chat).
     *
     * @param userMessage    a mensagem original do utilizador
     * @param assistantReply a resposta natural já gerada
     * @return lista de ações resolvidas e validadas
     */
    public List<ResolvedAction> orchestrate(String userMessage, String assistantReply) {
        return orchestrate(userMessage, assistantReply, "", false);
    }

    public List<ResolvedAction> orchestrate(String userMessage, String assistantReply, String sourceLabel) {
        return orchestrate(userMessage, assistantReply, sourceLabel, false);
    }

    /**
     * Orquestração completa. Quando {@code enforceSourceQuote} é {@code true}
     * (caminho de compreensão de notas), toda a proposta é obrigada a ter uma
     * citação literal da nota — sem a qual é rejeitada. No caminho de chat,
     * onde o utilizador pede explicitamente ações, esta exigência não se aplica.
     */
    public List<ResolvedAction> orchestrate(String userMessage, String assistantReply,
                                            String sourceLabel, boolean enforceSourceQuote) {
        List<ParsedAction> parsed = extractor.extract(userMessage, assistantReply);
        int rejected = 0;
        List<ResolvedAction> out = new ArrayList<>();
        for (ParsedAction pa : parsed) {
            AiActionType type = mapAction(pa.action);
            ContextEntityType entity = mapEntity(pa.entity);
            if (type == null || entity == null) {
                rejected++;
                LOGGER.warning("REJECTED_ACTION: acao ou tipo de entidade desconhecido "
                        + "(action=" + pa.action + ", entity=" + pa.entity + ").");
                continue;
            }
            ResolvedAction resolved = build(type, entity, pa, sourceLabel, userMessage, enforceSourceQuote);
            if (resolved != null) {
                out.add(resolved);
            } else {
                rejected++;
            }
        }
        LOGGER.info("Orchestrator: recebidas=" + parsed.size() + ", aceitas=" + out.size()
                + ", rejeitadas=" + rejected + ".");
        return out;
    }

    private ResolvedAction build(AiActionType type, ContextEntityType entity, ParsedAction pa,
                                String sourceLabel, String noteText, boolean enforceSourceQuote) {
        // HARD GATE (apenas para notas) — sourceQuote (evidence) obrigatório e literal.
        // Nenhuma proposta pode existir sem uma citação literal da nota original.
        String evidence = pa.evidence == null ? "" : pa.evidence.trim();
        if (enforceSourceQuote) {
            if (evidence.isEmpty()) {
                LOGGER.warning("REJECTED_NO_SOURCE_QUOTE: proposta sem sourceQuote literal "
                        + "(entity=" + pa.entity + "). A ignorar — a IA nao pode propor sem fonte.");
                return null;
            }
            if (!literalQuoteFound(noteText, evidence)) {
                LOGGER.warning("REJECTED_SOURCE_QUOTE_NOT_FOUND: a citação não aparece literalmente na nota "
                        + "(entity=" + pa.entity + ", quote=\"" + truncate(evidence, 80) + "\").");
                return null;
            }
        }
        Map<String, String> fields = ActionExecutor.normalizeFields(entity, pa.fields);
        // Propagação da classificação epistémica e da confiança. A classificação
        // semântica (FACT/INFERENCE/SUGGESTION) é preservada tal como o modelo a
        // atribuiu — nunca se converte FACT em INFERENCE só por ainda não estar
        // aprovado. trustLevel mantém-se como indicador de confiança do sistema
        // (CONFIRMED só após aceitação do utilizador).
        TrustLevel trust = mapClassification(pa.classification);
        double confidence = pa.confidence > 0 ? pa.confidence : 0.5;
        String sourceContext = buildSourceContext(sourceLabel, pa.evidence);
        String semanticClass = normalizeClassification(pa.classification);
        switch (type) {
            case CREATE_ENTITY -> {
                AiActionProposal.Builder b = new AiActionProposal.Builder()
                        .actionType(type).entityType(entity)
                        .reason(pa.reason)
                        .relationships(pa.relationships)
                        .trustLevel(trust)
                        .confidence(confidence)
                        .sourceContext(sourceContext)
                        .semanticClassification(semanticClass);
                fields.forEach(b::field);
                AiActionProposal proposal = b.build();
                if (!ActionValidator.isValid(proposal)) {
                    LOGGER.warning("REJECTED_INVALID: proposta inválida "
                            + ActionValidator.validate(proposal) + " (entity=" + pa.entity + ").");
                    return null;
                }
                DuplicateResolver.Result dup = DuplicateResolver.resolve(entity, identifierOf(entity, fields));
                return new ResolvedAction(proposal, dup, ProposalStatus.PENDING_APPROVAL);
            }
            case UPDATE_ENTITY -> {
                if (entity == ContextEntityType.PROFILE) {
                    // ANTI-ALUCINAÇÃO DETERMINÍSTICA (spec #5): um valor de
                    // perfil só entra em proposta se estiver ancorado numa
                    // frase em 1ª pessoa da mensagem/nota — bloqueia em código
                    // "Rafael vive em Amarante" → location, "O ISEP tem um
                    // curso interessante" → studies, e valores inventados.
                    Map<String, String> grounded = PersonalMemoryGuard.filterGroundedProfileFields(fields, noteText);
                    if (grounded.isEmpty()) {
                        LOGGER.warning("DROPPED_PROFILE_UNGROUNDED: nenhum campo proposto esta "
                                + "ancorado numa afirmacao em 1a pessoa do utilizador.");
                        return null;
                    }
                    fields = grounded;
                    // Deduplicação própria do perfil (spec #9, #10):
                    //  - descarta campos cujo valor já está confirmado no perfil;
                    //  - descarta se já existe uma proposta pendente idêntica.
                    Map<String, String> deduped = filterRedundantProfileFields(fields);
                    if (deduped.isEmpty()) {
                        LOGGER.fine("DROPPED_PROFILE_DUPLICATE: todos os campos já estão confirmados ou pendentes");
                        return null;
                    }
                    AiActionProposal.Builder b = new AiActionProposal.Builder()
                            .actionType(type).entityType(entity).entityId("profile")
                            .reason(pa.reason).relationships(pa.relationships)
                            .trustLevel(trust).confidence(confidence)
                            .sourceContext(sourceContext)
                            .semanticClassification(semanticClass);
                    deduped.forEach(b::field);
                    AiActionProposal proposal = b.build();
                    if (!ActionValidator.isValid(proposal)) {
                        LOGGER.warning("REJECTED_INVALID_PROFILE: " + ActionValidator.validate(proposal));
                        return null;
                    }
                    return new ResolvedAction(proposal, null, ProposalStatus.PENDING_APPROVAL);
                }
                String targetName = pa.target.isBlank() ? identifierOf(entity, fields) : pa.target;
                AetherEntity existing = resolveByName(entity, targetName);
                if (existing == null) return null;
                AiActionProposal.Builder b2 = new AiActionProposal.Builder()
                        .actionType(type).entityType(entity).entityId(existing.getId())
                        .reason(pa.reason).relationships(pa.relationships)
                        .trustLevel(trust).confidence(confidence).sourceContext(sourceContext)
                        .semanticClassification(semanticClass);
                fields.forEach(b2::field);
                AiActionProposal proposal = b2.build();
                if (!ActionValidator.isValid(proposal)) return null;
                return new ResolvedAction(proposal, null, ProposalStatus.PENDING_APPROVAL);
            }
            case DELETE_ENTITY, LINK_ENTITIES, UNLINK_ENTITIES -> {
                String targetName = pa.target.isBlank() ? identifierOf(entity, fields) : pa.target;
                AetherEntity existing = resolveByName(entity, targetName);
                if (existing == null) {
                    // Relações entre entidades ainda não criadas: não se dropa
                    // silenciosamente, mas não se pode executar sem endpoint. Para
                    // já regista como proposta pendente apenas se for CREATE.
                    LOGGER.fine("REJECTED_NO_ENDPOINT: acao " + type + " sobre entidade inexistente "
                            + "(" + targetName + ").");
                    return null;
                }
                AiActionProposal.Builder b2 = new AiActionProposal.Builder()
                        .actionType(type).entityType(entity)
                        .entityId(existing.getId())
                        .reason(pa.reason)
                        .relationships(pa.relationships)
                        .trustLevel(trust)
                        .confidence(confidence)
                        .sourceContext(sourceContext)
                        .semanticClassification(semanticClass);
                fields.forEach(b2::field);
                AiActionProposal proposal = b2.build();
                if (!ActionValidator.isValid(proposal)) {
                    LOGGER.warning("REJECTED_INVALID: " + ActionValidator.validate(proposal)
                            + " (entity=" + pa.entity + ").");
                    return null;
                }
                return new ResolvedAction(proposal, null, ProposalStatus.PENDING_APPROVAL);
            }
        }
        return null;
    }

    /**
     * Verifica se a citação (sourceQuote) aparece literalmente na nota. É
     * tolerante a diferenças de capitalização e espaços, mas o conteúdo tem
     * de ser uma subsequência literal da nota — nunca uma invenção.
     */
    private static boolean literalQuoteFound(String note, String quote) {
        if (note == null || note.isBlank() || quote == null || quote.isBlank()) return false;
        String n = normalize(note);
        String q = normalize(quote);
        if (q.isEmpty()) return false;
        if (n.contains(q)) return true;
        // tolerância: remover as aspas exteriores que o modelo possa ter adicionado
        if (q.length() > 2 && q.startsWith("\"") && q.endsWith("\"")) {
            String inner = q.substring(1, q.length() - 1).trim();
            return !inner.isEmpty() && n.contains(inner);
        }
        return false;
    }

    private static String normalize(String s) {
        // collapse whitespace, lowercase, sem acentos para matching tolerante
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeClassification(String c) {
        if (c == null || c.isBlank()) return "SUGGESTION";
        return switch (c.trim().toUpperCase(Locale.ROOT)) {
            case "FACT", "FACTO", "FATO" -> "FACT";
            case "INFERENCE", "INFERRED", "INFERIDO", "INFERENCIA" -> "INFERENCE";
            default -> "SUGGESTION";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Mapeia a classificação epistémica do modelo para {@link TrustLevel}.
     * <p>
     * FACT e INFERENCE mapeiam para {@code INFERRED} (nunca para CONFIRMED —
     * CONFIRMED é reservado para dados que o utilizador já aceitou e persistiu).
     * SUGGESTION e valores desconhecidos mapeiam para {@code SUGGESTED}.
     * </p>
     */
    private static TrustLevel mapClassification(String classification) {
        if (classification == null || classification.isBlank()) return TrustLevel.SUGGESTED;
        return switch (classification.trim().toUpperCase(Locale.ROOT)) {
            case "FACT", "INFERRED", "INFERENCE", "INFERIDO", "FACTO" -> TrustLevel.INFERRED;
            default -> TrustLevel.SUGGESTED;
        };
    }

    private static String buildSourceContext(String sourceLabel, String evidence) {
        StringBuilder sb = new StringBuilder();
        if (sourceLabel != null && !sourceLabel.isBlank()) {
            sb.append(sourceLabel);
        }
        if (evidence != null && !evidence.isBlank()) {
            if (sb.length() > 0) sb.append(" — ");
            // Trunca a evidência para não inchar a card.
            String ev = evidence.length() > 240 ? evidence.substring(0, 240) + "…" : evidence;
            sb.append("\u201c").append(ev).append("\u201d");
        }
        return sb.toString().trim();
    }

    /** Resolve um nome/título a uma entidade existente (insensível a diacríticos). */
    private AetherEntity resolveByName(ContextEntityType entity, String name) {
        if (name == null || name.isBlank()) return null;
        String wanted = DuplicateResolver.normalize(name);
        List<? extends AetherEntity> list = entitiesOf(entity);
        AetherEntity best = null;
        for (AetherEntity e : list) {
            String dn = DuplicateResolver.normalize(displayName(e));
            if (dn.isEmpty()) continue;
            if (dn.equals(wanted)) return e;
            if (dn.contains(wanted) || wanted.contains(dn)) {
                if (best == null) best = e;
            }
        }
        return best;
    }

    private List<? extends AetherEntity> entitiesOf(ContextEntityType t) {
        return switch (t) {
            case PERSON -> VaultIndex.getInstance().people();
            case PROJECT -> VaultIndex.getInstance().projects();
            case EVENT -> VaultIndex.getInstance().events();
            case TASK -> VaultIndex.getInstance().tasks();
            case NOTE -> VaultIndex.getInstance().notes();
            case PROFILE -> List.of();
        };
    }

    private static String identifierOf(ContextEntityType t, java.util.Map<String, String> fields) {
        if (fields == null) return "";
        return switch (t) {
            case PERSON, PROJECT -> fields.getOrDefault("name", "");
            case EVENT, TASK -> fields.getOrDefault("title", "");
            case NOTE -> fields.getOrDefault("content", "");
            case PROFILE -> fields.getOrDefault("preferredName", "");
        };
    }

    private static String displayName(AetherEntity e) {
        return switch (e) {
            case domain.entities.Person p -> p.getName() == null ? "" : p.getName();
            case domain.entities.Project p -> p.getName() == null ? "" : p.getName();
            case domain.entities.Event ev -> ev.getTitle() == null ? "" : ev.getTitle();
            case domain.entities.Task tk -> tk.getTitle() == null ? "" : tk.getTitle();
            case domain.entities.Note n -> n.getContent() == null ? "" : n.getContent();
            default -> "";
        };
    }

    private static java.util.Map<String, String> copyFields(java.util.Map<String, String> in) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (in != null) out.putAll(in);
        return out;
    }

    /**
     * Deduplica campos de uma proposta de atualização de perfil (spec #9, #10).
     * <p>
     * Remove campos cujo valor proposto já está confirmado no perfil do
     * utilizador ou que já tenham uma proposta pendente idêntica. Se todos os
     * campos forem redundantes, devolve um mapa vazio (a proposta é descartada).
     * Isto evita que o AETHER crie continuamente Suggested Updates duplicadas
     * para informação já confirmada.
     *
     * @param proposed campos propostos pela IA
     * @return campos não redundantes (pode ser vazio)
     */
    private static java.util.Map<String, String> filterRedundantProfileFields(java.util.Map<String, String> proposed) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (proposed == null || proposed.isEmpty()) return result;

        domain.UserProfile profile = session.UserSession.getInstance().getUserProfile();
        java.util.List<ai.ProposalStore.Snapshot> pending = ai.ProposalStore.getInstance().pending();

        for (java.util.Map.Entry<String, String> e : proposed.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isBlank()) continue;

            // 1. Já confirmado no perfil?
            String current = profileValue(profile, key);
            if (current != null && alreadyContainsValue(current, value, key)) {
                continue; // valor idêntico ou já presente como bullet — redundante
            }

            // 2. Já existe proposta pendente idêntica para o mesmo campo+valor?
            if (hasPendingProfileField(pending, key, value)) {
                continue; // já pendente idêntico — não duplicar
            }

            result.put(key, value);
        }
        return result;
    }

    /** Lê o valor atual confirmado de um campo do perfil. */
    private static String profileValue(domain.UserProfile profile, String key) {
        if (profile == null || key == null) return null;
        return switch (key) {
            case "fullName" -> profile.getFullName();
            case "preferredName" -> profile.getPreferredName();
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
            case "aiContext" -> profile.getAiContext();
            case "inferredContext" -> profile.getInferredContext();
            case "profileSummary" -> profile.getProfileSummary();
            default -> null;
        };
    }

    /** Verifica se já existe uma proposta pendente com o mesmo campo+valor. */
    private static boolean hasPendingProfileField(java.util.List<ai.ProposalStore.Snapshot> pending,
                                                   String key, String value) {
        if (pending == null) return false;
        for (ai.ProposalStore.Snapshot s : pending) {
            if (s == null || s.entityType == null) continue;
            if (!"PROFILE".equals(s.entityType) || s.fields == null) continue;
            String pv = s.fields.get(key);
            if (pv != null && alreadyContainsValue(pv, value, key)) return true;
        }
        return false;
    }

    /**
     * Compara um valor proposto contra o conteúdo já confirmado/pendente. Para
     * campos de contexto (aiContext, inferredContext) que acumulam bullets,
     * normaliza marcadores de bullet e compara por linha — evita propostas
     * repetidas quando o texto já está presente como bullet.
     */
    private static boolean alreadyContainsValue(String current, String proposed, String key) {
        if (current == null || proposed == null) return false;
        String c = normalizeForCompare(current);
        String p = normalizeForCompare(proposed);
        if (c.equals(p)) return true;
        if (isContextField(key)) {
            // Compara linha-a-linha: se alguma linha confirmada contiver o
            // valor proposto (ou vice-versa), é redundante.
            for (String line : c.split("\\r?\\n")) {
                String l = line.trim();
                if (!l.isEmpty() && (l.equals(p) || l.contains(p) || p.contains(l))) return true;
            }
        }
        return false;
    }

    private static boolean isContextField(String key) {
        return "aiContext".equals(key) || "inferredContext".equals(key);
    }

    /** Normaliza para comparação: remove marcadores de bullet, espaços e acentos. */
    private static String normalizeForCompare(String s) {
        String n = java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("^[\\s•\\-*·]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return n;
    }

    private static AiActionType mapAction(String s) {
        if (s == null) return null;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "CREATE_ENTITY" -> AiActionType.CREATE_ENTITY;
            case "UPDATE_ENTITY" -> AiActionType.UPDATE_ENTITY;
            case "LINK_ENTITIES" -> AiActionType.LINK_ENTITIES;
            case "UNLINK_ENTITIES" -> AiActionType.UNLINK_ENTITIES;
            case "DELETE_ENTITY" -> AiActionType.DELETE_ENTITY;
            default -> null;
        };
    }

    private static ContextEntityType mapEntity(String s) {
        if (s == null) return null;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "PERSON" -> ContextEntityType.PERSON;
            case "PROJECT" -> ContextEntityType.PROJECT;
            case "EVENT" -> ContextEntityType.EVENT;
            case "TASK" -> ContextEntityType.TASK;
            case "NOTE" -> ContextEntityType.NOTE;
            case "PROFILE", "USER_PROFILE", "USER" -> ContextEntityType.PROFILE;
            default -> null;
        };
    }
}
