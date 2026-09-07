package ai;

/**
 * ResolvedAction — uma proposta validada com o seu contexto de duplicados e
 * estado inicial. É o que a UI de aprovação recebe para renderizar um cartão.
 *
 * @author AETHER
 */
public final class ResolvedAction {

    /** A proposta validada. */
    public final AiActionProposal proposal;
    /** Resultado da deteção de duplicados (só relevante para CREATE); pode ser {@code null}. */
    public final DuplicateResolver.Result duplicate;
    /** Estado inicial ({@link ProposalStatus#PENDING_APPROVAL}) ao ser apresentada. */
    public final ProposalStatus status;

    ResolvedAction(AiActionProposal proposal, DuplicateResolver.Result duplicate, ProposalStatus status) {
        this.proposal = proposal;
        this.duplicate = duplicate;
        this.status = status;
    }

    /** Indica se a proposta é destrutiva (exige confirmação extra). */
    public boolean isDestructive() {
        return proposal.isDestructive();
    }

    /** Indica se há um duplicado que deva ser apresentado ao utilizador. */
    public boolean hasDuplicate() {
        return duplicate != null && duplicate.outcome != DuplicateResolver.Outcome.NO_DUPLICATE;
    }
}
