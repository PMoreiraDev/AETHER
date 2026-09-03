import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import session.ChatSession;
import util.OllamaService.ChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the ChatSession persists conversation history across controller
 * recreations — i.e., when the user switches views and comes back, the
 * conversation is still there. This is the fix for the bug where switching to
 * the Dashboard and back reset the chat.
 */
class ChatSessionPersistenceTest {

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
    void clearResetsConversation() {
        ChatSession session = ChatSession.getInstance();
        session.addUserMessage("Olá");
        session.addAssistantMessage("Olá!");
        assertEquals(2, session.getHistory().size());

        session.clear();

        assertTrue(session.getHistory().isEmpty(),
                "clear() must reset the conversation so the next turn starts fresh");
    }

    @Test
    void historyIsCappedToMaxTurns() {
        ChatSession session = ChatSession.getInstance();
        session.clear();
        // Add 10 full turns (20 messages). The cap is 6 turns (12 messages).
        for (int i = 0; i < 10; i++) {
            session.addUserMessage("Pergunta " + i);
            session.addAssistantMessage("Resposta " + i);
        }

        assertTrue(session.getHistory().size() <= 12,
                "history must be capped to avoid inflating the model context, got "
                        + session.getHistory().size());
        // The most recent turn must be preserved.
        ChatMessage last = session.getHistory().get(session.getHistory().size() - 1);
        assertEquals("Resposta 9", last.getContent());
    }
}
