import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import persistence.SqliteConversationRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link SqliteConversationRepository} (the persistent
 * conversation store backing the ChatSession).
 */
class ConversationRepositoryTest {

    @BeforeAll
    static void isolateData() {
        try {
            Path dataDir = Files.createTempDirectory("aether-convrepo-test");
            System.setProperty("aether.data.dir", dataDir.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void messagesPersistInOrder() {
        SqliteConversationRepository repo = new SqliteConversationRepository();
        SqliteConversationRepository.Conversation c = repo.createConversation();

        repo.addMessage(c.id(), "user", "Primeira pergunta");
        repo.addMessage(c.id(), "assistant", "Primeira resposta");
        repo.addMessage(c.id(), "user", "Segunda pergunta");

        List<SqliteConversationRepository.Message> messages = repo.messages(c.id());
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("Primeira pergunta", messages.get(0).content());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("user", messages.get(2).role());
        assertEquals(1, messages.get(0).seq());
        assertEquals(3, messages.get(2).seq());
    }

    @Test
    void deleteConversationCascadesToMessages() {
        SqliteConversationRepository repo = new SqliteConversationRepository();
        SqliteConversationRepository.Conversation c = repo.createConversation();
        repo.addMessage(c.id(), "user", "mensagem a eliminar");
        assertEquals(1, repo.messages(c.id()).size());

        assertTrue(repo.deleteConversation(c.id()));
        assertEquals(0, repo.messages(c.id()).size(),
                "messages must cascade-delete with their conversation");
    }

    @Test
    void latestConversationIsTheMostRecentlyTouched() {
        SqliteConversationRepository repo = new SqliteConversationRepository();
        SqliteConversationRepository.Conversation first = repo.createConversation();
        repo.addMessage(first.id(), "user", "olá");

        SqliteConversationRepository.Conversation second = repo.createConversation();

        SqliteConversationRepository.Conversation latest = repo.latestConversation();
        assertNotNull(latest);
        assertEquals(second.id(), latest.id(),
                "the newest conversation (most recently updated) must be returned");
    }

    @Test
    void addMessageToUnknownConversationReturnsNull() {
        SqliteConversationRepository repo = new SqliteConversationRepository();
        assertNull(repo.addMessage("conversa-inexistente", "user", "órfã"),
                "a message for a non-existent conversation must not be persisted (FK guard)");
    }

    @Test
    void searchFindsContentAcrossConversations() {
        SqliteConversationRepository repo = new SqliteConversationRepository();
        SqliteConversationRepository.Conversation a = repo.createConversation();
        SqliteConversationRepository.Conversation b = repo.createConversation();
        repo.addMessage(a.id(), "user", "falar sobre a Maria");
        repo.addMessage(b.id(), "user", "falar sobre o João");

        List<SqliteConversationRepository.Message> hits = repo.search("Maria");
        assertEquals(1, hits.size());
        assertEquals("falar sobre a Maria", hits.get(0).content());
    }
}
