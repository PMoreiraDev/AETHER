package ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ApprovalFlow — gere o ciclo de vida das propostas da IA até à execução.
 * <p>
 * Princípio fundamental do AETHER: <b>Suggest → Explain → Ask → Execute</b>.
 * A IA nunca altera dados persistentes silenciosamente. Cada proposta é
 * apresentada ao utilizador com a sua razão e confiança, e só é executada após
 * aprovação explícita (Accept / Reject / Edit). Aceitar em lote continua a
 * passar pelas mesmas validações.
 * </p>
 * <p>
 * O fluxo distingue claramente estados de confiança
 * ({@link TrustLevel#CONFIRMED}, {@link TrustLevel#INFERRED},
 * {@link TrustLevel#SUGGESTED}) para que a UI deixe clara a diferença entre
 * factos, inferências e sugestões.
 * </p>
 *
 * @author AETHER
 */
public final class ApprovalFlow {

    /**
     * Central approval gate used by UI controllers before touching persistent data.
     * Validation and destructive-action confirmation are kept here so controllers
     * do not bypass the canonical approval layer.
     *
     * @param proposal the proposal already reviewed by the user
     * @param confirmDestructive whether an explicit destructive confirmation was given
     * @return execution result
     */
    public static ExecutionResult executeApproved(AiActionProposal proposal, boolean confirmDestructive) {
        if (proposal == null) {
            return new ExecutionResult(null, false, "Proposal is null.");
        }
        List<String> errors = ActionValidator.validate(proposal);
        if (!errors.isEmpty()) {
            return new ExecutionResult(proposal, false, String.join("; ", errors));
        }
        if (proposal.isDestructive() && !confirmDestructive) {
            return new ExecutionResult(proposal, false, "Destructive action requires explicit confirmation.");
        }
        boolean ok = ActionExecutor.execute(proposal);
        return new ExecutionResult(proposal, ok, ok ? "Executed." : "Execution failed.");
    }

    /** Convenience overload for non-destructive approvals. */
    public static ExecutionResult executeApproved(AiActionProposal proposal) {
        return executeApproved(proposal, false);
    }

    private final Map<String, AiActionProposal> pending = new LinkedHashMap<>();
    private final List<AiActionProposal> accepted = new ArrayList<>();
    private final List<AiActionProposal> rejected = new ArrayList<>();
    private final List<ExecutionResult> results = new ArrayList<>();

    /**
     * Adiciona uma proposta pendente para aprovação. A proposta é validada
     * antes de ser apresentada; propostas inválidas são rejeitadas imediatamente
     * com o motivo.
     *
     * @param proposal a proposta
     * @return {@code true} se ficou pendente para aprovação
     */
    public boolean propose(AiActionProposal proposal) {
        if (proposal == null) {
            return false;
        }
        List<String> errors = ActionValidator.validate(proposal);
        if (!errors.isEmpty()) {
            rejected.add(proposal);
            results.add(new ExecutionResult(proposal, false, String.join("; ", errors)));
            return false;
        }
        pending.put(proposal.describe(), proposal);
        return true;
    }

    /**
     * Aceita uma proposta pendente e executa-a. Ações destrutivas (DELETE) são
     * recusadas aqui — exigem o caminho de confirmação dedicado.
     *
     * @param proposal a proposta a aceitar
     * @return resultado da execução
     */
    public ExecutionResult accept(AiActionProposal proposal) {
        return accept(proposal, false);
    }

    /**
     * Aceita uma proposta. Para ações destrutivas, {@code confirmDestructive}
     * tem de ser {@code true} (confirmação muito explícita).
     *
     * @param proposal            a proposta a aceitar
     * @param confirmDestructive  {@code true} para confirmar uma ação destrutiva
     * @return resultado da execução
     */
    public ExecutionResult accept(AiActionProposal proposal, boolean confirmDestructive) {
        if (proposal == null || !pending.containsValue(proposal)) {
            return new ExecutionResult(proposal, false, "Proposal is not pending.");
        }
        if (proposal.isDestructive() && !confirmDestructive) {
            return new ExecutionResult(proposal, false,
                    "Destructive action requires explicit confirmation.");
        }
        pending.remove(proposal.describe());
        ExecutionResult r = executeApproved(proposal, confirmDestructive);
        boolean ok = r.success();
        accepted.add(proposal);
        results.add(r);
        return r;
    }

    /**
     * Aceita todas as propostas pendentes não destrutivas. As destrutivas
     * permanecem pendentes para confirmação individual.
     *
     * @return resultados da execução
     */
    public List<ExecutionResult> acceptAll() {
        List<ExecutionResult> out = new ArrayList<>();
        for (AiActionProposal p : new ArrayList<>(pending.values())) {
            if (p.isDestructive()) {
                // Destrutivas exigem confirmação individual — não executar em lote.
                continue;
            }
            out.add(accept(p));
        }
        return out;
    }

    /**
     * Rejeita uma proposta pendente.
     */
    public void reject(AiActionProposal proposal) {
        if (proposal != null) {
            pending.remove(proposal.describe());
            rejected.add(proposal);
        }
    }

    /** Rejeita todas as propostas pendentes. */
    public void rejectAll() {
        rejected.addAll(pending.values());
        pending.clear();
    }

    /**
     * Substitui uma proposta pendente por uma versão editada (o utilizador
     * editou antes de aceitar).
     *
     * @param original  a proposta original pendente
     * @param edited    a proposta editada
     * @return {@code true} se substituída com sucesso
     */
    public boolean edit(AiActionProposal original, AiActionProposal edited) {
        if (original == null || edited == null) return false;
        if (!pending.containsKey(original.describe())) return false;
        if (!ActionValidator.isValid(edited)) return false;
        pending.remove(original.describe());
        pending.put(edited.describe(), edited);
        return true;
    }

    /** Devolve as propostas pendentes (não modificáveis). */
    public List<AiActionProposal> pending() {
        return Collections.unmodifiableList(new ArrayList<>(pending.values()));
    }

    /** Devolve as propostas aceites (não modificáveis). */
    public List<AiActionProposal> accepted() {
        return Collections.unmodifiableList(accepted);
    }

    /** Devolve as propostas rejeitadas (não modificáveis). */
    public List<AiActionProposal> rejected() {
        return Collections.unmodifiableList(rejected);
    }

    /** Devolve os resultados de execução (não modificáveis). */
    public List<ExecutionResult> results() {
        return Collections.unmodifiableList(results);
    }

    /** Número de propostas pendentes. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Resultado da execução de uma proposta.
     */
    public static final class ExecutionResult {
        private final AiActionProposal proposal;
        private final boolean success;
        private final String message;

        public ExecutionResult(AiActionProposal proposal, boolean success, String message) {
            this.proposal = proposal;
            this.success = success;
            this.message = message;
        }

        public AiActionProposal proposal() {
            return proposal;
        }

        public boolean success() {
            return success;
        }

        public String message() {
            return message;
        }
    }
}
