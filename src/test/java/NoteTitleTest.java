import domain.entities.Note;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NoteTitleTest — verifica o suporte ao título explícito na entidade Note e a
 * retrocompatibilidade da persistência no vault:
 * <ul>
 *   <li>displayTitle() prefere o título explícito e deriva do conteúdo quando vazio.</li>
 *   <li>saveNote + parseNote round-trip preserva o título e o conteúdo.</li>
 *   <li>Notas antigas (sem título no frontmatter, com "# Título" no corpo) são
 *       lidas corretamente: o título é recuperado do cabeçalho e o corpo limpo.</li>
 * </ul>
 */
class NoteTitleTest {

    @BeforeEach
    void setupVault() throws Exception {
        Path tmpVault = Files.createTempDirectory("aether-note-test");
        System.setProperty("aether.data.dir", tmpVault.toString());
        VaultManager.initializeVault();
    }

    @Test
    void displayTitle_prefersExplicitTitle() {
        Note note = new Note();
        note.setTitle("Meeting with Ana");
        note.setContent("Random body that should not be the title");
        assertEquals("Meeting with Ana", note.displayTitle());
    }

    @Test
    void displayTitle_derivesFromContentWhenTitleBlank() {
        Note note = new Note();
        note.setContent("First line is the title\nMore content here.");
        assertEquals("First line is the title", note.displayTitle());
    }

    @Test
    void displayTitle_truncatesLongDerivedTitle() {
        Note note = new Note();
        note.setContent("A".repeat(80));
        String title = note.displayTitle();
        assertTrue(title.endsWith("..."));
        assertEquals(53, title.length()); // 50 + "..."
    }

    @Test
    void saveAndParse_preservesTitleAndContent() {
        Note note = new Note();
        note.setTitle("Reunião de Produto");
        note.setContent("Discutir o roadmap do Q4.\n- Definir prioridades\n- Atribuir responsáveis");
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        VaultManager.saveNote(note, null);

        List<Note> notes = VaultManager.listNotes();
        assertEquals(1, notes.size());
        Note loaded = notes.get(0);
        assertEquals("Reunião de Produto", loaded.getTitle());
        assertEquals(note.getContent(), loaded.getContent());
        // O corpo não deve conter o título duplicado como cabeçalho.
        assertFalse(loaded.getContent().startsWith("# Reunião"));
    }

    @Test
    void parseNote_legacyNoteWithoutTitleFrontmatter_recoversTitleFromBody() throws Exception {
        // Simula uma nota antiga: sem "title" no frontmatter, com "# Título" no corpo.
        String legacy = """
                ---
                type: note
                id: legacy-1
                created: 2026-01-01 10:00
                updated: 2026-01-01 10:00
                ---
                # Título Legado

                Conteúdo da nota antiga.
                """;
        Path notesDir = VaultManager.getVaultPath().resolve("Notes");
        Files.createDirectories(notesDir);
        Files.writeString(notesDir.resolve("legacy-1.md"), legacy);

        List<Note> notes = VaultManager.listNotes();
        assertEquals(1, notes.size());
        Note loaded = notes.get(0);
        assertEquals("Título Legado", loaded.getTitle());
        // O corpo não deve incluir o cabeçalho "# Título Legado".
        assertFalse(loaded.getContent().contains("# Título Legado"));
        assertTrue(loaded.getContent().contains("Conteúdo da nota antiga."));
    }
}
