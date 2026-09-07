package ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;
import util.OllamaService;

/**
 * NoteAnalysisService — ponto de entrada da compreensão de notas.
 * <p>
 * Recebe o texto de uma nota (e uma etiqueta de fonte), executa a pipeline de
 * compreensão e devolve as <b>propostas</b> a apresentar ao utilizador —
 * nunca entidades confirmadas. O fluxo é:
 * </p>
 * <pre>
 *   NOTE → NoteUnderstandingExtractor (Ollama JSON) → JsonActionParser
 *        → AiActionOrchestrator (map + resolve + validate)
 *        → ProposalStore (idempotência: descarta já-decididas)
 *        → UI (ProposalCardBuilder) → USER DECIDES → ActionExecutor → Vault
 * </pre>
 * <p>
 * <b>Idempotência:</b> se a mesma nota for analisada duas vezes, as ações já
 * aceites/rejeitadas não voltam a aparecer; as pendentes duplicadas não se
 * multiplicam (mesmo id lógico). Isto torna o sistema restart-safe: as
 * pendentes sobrevivem em {@link ProposalStore} e podem ser recarregadas.
 * </p>
 * <p>
 * <b>Ollama indisponível:</b> se o modelo não responder, devolve uma lista
 * vazia (sem propagar exceções) — a nota continua guardada, simplesmente não
 * gera propostas automáticas. O utilizador pode re-analisar mais tarde.
 * </p>
 *
 * @author AETHER
 */
public final class NoteAnalysisService {

    private static final Logger LOGGER = Logger.getLogger(NoteAnalysisService.class.getName());

    private final String modelId;
    private final Supplier<List<OllamaService.ChatMessage>> historySupplier;

    /**
     * @param modelId        modelo Ollama ativo (vazio → analyze devolve [])
     * @param historySupplier fornece histórico da conversa para contexto (pode ser vazio)
     */
    public NoteAnalysisService(String modelId,
                               Supplier<List<OllamaService.ChatMessage>> historySupplier) {
        this.modelId = modelId;
        this.historySupplier = historySupplier == null ? List::of : historySupplier;
    }

    /**
     * Analisa uma nota e devolve as propostas a apresentar (filtradas por
     * idempotência). Bloqueante — chamar fora da FX Application Thread.
     *
     * @param noteText    texto da nota
     * @param sourceLabel etiqueta da fonte (ex.: "Nota: Reunião de hoje")
     * @return propostas resolvidas e não duplicadas
     */
    public List<ResolvedAction> analyze(String noteText, String sourceLabel) {
        // Sem modelo ativo, o extrator LLM devolve vazio — mas o detetor
        // heurístico de memória pessoal AINDA corre (spec #25): notas com
        // "Vivo em Amarante." alimentam o perfil mesmo sem Ollama.
        ActionExtractor noteExtractor = (modelId == null || modelId.isBlank())
                ? (um, ar) -> List.of()
                : new NoteUnderstandingExtractor(modelId, historySupplier);
        if (noteText == null || noteText.isBlank()) {
            return List.of();
        }
        // Extrator composto (spec #25): a compreensão LLM da nota continua a
        // ser a 1ª linha (com citação literal obrigatória), mas o detetor
        // heurístico de MEMÓRIA PESSOAL acrescenta factos de PERFIL em 1ª
        // pessoa escritos na nota ("Vivo em Amarante.") que o modelo de notas
        // não extrai (só deteta PERSON/PROJECT/TASK/EVENT). Ambos passam pelo
        // MESMO pipeline com guarda anti-alucinação e idempotência.
        ActionExtractor composite = (noteText1, reply1) -> {
            List<ai.ParsedAction> out = new ArrayList<>();
            try {
                out.addAll(noteExtractor.extract(noteText1, reply1));
            } catch (RuntimeException ignored) {
                // Falha do LLM: o fallback abaixo ainda deteta memória pessoal.
            }
            java.util.Set<String> covered = new java.util.HashSet<>();
            for (ai.ParsedAction p : out) {
                if ("PROFILE".equalsIgnoreCase(p.entity) && p.fields != null) {
                    covered.addAll(p.fields.keySet());
                }
            }
            PersonalInfoFallbackExtractor personal = new PersonalInfoFallbackExtractor();
            for (ai.ParsedAction p : personal.extract(noteText1, "")) {
                if ("PROFILE".equalsIgnoreCase(p.entity) && p.fields != null && !p.fields.isEmpty()) {
                    java.util.Map<String, String> remaining = new java.util.LinkedHashMap<>();
                    p.fields.forEach((k, v) -> { if (!covered.contains(k)) remaining.put(k, v); });
                    if (!remaining.isEmpty()) {
                        out.add(ai.ParsedAction.of("UPDATE_ENTITY", "PROFILE", remaining,
                                List.of(), "", "Informação pessoal detetada na nota.",
                                p.classification, p.confidence, p.evidence));
                    }
                }
            }
            return out;
        };
        AiActionOrchestrator orchestrator = new AiActionOrchestrator(composite);

        List<ResolvedAction> resolved;
        try {
            resolved = orchestrator.orchestrate(noteText, "", sourceLabel, true);
        } catch (RuntimeException e) {
            LOGGER.warning("NoteAnalysisService: falha ao analisar nota: " + e.getMessage());
            return List.of();
        }
        if (resolved == null || resolved.isEmpty()) {
            return List.of();
        }

        // Idempotência: regista cada proposta como PENDING e só mostra as que
        // são novas ou já pendentes. Ações já decididas (aceites/rejeitadas/
        // falhadas) são silenciosamente omitidas — não aborrecemos o utilizador
        // com decisões repetidas.
        List<ResolvedAction> toShow = new ArrayList<>();
        ProposalStore store = ProposalStore.getInstance();
        for (ResolvedAction ra : resolved) {
            ProposalStore.Snapshot snap = toPendingSnapshot(ra, sourceLabel);
            ProposalStore.Decision decision = store.propose(snap);
            if (decision == ProposalStore.Decision.NEW_PENDING
                    || decision == ProposalStore.Decision.ALREADY_PENDING
                    || decision == ProposalStore.Decision.ALREADY_FAILED) {
                toShow.add(ra);
            }
            // ALREADY_ACCEPTED / ALREADY_REJECTED → omitir (já decidido).
        }
        return toShow;
    }

    private static ProposalStore.Snapshot toPendingSnapshot(ResolvedAction ra, String sourceLabel) {
        AiActionProposal p = ra.proposal;
        String identifier = p.getFields().getOrDefault("name",
                p.getFields().getOrDefault("title", p.getFields().getOrDefault("content", "")));
        String id = ProposalStore.idFor(p.getActionType().name(), p.getEntityType().name(),
                identifier, p.getFields());
        return new ProposalStore.Snapshot(id, "PENDING", p.getActionType().name(),
                p.getEntityType().name(), identifier, p.getFields(), p.getRelationships(),
                p.getReason(), p.getTrustLevel().name(), p.getConfidence(),
                p.getSourceContext(), System.currentTimeMillis(),
                p.getSemanticClassification());
    }
}
