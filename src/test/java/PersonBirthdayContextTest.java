import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import domain.entities.Person;
import util.ContextManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the AI context includes a person's birthday so the model can
 * answer questions about age and birth dates. Before this fix, the birthday
 * was stored in the vault but never included in the AI's context, so the AI
 * could not tell the user when someone was born or how old they are.
 */
class PersonBirthdayContextTest {

    @BeforeEach
    void setup() throws Exception {
        Path tmp = Files.createTempDirectory("aether-birthday-test");
        System.setProperty("aether.data.dir", tmp.toString());
        VaultManager.initializeVault();
    }

    @Test
    void birthdayIncludedInContext_whenPersonHasBirthDate() {
        Person rafael = new Person();
        rafael.setName("Rafael");
        rafael.setBirthDate(LocalDate.of(1990, 5, 15));
        rafael.setAbout("O Rafael é o meu irmão.");
        VaultManager.savePerson(rafael);

        String prompt = ContextManager.buildSystemPrompt("quando faz anos o Rafael?");

        assertTrue(prompt.contains("Rafael"), "Rafael must be in the context");
        assertTrue(prompt.contains("1990-05-15"),
                "the birthday must be in the context so the AI can answer age/birthday questions");
    }

    @Test
    void systemPromptInstructsAiNotToAttributeKnowledgeToUser() {
        String prompt = ContextManager.buildSystemPrompt("olá");

        // The AI must be told to state vault facts as its own knowledge and
        // never say "you mentioned" / "as you said".
        assertTrue(prompt.contains("NEVER attribute"),
                "system prompt must forbid attributing vault knowledge to the user");
        assertTrue(prompt.contains("you mentioned"),
                "system prompt must explicitly list the forbidden phrasing 'you mentioned'");
        assertTrue(prompt.contains("age"),
                "system prompt must instruct the AI to calculate age from birthday and current date");
    }
}
