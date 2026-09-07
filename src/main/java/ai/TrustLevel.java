package ai;

/**
 * Nível de confiança de uma informação ou relação no AETHER.
 * <p>
 * O AETHER separa sempre facto de inferência e de sugestão. A UI deixa clara
 * esta diferença para que o utilizador saiba o que está confirmado e o que foi
 * inferido ou proposto pela IA.
 * </p>
 *
 * @author AETHER
 */
public enum TrustLevel {

    /** Criado ou explicitamente confirmado pelo utilizador. Apresentado como facto. */
    CONFIRMED,

    /** Inferido pela IA / context engine. Não deve ser apresentado como facto confirmado. */
    INFERRED,

    /** Alteração proposta pela IA que ainda não foi aceite pelo utilizador. */
    SUGGESTED
}
