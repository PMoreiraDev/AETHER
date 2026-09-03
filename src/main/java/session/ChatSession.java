package session;

import util.OllamaService.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Mantém o histórico da conversa com a IA entre trocas de vista.
 * <p>
 * Sem este contentor partilhado, o histórico seria perdido sempre que o
 * utilizador muda de vista (ex.: vai ao Dashboard e volta ao chat), porque o
 * {@link controller.AetherAIController} é recriado pelo FXMLLoader a cada
 * carregamento da vista. O {@code ChatSession} sobrevive à recriação do
 * controlador, pelo que a conversa só é reiniciada quando o utilizador clica
 * em "Nova conversa".
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class ChatSession {

    private static final ChatSession INSTANCE = new ChatSession();

    /** Número máximo de turnos (pares user/assistant) guardados. */
    private static final int MAX_HISTORY_TURNS = 6;

    private final List<ChatMessage> history = new ArrayList<>();

    private ChatSession() {
        // Singleton.
    }

    /**
     * Devolve a instância única da sessão de chat.
     *
     * @return a instância
     */
    public static ChatSession getInstance() {
        return INSTANCE;
    }

    /**
     * Devolve o histórico da conversa (user/assistant). A lista devolvida é a
     * mesma instância interna — o chamador pode ler mas não deve modificar
     * diretamente; use {@link #addUserMessage} e
     * {@link #addAssistantMessage}.
     *
     * @return o histórico da conversa
     */
    public List<ChatMessage> getHistory() {
        return history;
    }

    /**
     * Acrescenta uma mensagem do utilizador ao histórico.
     *
     * @param content o conteúdo da mensagem
     */
    public void addUserMessage(String content) {
        history.add(new ChatMessage("user", content));
        trim();
    }

    /**
     * Acrescenta uma resposta da IA ao histórico.
     *
     * @param content o conteúdo da resposta
     */
    public void addAssistantMessage(String content) {
        history.add(new ChatMessage("assistant", content));
        trim();
    }

    /**
     * Limpa todo o histórico da conversa. Chamado pelo botão "Nova conversa".
     */
    public void clear() {
        history.clear();
    }

    /**
     * Garante que o histórico não cresce indefinidamente: mantém apenas os
     * últimos {@value #MAX_HISTORY_TURNS} turnos (pares user/assistant).
     */
    private void trim() {
        while (history.size() > MAX_HISTORY_TURNS * 2) {
            history.remove(0);
        }
    }
}
