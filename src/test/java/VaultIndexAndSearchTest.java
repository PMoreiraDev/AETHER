import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import domain.entities.TaskPriority;
import domain.entities.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import util.GlobalSearchService;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the central {@link VaultIndex} cache and {@link GlobalSearchService}:
 * read-through caching, invalidation after mutations, calendar date queries,
 * and diacritics-insensitive cross-entity search.
 */
class VaultIndexAndSearchTest {

    private Path tmpVault;

    @BeforeEach
    void setup() throws Exception {
        tmpVault = Files.createTempDirectory("aether-index-test");
        System.setProperty("aether.data.dir", tmpVault.toString());
        VaultManager.initializeVault();
        // Reset the shared index so no stale cache from another test leaks in.
        VaultIndex.getInstance().invalidate();
    }

    @Test
    void index_readThroughReturnsSavedEntities() {
        Person p = new Person();
        p.setName("João Costa");
        p.setOccupation("Engineer");
        VaultManager.savePerson(p);

        List<Person> people = VaultIndex.getInstance().people();
        assertEquals(1, people.size());
        assertEquals("João Costa", people.get(0).getName());
    }

    @Test
    void index_invalidatedAfterDelete() {
        Person p = new Person();
        p.setName("Ana Silva");
        VaultManager.savePerson(p);
        assertEquals(1, VaultIndex.getInstance().people().size());

        VaultManager.deletePerson(p);
        assertEquals(0, VaultIndex.getInstance().people().size(), "index should reflect deletion");
    }

    @Test
    void calendar_eventsAppearOnCorrectDay() {
        Event e = new Event();
        e.setTitle("AETHER Architecture Meeting");
        e.setStartDateTime(LocalDateTime.of(2026, 9, 6, 10, 0));
        VaultManager.saveEvent(e);

        VaultIndex idx = VaultIndex.getInstance();
        List<Event> onDay = idx.eventsOn(LocalDate.of(2026, 9, 6));
        assertEquals(1, onDay.size(), "event should appear on its start date");
        assertEquals(0, idx.eventsOn(LocalDate.of(2026, 9, 7)).size(), "no event on next day");
    }

    @Test
    void calendar_tasksAppearOnDeadline() {
        Task t = new Task();
        t.setTitle("Implement global search");
        t.setDeadline(LocalDateTime.of(2026, 9, 10, 18, 0));
        t.setStatus(TaskStatus.TODO);
        t.setPriority(TaskPriority.HIGH);
        VaultManager.saveTask(t);

        List<Task> due = VaultIndex.getInstance().tasksDueOn(LocalDate.of(2026, 9, 10));
        assertEquals(1, due.size(), "task should appear on its deadline");
        assertEquals("Implement global search", due.get(0).getTitle());
    }

    @Test
    void calendar_multipleItemsSameDay() {
        LocalDate day = LocalDate.of(2026, 9, 6);
        Event e1 = new Event();
        e1.setTitle("Standup");
        e1.setStartDateTime(day.atTime(9, 0));
        VaultManager.saveEvent(e1);
        Event e2 = new Event();
        e2.setTitle("Demo");
        e2.setStartDateTime(day.atTime(15, 0));
        VaultManager.saveEvent(e2);
        Task t = new Task();
        t.setTitle("Review notes");
        t.setDeadline(day.atTime(17, 0));
        VaultManager.saveTask(t);

        VaultIndex idx = VaultIndex.getInstance();
        assertEquals(2, idx.eventsOn(day).size(), "two events on the same day");
        assertEquals(1, idx.tasksDueOn(day).size(), "one task due on the same day");
    }

    @Test
    void search_diacriticsInsensitiveAcrossTypes() {
        Project proj = new Project();
        proj.setName("AETHER");
        VaultManager.saveProject(proj);
        Task t = new Task();
        t.setTitle("Pesquisa global");
        VaultManager.saveTask(t);
        Event e = new Event();
        e.setTitle("Reunião AETHER");
        e.setStartDateTime(LocalDateTime.of(2026, 9, 6, 10, 0));
        VaultManager.saveEvent(e);
        Note n = new Note();
        n.setContent("Notas sobre o AETHER roadmap");
        VaultManager.saveNote(n, n.getContent());

        // "aether" without diacritics should match the project, event and note.
        GlobalSearchService.SearchResult r = GlobalSearchService.search("aether");
        assertFalse(r.isEmpty(), "search should find AETHER-related entities");
        assertTrue(r.projects().stream().anyMatch(p -> p.getName().equals("AETHER")));
        assertTrue(r.events().stream().anyMatch(ev -> ev.getTitle().contains("AETHER")));
        assertTrue(r.notes().stream().anyMatch(no -> no.getContent().contains("AETHER")));

        // Diacritics-insensitive: "reuniao" (no accent) should match "Reunião".
        GlobalSearchService.SearchResult r2 = GlobalSearchService.search("reuniao");
        assertTrue(r2.events().stream().anyMatch(ev -> ev.getTitle().contains("Reunião")),
                "search should be diacritics-insensitive");
    }

    @Test
    void search_noResultsWhenNoMatch() {
        Person p = new Person();
        p.setName("João");
        VaultManager.savePerson(p);
        assertTrue(GlobalSearchService.search("zzzznonexistent").isEmpty());
    }

    @Test
    void index_emptyMonthReturnsNoItems() {
        VaultIndex idx = VaultIndex.getInstance();
        LocalDate day = LocalDate.of(2026, 2, 1);
        assertEquals(0, idx.eventsOn(day).size());
        assertEquals(0, idx.tasksDueOn(day).size());
    }
}
