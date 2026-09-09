import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.Database;
import persistence.EntitySynchronizer;
import persistence.SqliteEntityRepository;
import persistence.VaultManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the SQLite-authoritative entity store and its vault projection:
 * <ul>
 *   <li>saves write SQLite FIRST, then project markdown (spec §55: the vault
 *       can fail without corrupting AETHER Core);</li>
 *   <li>entities survive a wiped vault (re-projection from SQLite);</li>
 *   <li>entities survive a deleted DATABASE? No — the database is the source
 *       of truth; but entities survive a deleted VAULT (projection is
 *       rebuildable — spec RULE 4: indexes/projections must be rebuildable);</li>
 *   <li>external edits in the vault are re-imported (hash comparison);</li>
 *   <li>files created directly in the vault (e.g. in Obsidian) are imported;</li>
 *   <li>deleting a vault file externally does NOT delete the entity.</li>
 * </ul>
 */
class EntitySyncTest {

    private Path dataDir;

    @BeforeEach
    void setup() throws Exception {
        dataDir = Files.createTempDirectory("aether-entity-sync");
        System.setProperty("aether.data.dir", dataDir.toString());
        EntitySynchronizer.resetForTest();
        VaultManager.initializeVault();
    }

    private Person newPerson(String name) {
        Person p = new Person();
        p.setName(name);
        p.setOccupation("Engineer");
        return p;
    }

    @Test
    void saveWritesRepoFirst_thenProjectsFile() {
        Person p = newPerson("Maria Silva");
        Path file = VaultManager.savePerson(p);

        assertNotNull(file, "projection path must be returned");
        assertTrue(Files.isRegularFile(file), "markdown projection must exist");

        // The authoritative store has it too.
        SqliteEntityRepository repo = new SqliteEntityRepository();
        Person stored = repo.findPerson(p.getId());
        assertNotNull(stored, "entity must be in SQLite (source of truth)");
        assertEquals("Maria Silva", stored.getName());

        // And the public API reads from the authoritative store.
        List<Person> people = VaultManager.listPeople();
        assertEquals(1, people.size());
        assertEquals("Maria Silva", people.get(0).getName());
    }

    @Test
    void entitiesSurviveWipedVault() throws Exception {
        // The user deletes the whole vault folder (or it is on a dead drive).
        Person p = newPerson("Sobrevivente");
        Task t = new Task();
        t.setTitle("Tarefa crítica");
        VaultManager.savePerson(p);
        VaultManager.saveTask(t);
        assertEquals(1, VaultManager.listPeople().size());

        Path vault = VaultManager.getVaultPath();
        deleteRecursively(vault);

        // Nothing is lost: the source of truth still has the entities…
        assertFalse(VaultManager.listPeople().isEmpty(),
                "entities must survive vault loss (SQLite is the source of truth)");
        // …and after a restart/reconcile the projection is rebuilt.
        EntitySynchronizer.resetForTest();
        EntitySynchronizer.reconcile();
        Path peopleDir = VaultManager.getVaultPath().resolve("People");
        assertTrue(Files.isDirectory(peopleDir), "People folder must be re-created");
        try (var files = Files.list(peopleDir)) {
            assertEquals(1, files.count(), "the person file must be re-projected");
        }
        Optional<Person> found = VaultManager.findPerson("Sobrevivente");
        assertTrue(found.isPresent(), "person must be findable after vault rebuild");
        assertEquals(p.getId(), found.get().getId());
    }

    @Test
    void externalEditIsReimported() throws Exception {
        Person p = newPerson("Nome Original");
        Path file = VaultManager.savePerson(p);

        // The user edits the file in Obsidian.
        String content = Files.readString(file);
        String edited = content.replace("Nome Original", "Nome Editado no Obsidian")
                .replace("occupation: Engineer", "occupation: Astronaut");
        Files.writeString(file, edited);

        // The watcher hook picks it up.
        EntitySynchronizer.onExternalChange(file);

        Optional<Person> found = VaultManager.findPerson("Nome Editado");
        assertTrue(found.isPresent(), "external edit must be re-imported into SQLite");
        assertEquals("Astronaut", found.get().getOccupation());
    }

    @Test
    void fileCreatedDirectlyInVaultIsImported() throws Exception {
        // A person file created by hand in Obsidian.
        String markdown = """
                ---
                type: person
                id: manual-1
                name: Feito no Obsidian
                occupation: Designer
                about: Criado manualmente
                created: 2026-01-01 10:00
                updated: 2026-01-01 10:00
                ---
                # Feito no Obsidian

                Criado manualmente.
                """;
        Path peopleDir = VaultManager.getVaultPath().resolve("People");
        Path file = peopleDir.resolve("feito-no-obsidian.md");
        Files.writeString(file, markdown);

        EntitySynchronizer.reconcile();

        Optional<Person> found = VaultManager.findPerson("Feito no Obsidian");
        assertTrue(found.isPresent(), "vault-created entity must be imported");
        assertEquals("Designer", found.get().getOccupation());
    }

    @Test
    void externalDeleteDoesNotDestroyEntity() throws Exception {
        Person p = newPerson("Imortal");
        Path file = VaultManager.savePerson(p);
        Files.delete(file);

        // The entity remains in the source of truth.
        assertFalse(VaultManager.listPeople().isEmpty(),
                "external file deletion must NOT delete the entity");
        // And a reconcile re-projects the missing file.
        EntitySynchronizer.resetForTest();
        EntitySynchronizer.reconcile();
        assertTrue(Files.isRegularFile(file), "the file must be re-projected");
    }

    @Test
    void legacyInstallVaultImportedOnFirstList() throws Exception {
        // EXACT legacy-upgrade path: an existing installation has vault files
        // but a database with EMPTY entity tables (migration v3 just created
        // them). The first read (listPeople) must import the vault content
        // before any save happens.
        String markdown = """
                ---
                type: person
                id: legacy-person-9
                name: Utilizador Antigo
                occupation: Professor
                about: Dados da instalação antiga
                created: 2025-06-01 09:00
                updated: 2025-06-01 09:00
                ---
                # Utilizador Antigo

                Dados da instalação antiga.
                """;
        Path peopleDir = VaultManager.getVaultPath().resolve("People");
        Files.writeString(peopleDir.resolve("utilizador-antigo.md"), markdown);

        // First read after "restart" — no save happened yet.
        List<Person> people = VaultManager.listPeople();
        assertEquals(1, people.size(), "legacy vault content must be imported on first read");
        assertEquals("Utilizador Antigo", people.get(0).getName());
        assertEquals("Professor", people.get(0).getOccupation());
        assertEquals("legacy-person-9", people.get(0).getId());
    }

    @Test
    void renameKeepsSingleEntity() {
        Person p = newPerson("Antigo Nome");
        VaultManager.savePerson(p);
        assertEquals(1, VaultManager.listPeople().size());

        p.setName("Nome Novo");
        VaultManager.savePerson(p);
        assertEquals(1, VaultManager.listPeople().size(), "rename must not duplicate");
        assertEquals("Nome Novo", VaultManager.listPeople().get(0).getName());
    }

    @Test
    void noteOriginalTextRoundTrips() {
        Note note = new Note();
        note.setContent("Comprar leite");
        VaultManager.saveNote(note, "Lembra-me de comprar leite");

        SqliteEntityRepository repo = new SqliteEntityRepository();
        assertEquals("Lembra-me de comprar leite", repo.noteOriginalText(note.getId()),
                "the original natural-language text must be preserved in SQLite");
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best effort.
                        }
                    });
        } catch (Exception ignored) {
            // Best effort.
        }
    }
}
