package ai;

/**
 * Estado do ciclo de vida de uma proposta de ação no AETHER.
 * <p>
 * Distinto de {@link TrustLevel} (que descreve a <i>confiança</i> numa
 * informação: confirmada / inferida / sugerida). {@code ProposalStatus}
 * descreve o <i>estado de execução</i> da proposta — o que aconteceu a ela
 * depois de apresentada ao utilizador.
 * </p>
 *
 * @author AETHER
 */
public enum ProposalStatus {

    /** Apresentada ao utilizador, à espera de decisão. */
    PENDING_APPROVAL,

    /** Backward-compatible alias for older callers. */
    SUGGESTED,

    /** Aceite pelo utilizador (mas ainda não executada / em execução). */
    CONFIRMED,

    /** Rejeitada pelo utilizador. */
    REJECTED,

    /** Executada com sucesso contra o vault. */
    EXECUTED,

    /** A execução falhou. */
    FAILED
}
