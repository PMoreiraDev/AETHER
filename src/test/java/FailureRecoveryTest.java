import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.Database;
import persistence.EntitySynchronizer;
import persistence.SchemaMigrations;
import persistence.VaultManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import domain.entities.Person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-recovery guarantees against a broken/unavailable vault: SQLite is
 * the source of truth, so an entity must survive a save whose Markdown
 * projection cannot be written.
 */
class FailureRecoveryTest {

    private Path dataDir;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createTempDirectory("aether-recovery-test");
        System.setProperty("aether.data.dir", dataDir.toString());
        EntitySynchronizer.resetForTest();
        SchemaMigrations.migrateIfNeeded();
        VaultManager.initializeVault();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("aether.data.dir");
        EntitySynchronizer.resetForTest();
    }

    /**
     * Vault unavailable during save: the People folder is replaced by a
     * regular file, so every Markdown projection write fails. The entity
     * must still be saved (SQLite first), the call must not throw, and the
     * data must be readable afterwards — the file is re-projected at the
     * next reconcile once the vault works again.
     */
    @Test
    void entitySurvivesVaultWriteFailure() throws Exception {
        Path peopleDir = VaultManager.getVaultPath().resolve("People");
        try (var files = Files.list(peopleDir)) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        Files.delete(peopleDir);
        Files.createFile(peopleDir); // a FILE where the directory should be

        Person alice = new Person("person-rec-1", "Alice", null, "Engineer",
                "About", java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        Path result = VaultManager.savePerson(alice);

        assertNotNull(result, "saveX returns the projection path even when the write fails");
        List<Person> people = VaultManager.listPeople(); // reads SQLite
        assertEquals(1, people.size(), "the entity must be saved despite vault failure");
        assertEquals("Alice", people.get(0).getName());

        // Once the vault works again, the next reconcile re-projects the file.
        Files.delete(peopleDir);
        Files.createDirectories(peopleDir);
        EntitySynchronizer.resetForTest();
        VaultManager.listPeople();
        assertTrue(Files.readString(
                        peopleDir.resolve("alice.md")).contains("id: person-rec-1"),
                "the entity must be re-projected after the vault recovers");
    }
}
