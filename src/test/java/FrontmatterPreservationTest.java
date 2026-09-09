import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.EntitySynchronizer;
import persistence.VaultManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import domain.entities.Person;
import domain.entities.Project;
import domain.entities.ProjectStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for user-defined (non-AETHER) frontmatter preservation in
 * entity files (spec: "Unknown / user-defined frontmatter fields MUST be
 * preserved"). AETHER-owned fields stay authoritative; everything else
 * survives saves, re-projections, renames, external edits and vault loss.
 */
class FrontmatterPreservationTest {

    private Path dataDir;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createTempDirectory("aether-fm-test");
        System.setProperty("aether.data.dir", dataDir.toString());
        EntitySynchronizer.resetForTest();
        VaultManager.initializeVault();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("aether.data.dir");
        EntitySynchronizer.resetForTest();
    }

    private static Person newPerson(String name) {
        return new Person("person-fm-1", name, null, "Engineer", "About",
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    }

    /** Reads the current vault file for a person by id. */
    private Path personFile(String id) throws Exception {
        Path dir = VaultManager.getVaultPath().resolve("People");
        try (var files = Files.list(dir)) {
            return files.filter(p -> {
                try {
                    return Files.readString(p).contains("id: " + id);
                } catch (Exception e) {
                    return false;
                }
            }).findFirst().orElseThrow();
        }
    }

    /**
     * Simulates the user editing the file in Obsidian: adds custom keys and
     * lets the watcher/import path pick the change up.
     */
    private void externalEdit(Path file, String extraFrontmatter) throws Exception {
        String content = Files.readString(file);
        int end = content.indexOf("---", 3);
        String edited = content.substring(0, end) + extraFrontmatter + "\n"
                + content.substring(end);
        Files.writeString(file, edited);
        EntitySynchronizer.onExternalChange(file);
    }

    @Test
    void customFieldsSurviveOrdinarySave() throws Exception {
        VaultManager.savePerson(newPerson("Alice"));
        externalEdit(personFile("person-fm-1"), "custom_field: something\npriority: high\nclient: ACME");

        // AETHER updates its own fields (occupation).
        Person updated = new Person("person-fm-1", "Alice", null, "Designer",
                "About", java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        VaultManager.savePerson(updated);

        String content = Files.readString(personFile("person-fm-1"));
        assertTrue(content.contains("custom_field: something"), content);
        assertTrue(content.contains("priority: high"), content);
        assertTrue(content.contains("client: ACME"), content);
        // AETHER-owned field is authoritative.
        assertTrue(content.contains("occupation: Designer"), content);
    }

    @Test
    void customFieldsSurviveRename() throws Exception {
        VaultManager.savePerson(newPerson("Old Name"));
        externalEdit(personFile("person-fm-1"), "client: ACME");

        // Rename: same id, new name → new file, old file removed.
        Person renamed = new Person("person-fm-1", "New Name", null, "Engineer",
                "About", java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        VaultManager.savePerson(renamed);

        Path file = personFile("person-fm-1");
        assertEquals("new-name.md", file.getFileName().toString());
        String content = Files.readString(file);
        assertTrue(content.contains("client: ACME"),
                "custom metadata must survive a rename: " + content);
        assertTrue(content.contains("name: New Name"), content);
        assertFalse(Files.exists(file.getParent().resolve("old-name.md")),
                "the stale file must be removed after rename");
    }

    @Test
    void customFieldsSurviveWipedVault() throws Exception {
        VaultManager.savePerson(newPerson("Alice"));
        externalEdit(personFile("person-fm-1"), "client: ACME");

        // Vault loss: delete the whole People folder.
        Path peopleDir = VaultManager.getVaultPath().resolve("People");
        try (var files = Files.list(peopleDir)) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }

        // New session: the entity is re-projected from SQLite.
        EntitySynchronizer.resetForTest();
        List<Person> people = VaultManager.listPeople();
        assertEquals(1, people.size());
        Path file = personFile("person-fm-1");
        String content = Files.readString(file);
        assertTrue(content.contains("client: ACME"),
                "custom metadata must survive vault loss (SQLite store): " + content);
    }

    @Test
    void customFieldsCannotOverrideAetherFields() throws Exception {
        VaultManager.savePerson(newPerson("Alice"));
        // Attempt to inject owned keys as "custom" data via an external edit.
        externalEdit(personFile("person-fm-1"),
                "id: evil-id\ntype: note\nname: HACKED");

        List<Person> people = VaultManager.listPeople();
        assertEquals(1, people.size(), "no duplicate entity may be created");
        assertEquals("person-fm-1", people.get(0).getId());
        assertTrue(Files.readString(personFile("person-fm-1")).contains("type: person"));
        // The owned fields stay authoritative after the next AETHER save.
        VaultManager.savePerson(newPerson("Alice"));
        String content = Files.readString(personFile("person-fm-1"));
        assertTrue(content.contains("id: person-fm-1"), content);
        assertFalse(content.contains("id: evil-id"), content);
    }

    @Test
    void priorityIsCustomForProjectButOwnedForTask() throws Exception {
        // "priority" belongs to AETHER on tasks, but is user metadata on
        // projects — the whitelists are per-type.
        Project project = new Project("project-fm-1", "AETHER", null,
                ProjectStatus.ACTIVE, "desc",
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        VaultManager.saveProject(project);
        Path file = VaultManager.getVaultPath().resolve("Projects").resolve("aether.md");
        externalEdit(file, "priority: high");

        VaultManager.saveProject(project);
        String content = Files.readString(file);
        assertTrue(content.contains("priority: high"),
                "priority is user-defined on projects: " + content);
    }

    @Test
    void emptyCustomValueIsPreserved() throws Exception {
        VaultManager.savePerson(newPerson("Alice"));
        externalEdit(personFile("person-fm-1"), "reviewed:\nclient: ACME");

        VaultManager.savePerson(newPerson("Alice"));
        String content = Files.readString(personFile("person-fm-1"));
        assertTrue(content.contains("reviewed:"),
                "an empty custom value is still a user field: " + content);
        assertTrue(content.contains("client: ACME"), content);
    }

    @Test
    void noteCustomFrontmatterSurvivesSave() throws Exception {
        // Note has a different save path (title derivation, original_text) —
        // the merge must cover it too.
        domain.entities.Note note = new domain.entities.Note(
                "note-fm-1", "Ideias", "Corpo da nota",
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        VaultManager.saveNote(note, null);
        Path file = VaultManager.getVaultPath().resolve("Notes").resolve("ideias.md");
        assertTrue(Files.exists(file), file.toString());
        externalEdit(file, "tags: importante");

        VaultManager.saveNote(note, null);
        String content = Files.readString(file);
        assertTrue(content.contains("tags: importante"),
                "custom keys must survive note re-projection: " + content);
        assertTrue(content.contains("title: Ideias"), content);
    }

    @Test
    void removingCustomFieldExternallyIsRespected() throws Exception {
        VaultManager.savePerson(newPerson("Alice"));
        externalEdit(personFile("person-fm-1"), "client: ACME");

        // The user then deletes the key by hand and AETHER re-projects:
        // the removed key must not resurrect from the repository.
        Path file = personFile("person-fm-1");
        String content = Files.readString(file).replace("client: ACME\n", "");
        Files.writeString(file, content);
        EntitySynchronizer.onExternalChange(file);

        VaultManager.savePerson(newPerson("Alice"));
        String after = Files.readString(personFile("person-fm-1"));
        assertFalse(after.contains("client: ACME"),
                "externally removed custom keys must stay removed: " + after);
    }
}
