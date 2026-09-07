package ai;

import java.util.List;

/**
 * ActionExtractor — abstração que transforma a resposta natural do modelo numa
 * lista de {@link ParsedAction} estruturadas.
 * <p>
 * O modelo nunca executa nada: apenas produz texto. O extrator é o passo que
 * separa a resposta natural (mostrada ao utilizador) das ações estruturadas que
 * precisam de aprovação. Esta interface permite testar o
 * {@link AiActionOrchestrator} com um extrator falso, sem depender do Ollama.
 * </p>
 *
 * @author AETHER
 */
@FunctionalInterface
public interface ActionExtractor {

    /**
     * Extrai ações da resposta do assistente.
     *
     * @param userMessage   a mensagem original do utilizador
     * @param assistantReply a resposta natural já gerada pelo modelo
     * @return lista de ações extraídas (vazia se nenhuma ou inválida)
     */
    List<ParsedAction> extract(String userMessage, String assistantReply);
}
