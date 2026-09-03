import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import util.ContextManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the AETHER entity → context pipeline (Tasks 6–11, 15).
 * <p>
 * Creates the canonical test data (Person: João Costa, Project: AETHER,
 * Event: Meeting with João, Task: Prepare AETHER meeting, Note: Discussed the
 * next AETHER milestone with João Costa) directly in a throwaway vault, then
 * verifies that {@link ContextManager#buildSystemPrompt(String)} retrieves the
 * relevant entities for each sample query — without Ollama and without a
 * restart (Task 9: newly created entities are available immediately).
 * </p>
 */
class EntityContextPipelineTest {

    @BeforeAll
    static void setupVault() throws Exception {
        // Redirect the vault + SQLite DB to a fresh temp directory so the test
        // never touches the real user data and needs no restart.
        Path tempDir = Files.createTempDirectory("aether-pipeline-test");
        System.setProperty("aether.data.dir", tempDir.toString());

        // Person: João Costa
        Person joao = new Person();
        joao.setName("João Costa");
        joao.setOccupation("Software Engineer");
        joao.setAbout("Colleague working on AETHER");
        VaultManager.savePerson(joao);

        // Project: AETHER
        Project aether = new Project();
        aether.setName("AETHER");
        aether.setDescription("Personal knowledge and AI assistant project");
        VaultManager.saveProject(aether);

        // Event: Meeting with João
        Event meeting = new Event();
        meeting.setTitle("Meeting with João");
        meeting.setLocation("Lisbon");
        meeting.setStartDateTime(LocalDateTime.now().plusDays(1).withHour(10).withMinute(0));
        VaultManager.saveEvent(meeting);

        // Task: Prepare AETHER meeting
        Task prepareTask = new Task();
        prepareTask.setTitle("Prepare AETHER meeting");
        prepareTask.setDeadline(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        VaultManager.saveTask(prepareTask);

        // Note: Discussed the next AETHER milestone with João Costa
        // Includes [[wikilinks]] so relationships live in the data (Task 10).
        String noteBody = "Discussed the next AETHER milestone with João Costa.\n\n"
                + "## Related\n- [[João Costa]]\n- [[AETHER]]";
        Note note = new Note();
        note.setContent(noteBody);
        VaultManager.saveNote(note, noteBody);
    }

    @Test
    void queryWhoIsJoaoCosta_returnsPersonContext() {
        String prompt = ContextManager.buildSystemPrompt("Who is João Costa?");
        assertTrue(prompt.contains("João Costa"),
                "Expected the person 'João Costa' in the AI context. Prompt:\n" + prompt);
    }

    @Test
    void queryWhatDoIHaveWithJoao_returnsAssociatedEntities() {
        String prompt = ContextManager.buildSystemPrompt("What do I have with João?");
        assertTrue(prompt.contains("Meeting"),
                "Expected the event 'Meeting with João' in the AI context. Prompt:\n" + prompt);
    }

    @Test
    void queryWhatProjectIsJoaoAssociatedWith_returnsAetherProject() {
        String prompt = ContextManager.buildSystemPrompt("What project is João associated with?");
        // AETHER is pulled in via the note's [[AETHER]] wikilink (phase 3),
        // so it must appear in a dedicated PROJECTS section — not just as a
        // substring of the note title.
        assertTrue(prompt.contains("PROJECTS:"),
                "Expected a PROJECTS section in the AI context. Prompt:\n" + prompt);
        assertTrue(prompt.contains("AETHER"),
                "Expected the project 'AETHER' in the AI context. Prompt:\n" + prompt);
    }

    @Test
    void queryWhatTasksRelatedToAether_returnsTask() {
        String prompt = ContextManager.buildSystemPrompt("What tasks are related to AETHER?");
        assertTrue(prompt.contains("Prepare AETHER meeting"),
                "Expected the task 'Prepare AETHER meeting' in the AI context. Prompt:\n" + prompt);
    }

    @Test
    void newlyCreatedEntityAvailableImmediatelyWithoutRestart() {
        // Task 9: a brand-new entity must be retrievable right away, no restart.
        Person maria = new Person();
        maria.setName("Maria Silva");
        maria.setOccupation("Designer");
        VaultManager.savePerson(maria);

        String prompt = ContextManager.buildSystemPrompt("Who is Maria Silva?");
        assertTrue(prompt.contains("Maria Silva"),
                "Newly created person should be available to the AI immediately. Prompt:\n" + prompt);
    }

    // ------------------------------------------------------------------
    // Category-wide recall — generic questions with no entity name
    // ------------------------------------------------------------------
    //
    // These reproduce the reported bug: the AI "mostly recognized People"
    // because name-based matching only fires when the user names a specific
    // entity. Generic questions like "what are my tasks" never mention a
    // title, so every non-Person type stayed invisible. buildRelevantVaultContext
    // must now also recognize category-level questions and return every
    // entity of that type.

    @Test
    void queryListMyProjects_returnsAllProjectsGenerically() {
        String prompt = ContextManager.buildSystemPrompt("Quais são os meus projetos?");
        assertTrue(prompt.contains("PROJECTS:"),
                "Expected a PROJECTS section for a generic projects question. Prompt:\n" + prompt);
        assertTrue(prompt.contains("AETHER"),
                "Expected the project 'AETHER' in the AI context. Prompt:\n" + prompt);
    }

    @Test
    void queryListMyTasks_returnsAllTasksGenerically() {
        String prompt = ContextManager.buildSystemPrompt("What are my tasks?");
        assertTrue(prompt.contains("TASKS:"),
                "Expected a TASKS section for a generic tasks question. Prompt:\n" + prompt);
        assertTrue(prompt.contains("Prepare AETHER meeting"),
                "Expected the task 'Prepare AETHER meeting' in the AI context. Prompt:\n" + prompt);
    }

    @Test
    void queryListMyEvents_returnsAllEventsGenerically() {
        String prompt = ContextManager.buildSystemPrompt("O que tenho na minha agenda?");
        assertTrue(prompt.contains("EVENTS:"),
                "Expected an EVENTS section for a generic agenda question. Prompt:\n" + prompt);
        assertTrue(prompt.contains("Meeting with João"),
                "Expected the event 'Meeting with João' in the AI context. Prompt:\n" + prompt);
    }

    @Test
    void queryListMyNotes_returnsAllNotesGenerically() {
        String prompt = ContextManager.buildSystemPrompt("Show me my notes");
        assertTrue(prompt.contains("NOTES:"),
                "Expected a NOTES section for a generic notes question. Prompt:\n" + prompt);
    }

    @Test
    void queryListMyPeople_returnsAllPeopleGenerically() {
        String prompt = ContextManager.buildSystemPrompt("Quem são as minhas pessoas?");
        assertTrue(prompt.contains("PEOPLE:"),
                "Expected a PEOPLE section for a generic people question. Prompt:\n" + prompt);
        assertTrue(prompt.contains("João Costa"),
                "Expected the person 'João Costa' in the AI context. Prompt:\n" + prompt);
    }
}
