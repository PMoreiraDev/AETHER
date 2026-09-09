import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.SqliteConversationRepository;
import session.ChatSession;
import util.OllamaService.ChatMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the PERSISTENT {@link session.ChatSession}.
 * <p>
 * New semantics (schema v2+):
 * </p>
 * <ul>
 *   <li>every message is persisted to SQLite immediately (user message BEFORE
 *       the AI call — a crash never loses what the user typed);</li>
 *   <li>the FULL history is preserved (no cap); only the window sent to the
 *       LLM is capped ({@code historyForLlm()});</li>
 *   <li>{@code clear()} starts a NEW conversation without destroying the old
 *       one (user data is never implicitly deleted);</li>
 *   <li>an application restart (simulated by resetting the singleton)
 *       reloads the latest conversation.</li>
 * </ul>
 */
class ChatSessionPersistenceTest {

    private static Path dataDir;

    @BeforeAll
    static void isolateData() {
        try {
            dataDir = Files.createTempDirectory("aether-chat-test");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.setProperty("aether.data.dir", dataDir.toString());
        // Fresh singleton bound to this test's database (other test classes
        // may have initialized it against their own data dir in this JVM).
        ChatSession.resetForTest();
    }

    @BeforeEach
    void freshConversation() {
        ChatSession.getInstance().clear();
    }

    @AfterEach
    void cleanup() {
        ChatSession.getInstance().clear();
    }

    @Test
    void historySurvivesControllerRecreation() {
        // Simulate a first conversation turn in one controller instance.
        ChatSession firstController = ChatSession.getInstance();
        firstController.addUserMessage("O meu irmão chama-se Rafael.");
        firstController.addAssistantMessage("Fiquei a saber do Rafael.");

        // The controller is destroyed and recreated when the user switches
        // views. A new "controller" fetches the same singleton.
        ChatSession recreatedController = ChatSession.getInstance();
        List<ChatMessage> history = recreatedController.getHistory();

        // The conversation must still be there.
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("O meu irmão chama-se Rafael.", history.get(0).getContent());
        assertEquals("assistant", history.get(1).getRole());
        assertTrue(history.get(1).getContent().contains("Rafael"));
    }

    @Test
    void userMessageSurvivesRestart() {
        // The user message is persisted even if the AI reply never arrives
        // (crash / Ollama outage) — this is the restart-survival guarantee.
        ChatSession.getInstance().addUserMessage("Lembra-me de comprar pão.");

        ChatSession.resetForTest();
        List<ChatMessage> restored = ChatSession.getInstance().getHistory();
        assertEquals(1, restored.size());
        assertEquals("Lembra-me de comprar pão.", restored.get(0).getContent());
        assertEquals("user", restored.get(0).getRole());
    }

    @Test
    void fullConversationSurvivesRestart() {
        ChatSession session = ChatSession.getInstance();
        session.addUserMessage("Pergunta 1");
        session.addAssistantMessage("Resposta 1");
        session.addUserMessage("Pergunta 2");
        session.addAssistantMessage("Resposta 2");

        ChatSession.resetForTest();
        List<ChatMessage> restored = ChatSession.getInstance().getHistory();
        assertEquals(4, restored.size());
        assertEquals("Pergunta 1", restored.get(0).getContent());
        assertEquals("Resposta 2", restored.get(3).getContent());
    }

    @Test
    void clearStartsNewConversationWithoutDestroyingOldOne() {
        ChatSession session = ChatSession.getInstance();
        session.addUserMessage("Olá");
        session.addAssistantMessage("Olá!");
        String oldConversationId = session.activeConversationId();

        session.clear();

        assertTrue(session.getHistory().isEmpty(),
                "clear() must reset the visible conversation so the next turn starts fresh");
        // The old conversation is preserved in the repository (no implicit
        // data destruction).
        SqliteConversationRepository repo = new SqliteConversationRepository();
        assertFalse(repo.messages(oldConversationId).isEmpty(),
                "old conversation messages must remain persisted after clear()");
        assertTrue(repo.listConversations().size() >= 2,
                "clear() must create a NEW conversation, not delete the old one");
    }

    @Test
    void fullHistoryPreserved_llmWindowCapped() {
        ChatSession session = ChatSession.getInstance();
        // Add 10 full turns (20 messages). The FULL history is preserved;
        // only the window sent to the model is capped (6 turns = 12 messages).
        for (int i = 0; i < 10; i++) {
            session.addUserMessage("Pergunta " + i);
            session.addAssistantMessage("Resposta " + i);
        }

        assertEquals(20, session.getHistory().size(),
                "full history must be preserved (persisted, uncapped)");

        List<ChatMessage> llmWindow = session.historyForLlm();
        assertTrue(llmWindow.size() <= 12,
                "the window sent to the LLM must be capped, got " + llmWindow.size());
        // The most recent turn must be preserved in the window.
        ChatMessage last = llmWindow.get(llmWindow.size() - 1);
        assertEquals("Resposta 9", last.getContent());
        // And the window is the TAIL of the history.
        assertEquals("Pergunta 4", llmWindow.get(0).getContent());
    }
}
