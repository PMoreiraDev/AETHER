import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import domain.entities.Person;
import domain.entities.Event;
import domain.entities.Task;
import domain.entities.Note;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that VaultManager delete methods remove the right entity by id,
 * robust to the filename being derived from the (possibly renamed) name.
 */
class DeleteEntityTest {

    private Path tmpVault;

    @BeforeEach
    void setup() throws Exception {
        tmpVault = Files.createTempDirectory("aether-delete-test");
        System.setProperty("aether.data.dir", tmpVault.toString());
        VaultManager.initializeVault();
    }

    @Test
    void deletePerson_removesByIndexId() {
        Person p = new Person();
        p.setName("João Silva");
        p.setOccupation("Engineer");
        VaultManager.savePerson(p);
        assertEquals(1, VaultManager.listPeople().size());

        // Rename the person (changes the filename) but keep the same id.
        p.setName("João Silva Renamed");
        VaultManager.savePerson(p);
        assertEquals(1, VaultManager.listPeople().size(), "rename should not duplicate");

        assertTrue(VaultManager.deletePerson(p), "delete should report success");
        assertEquals(0, VaultManager.listPeople().size(), "person should be gone");
    }

    @Test
    void deleteEvent_removesByIndexId() {
        Event e = new Event();
        e.setTitle("Kickoff Meeting");
        VaultManager.saveEvent(e);
        assertEquals(1, VaultManager.listEvents().size());

        assertTrue(VaultManager.deleteEvent(e));
        assertEquals(0, VaultManager.listEvents().size());
    }

    @Test
    void deleteTask_removesByIndexId() {
        Task t = new Task();
        t.setTitle("Prepare slides");
        VaultManager.saveTask(t);
        assertEquals(1, VaultManager.listTasks().size());

        assertTrue(VaultManager.deleteTask(t));
        assertEquals(0, VaultManager.listTasks().size());
    }

    @Test
    void deleteNote_removesByIndexId() {
        Note n = new Note();
        n.setContent("Meeting with Ana about the launch tomorrow at 10:00");
        VaultManager.saveNote(n, n.getContent());
        assertEquals(1, VaultManager.listNotes().size());

        assertTrue(VaultManager.deleteNote(n));
        assertEquals(0, VaultManager.listNotes().size());
    }

    @Test
    void deletePerson_nullSafe() {
        assertTrue(!VaultManager.deletePerson(null));
    }
}
