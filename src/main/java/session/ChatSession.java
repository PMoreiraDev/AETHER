package session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import persistence.SqliteConversationRepository;
import util.OllamaService.ChatMessage;

/**
 * Mantém a conversa com a IA — agora PERSISTENTE.
 * <p>
 * Anteriormente o histórico vivia apenas em RAM (limitado a 6 turnos), pelo
 * que fechar a aplicação perdia toda a conversa. Hoje cada mensagem é gravada
 * no SQLite no momento em que existe (a mensagem do utilizador ANTES da
 * chamada ao Ollama, a resposta da IA depois de recebida) e a conversa
 * ativa é recarregada no arranque seguinte.
 * </p>
 * <p>
 * Dois conceitos distintos:
 * </p>
 * <ul>
 *   <li>{@link #getHistory()} — histórico COMPLETO da conversa ativa
 *       (persistido, sem limite);</li>
 *   <li>{@link #historyForLlm()} — janela limitada (últimos turnos) enviada
 *       ao modelo, para não inflar o contexto.</li>
 * </ul>
 * <p>
 * {@link #clear()} começa uma NOVA conversa sem apagar a anterior — dados do
 * utilizador nunca são destruídos implicitamente. O armazenamento é autor
 * deste módulo: {@link SqliteConversationRepository}.
 * </p>
 *
 * @author Paulo Moreira
 * @version 2.0
 */
public final class ChatSession {

    private static final Logger LOGGER = Logger.getLogger(ChatSession.class.getName());

    /** Número máximo de turnos (pares user/assistant) enviados ao LLM. */
    private static final int MAX_LLM_TURNS = 6;

    /** Número máximo de mensagens restauradas na UI ao abrir a vista. */
    private static final int MAX_RESTORED_UI_MESSAGES = 100;

    private static volatile ChatSession instance;

    private final SqliteConversationRepository repository;

    /** Id da conversa ativa (persistida). */
    private String conversationId;

    /** Histórico completo da conversa ativa (cache em RAM). */
    private final List<ChatMessage> history = new ArrayList<>();

    private ChatSession() {
        this.repository = new SqliteConversationRepository();
        openLatestConversation();
    }

    /**
     * Devolve a instância única da sessão de chat.
     *
     * @return a instância
     */
    public static ChatSession getInstance() {
        ChatSession current = instance;
        if (current == null) {
            synchronized (ChatSession.class) {
                current = instance;
                if (current == null) {
                    current = new ChatSession();
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Resets the singleton — for tests simulating an application restart. */
    /** Resets the singleton (tests simulating an application restart). */
    public static synchronized void resetForTest() {
        instance = null;
    }

    /**
     * Abre a conversa mais recente (continuação após reinício) ou cria uma
     * nova quando não existe nenhuma.
     */
    private void openLatestConversation() {
        try {
            SqliteConversationRepository.Conversation latest = repository.latestConversation();
            if (latest == null) {
                latest = repository.createConversation();
            }
            conversationId = latest.id();
            for (SqliteConversationRepository.Message m : repository.messages(conversationId)) {
                history.add(new ChatMessage(m.role(), m.content()));
            }
        } catch (RuntimeException e) {
            // Base de dados indisponível: a sessão funciona só em RAM para
            // não bloquear a aplicação; a persistência tenta-se na próxima.
            LOGGER.warning("Conversa persistida indisponível: " + e.getMessage());
            conversationId = "";
        }
    }

    /**
     * Devolve o histórico COMPLETO da conversa ativa (vista só de leitura).
     *
     * @return o histórico completo
     */
    public List<ChatMessage> getHistory() {
        return Collections.unmodifiableList(history);
    }

    /**
     * Janela limitada para o contexto do LLM: os últimos
     * {@value #MAX_LLM_TURNS} turnos.
     *
     * @return sublista (cópia) adequada ao pedido /api/chat
     */
    public List<ChatMessage> historyForLlm() {
        synchronized (history) {
            int maxMessages = MAX_LLM_TURNS * 2;
            if (history.size() <= maxMessages) {
                return new ArrayList<>(history);
            }
            return new ArrayList<>(history.subList(history.size() - maxMessages, history.size()));
        }
    }

    /**
     * Mensagens a restaurar na UI (as mais recentes; o histórico completo
     * permanece na base de dados).
     *
     * @return cópia das últimas mensagens para renderização
     */
    public List<ChatMessage> historyForUi() {
        synchronized (history) {
            if (history.size() <= MAX_RESTORED_UI_MESSAGES) {
                return new ArrayList<>(history);
            }
            return new ArrayList<>(history.subList(
                    history.size() - MAX_RESTORED_UI_MESSAGES, history.size()));
        }
    }

    /**
     * Acrescenta uma mensagem do utilizador ao histórico e persiste-a de
     * imediato (antes de qualquer chamada à IA).
     *
     * @param content o conteúdo da mensagem
     */
    public synchronized void addUserMessage(String content) {
        add("user", content);
    }

    /**
     * Acrescenta uma resposta da IA ao histórico e persiste-a.
     *
     * @param content o conteúdo da resposta
     */
    public synchronized void addAssistantMessage(String content) {
        add("assistant", content);
    }

    private void add(String role, String content) {
        ChatMessage message = new ChatMessage(role, content);
        synchronized (history) {
            history.add(message);
        }
        persist(role, content);
    }

    private void persist(String role, String content) {
        if (conversationId == null || conversationId.isBlank()) {
            // Sem conversa persistida (falha anterior): tenta criar uma agora.
            try {
                SqliteConversationRepository.Conversation created =
                        repository.createConversation();
                conversationId = created.id();
            } catch (RuntimeException e) {
                LOGGER.warning("Não foi possível criar conversa persistida: " + e.getMessage());
                return;
            }
        }
        try {
            repository.addMessage(conversationId, role, content);
        } catch (RuntimeException e) {
            // Não destruir a sessão por falha de persistência: a mensagem
            // fica em RAM; o erro é registado para diagnóstico.
            LOGGER.warning("Não foi possível persistir mensagem: " + e.getMessage());
        }
    }

    /**
     * Começa uma conversa NOVA. A conversa anterior NÃO é apagada — fica
     * guardada na base de dados (proteção de dados do utilizador).
     */
    public synchronized void clear() {
        synchronized (history) {
            history.clear();
        }
        try {
            SqliteConversationRepository.Conversation created = repository.createConversation();
            conversationId = created.id();
        } catch (RuntimeException e) {
            LOGGER.warning("Não foi possível criar nova conversa: " + e.getMessage());
            conversationId = "";
        }
    }

    /** Id da conversa ativa (diagnóstico/testes). */
    public String activeConversationId() {
        return conversationId;
    }
}
