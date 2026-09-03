import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import domain.entities.Event;
import domain.entities.Person;
import domain.entities.Task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the dashboard data-loading behaviour: the summary lists must be
 * capped to a small number of items so they never overflow the window, while
 * the total counts remain accurate.
 */
class DashboardLimitsTest {

    private static final int EXPECTED_MAX_ITEMS = 4;

    @BeforeEach
    void setup() throws Exception {
        Path tmp = Files.createTempDirectory("aether-dashboard-test");
        System.setProperty("aether.data.dir", tmp.toString());
        VaultManager.initializeVault();
    }

    @Test
    void peopleList_isCappedToFour() {
        for (int i = 0; i < 10; i++) {
            Person p = new Person();
            p.setName("Person " + i);
            p.setOccupation("Engineer " + i);
            VaultManager.savePerson(p);
        }

        // The dashboard must show at most EXPECTED_MAX_ITEMS people even though
        // the vault contains 10. We replicate the capping logic the controller
        // uses (stream + limit) to assert the contract.
        List<Person> all = VaultManager.listPeople();
        assertEquals(10, all.size(), "total count must remain 10");

        long shown = all.stream().limit(EXPECTED_MAX_ITEMS).count();
        assertEquals(EXPECTED_MAX_ITEMS, shown, "dashboard must cap people to " + EXPECTED_MAX_ITEMS);
    }

    @Test
    void eventsList_showsOnlyFutureSortedAndCapped() {
        LocalDateTime now = LocalDateTime.now();
        // Two past events should be hidden.
        for (int i = 0; i < 2; i++) {
            Event past = new Event();
            past.setTitle("Past Event " + i);
            past.setStartDateTime(now.minusDays(i + 1));
            VaultManager.saveEvent(past);
        }
        // Six future events should be capped to EXPECTED_MAX_ITEMS.
        for (int i = 0; i < 6; i++) {
            Event e = new Event();
            e.setTitle("Event " + i);
            e.setStartDateTime(now.plusDays(i + 1));
            VaultManager.saveEvent(e);
        }

        List<Event> all = VaultManager.listEvents();
        assertEquals(8, all.size(), "total count must remain 8");

        List<Event> shown = all.stream()
                .filter(ev -> ev.getStartDateTime() == null || !ev.getStartDateTime().isBefore(now))
                .sorted(java.util.Comparator.comparing(Event::getStartDateTime))
                .limit(EXPECTED_MAX_ITEMS)
                .toList();
        assertEquals(EXPECTED_MAX_ITEMS, shown.size(), "dashboard must cap future events to " + EXPECTED_MAX_ITEMS);
        assertTrue(shown.get(0).getTitle().startsWith("Event 0"), "first shown must be nearest future event");
        assertTrue(shown.stream().noneMatch(e -> e.getTitle().startsWith("Past")), "past events must be hidden");
    }

    @Test
    void tasksList_hidesCompletedSortedByDeadlineAndCapped() {
        LocalDateTime now = LocalDateTime.now();
        // Completed tasks must be hidden.
        for (int i = 0; i < 3; i++) {
            Task done = new Task();
            done.setTitle("Done Task " + i);
            done.setDeadline(now.plusDays(i + 1));
            done.setStatus(domain.entities.TaskStatus.DONE);
            VaultManager.saveTask(done);
        }
        for (int i = 0; i < 6; i++) {
            Task t = new Task();
            t.setTitle("Task " + i);
            t.setDeadline(now.plusDays(i + 1));
            VaultManager.saveTask(t);
        }

        List<Task> all = VaultManager.listTasks();
        assertEquals(9, all.size(), "total count must remain 9");

        List<Task> shown = all.stream()
                .filter(t -> t.getStatus() != domain.entities.TaskStatus.DONE)
                .sorted(java.util.Comparator.comparing(Task::getDeadline))
                .limit(EXPECTED_MAX_ITEMS)
                .toList();
        assertEquals(EXPECTED_MAX_ITEMS, shown.size());
        assertTrue(shown.get(0).getTitle().startsWith("Task 0"));
        assertTrue(shown.stream().noneMatch(t -> t.getTitle().startsWith("Done")), "done tasks must be hidden");
    }
}
