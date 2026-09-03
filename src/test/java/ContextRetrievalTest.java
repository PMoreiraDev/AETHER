import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import domain.entities.Person;
import util.ContextManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the AI context retrieval finds vault entities by their
 * description content, not only by their name. This is the fix for the bug
 * where the user asked "who is my brother?" about a Person named "Rafael"
 * whose description said "my brother" — the name "Rafael" was not in the
 * question, so the old label-only matching never retrieved Rafael and the AI
 * could not answer.
 */
class ContextRetrievalTest {

    @BeforeEach
    void setup() throws Exception {
        Path tmp = Files.createTempDirectory("aether-context-test");
        System.setProperty("aether.data.dir", tmp.toString());
        VaultManager.initializeVault();
    }

    @Test
    void findsPersonByDescription_whenQuestionMentionsConceptInAbout() {
        Person rafael = new Person();
        rafael.setName("Rafael");
        rafael.setAbout("O Rafael é o meu irmão mais novo.");
        VaultManager.savePerson(rafael);

        // The name "Rafael" is NOT in the question — only the concept "irmão"
        // (brother) is, and it lives in the description, not the name.
        String prompt = ContextManager.buildSystemPrompt("quem é o meu irmão?");

        assertTrue(prompt.contains("Rafael"),
                "the system prompt must include Rafael when the user asks about "
                        + "their brother, even though the name is not in the question");
    }

    @Test
    void findsPersonByName_whenQuestionMentionsTheName() {
        Person rafael = new Person();
        rafael.setName("Rafael");
        rafael.setAbout("Engenheiro.");
        VaultManager.savePerson(rafael);

        String prompt = ContextManager.buildSystemPrompt("fala-me do Rafael");
        assertTrue(prompt.contains("Rafael"));
    }

    @Test
    void doesNotIncludeUnrelatedPerson_whenConceptNotInDescription() {
        Person rafael = new Person();
        rafael.setName("Rafael");
        rafael.setAbout("O Rafael é o meu irmão.");
        VaultManager.savePerson(rafael);

        Person ana = new Person();
        ana.setName("Ana");
        ana.setAbout("A Ana gosta de tocar piano.");
        VaultManager.savePerson(ana);

        String prompt = ContextManager.buildSystemPrompt("quem é o meu irmão?");

        assertTrue(prompt.contains("Rafael"));
        assertFalse(prompt.contains("Ana"),
                "Ana is unrelated to the question about a brother and must not be "
                        + "injected into the context");
    }
}
