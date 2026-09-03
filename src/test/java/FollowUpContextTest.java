import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import domain.entities.Person;
import util.ContextManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a follow-up question can still retrieve the right vault entity
 * even when the current message alone has no matching tokens. The context query
 * combines the current message with the recent user turns so "e o trabalho
 * dele?" finds Rafael, who was mentioned in the previous turn.
 *
 * <p>This simulates the AetherAIController.buildContextQuery behaviour using
 * only the public ContextManager API: it concatenates the previous user message
 * with the current one before building the system prompt.
 */
class FollowUpContextTest {

    @BeforeEach
    void setup() throws Exception {
        Path tmp = Files.createTempDirectory("aether-followup-test");
        System.setProperty("aether.data.dir", tmp.toString());
        VaultManager.initializeVault();

        Person rafael = new Person();
        rafael.setName("Rafael");
        rafael.setOccupation("Médico");
        rafael.setAbout("O Rafael é o meu irmão e trabalha como médico no hospital.");
        VaultManager.savePerson(rafael);
    }

    @Test
    void followUpQuestion_findsEntityFromPreviousTurn() {
        // Previous turn: user mentioned Rafael.
        String previousUserMessage = "O meu irmão chama-se Rafael.";
        // Current message: a pronoun follow-up with no entity tokens.
        String currentMessage = "Qual é o trabalho dele?";

        // buildContextQuery concatenates the previous user turn with the current
        // message, so the vault retrieval sees "Rafael" from the history.
        String contextQuery = currentMessage + " " + previousUserMessage;
        String prompt = ContextManager.buildSystemPrompt(contextQuery);

        assertTrue(prompt.contains("Rafael"),
                "a follow-up question must still retrieve Rafael from the previous turn");
        assertTrue(prompt.contains("Médico"),
                "the retrieved entity's occupation must be in the context");
    }

    @Test
    void followUpQuestion_aloneWithoutHistory_doesNotFindEntity() {
        // Sanity check: the current message alone has no entity tokens, so
        // without the history, Rafael is NOT retrieved. This proves the
        // history-concatenation is what makes follow-ups work.
        String prompt = ContextManager.buildSystemPrompt("Qual é o trabalho dele?");
        assertTrue(!prompt.contains("Rafael"),
                "a pronoun-only follow-up with no history must not retrieve Rafael");
    }
}
