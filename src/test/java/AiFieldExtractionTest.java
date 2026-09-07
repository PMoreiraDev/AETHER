import ai.ActionExecutor;
import ai.AiActionProposal;
import ai.AiActionType;
import ai.TrustLevel;
import domain.entities.ContextEntityType;
import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o fluxo completo de extração e persistência de campos contextuais:
 * USER MESSAGE → extrator → parser → orchestrator → executor → Vault → Markdown.
 * <p>
 * Garante que a informação contextual claramente presente (ex.: "colega de
 * trabalho") chega ao Markdown final e não se perde por mismatch de chaves
 * (description vs about, date+time vs start, due vs deadline). Cobre Person,
 * Project, Event, Task e Note — não apenas Person.
 */
class AiFieldExtractionTest {

    private Path tmpVault;

    @BeforeEach
    void setup() throws Exception {
        tmpVault = Files.createTempDirectory("aether-field-test");
        System.setProperty("aether.data.dir", tmpVault.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
    }

    private AiActionProposal createProposal(ContextEntityType type, java.util.Map<String, String> fields) {
        AiActionProposal.Builder b = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(type)
                .reason("Test")
                .trustLevel(TrustLevel.SUGGESTED);
        fields.forEach(b::field);
        return b.build();
    }

    /** "Guarda o Manel como meu colega de trabalho." → Person com about. */
    @Test
    void person_descriptionFieldPreservedToMarkdown() {
        // O modelo emite "description" (chave listada nas instruções de extração);
        // o executor normaliza para "about" e o Vault escreve no Markdown.
        AiActionProposal p = createProposal(ContextEntityType.PERSON,
                java.util.Map.of("name", "Manel", "description", "Colega de trabalho"));
        assertTrue(ActionExecutor.execute(p));

        Optional<Person> manel = VaultManager.findPerson("Manel");
        assertTrue(manel.isPresent());
        assertEquals("Colega de trabalho", manel.get().getAbout(),
                "a descrição contextual deve chegar ao campo about da pessoa");

        String md = readVaultFile("People", "Manel");
        assertTrue(md.contains("Colega de trabalho"),
                "o Markdown da pessoa deve conter a descrição");
    }

    /** "Falei com o Manel." → só o nome, sem descrição inventada. */
    @Test
    void person_noDescriptionWhenNotPresent() {
        AiActionProposal p = createProposal(ContextEntityType.PERSON,
                java.util.Map.of("name", "Manel"));
        assertTrue(ActionExecutor.execute(p));
        Person manel = VaultManager.findPerson("Manel").orElseThrow();
        assertEquals("", manel.getAbout(), "não deve inventar descrição inexistente");
    }

    @Test
    void project_descriptionPreserved() {
        AiActionProposal p = createProposal(ContextEntityType.PROJECT,
                java.util.Map.of("name", "Site AETHER", "description", "Redesign do portal"));
        assertTrue(ActionExecutor.execute(p));
        Project proj = VaultManager.findProject("Site AETHER").orElseThrow();
        assertEquals("Redesign do portal", proj.getDescription());
        assertTrue(readVaultFile("Projects", "Site AETHER").contains("Redesign do portal"));
    }

    /** "Cria uma tarefa para terminar o relatório amanhã." → deadline resolvido. */
    @Test
    void task_deadlineAndDescriptionPreserved() {
        AiActionProposal p = createProposal(ContextEntityType.TASK,
                java.util.Map.of("title", "Terminar relatório",
                        "description", "Relatório trimestral",
                        "deadline", "2026-09-07 17:00"));
        assertTrue(ActionExecutor.execute(p));
        Task task = VaultManager.listTasks().get(0);
        assertEquals("Terminar relatório", task.getTitle());
        assertEquals("Relatório trimestral", task.getDescription());
        assertNotNull(task.getDeadline(), "a deadline deve ser persistida");
        assertEquals(LocalDateTime.parse("2026-09-07T17:00:00"), task.getDeadline());
    }

    /** O modelo emite "due" em vez de "deadline" → não se perde. */
    @Test
    void task_dueAliasNormalizedToDeadline() {
        AiActionProposal p = createProposal(ContextEntityType.TASK,
                java.util.Map.of("title", "Revisar código", "due", "2026-09-08"));
        assertTrue(ActionExecutor.execute(p));
        Task task = VaultManager.listTasks().get(0);
        assertNotNull(task.getDeadline(), "o alias 'due' deve ser normalizado para deadline");
    }

    /** "Marca uma reunião com o Manel amanhã às 15:00." → Event com data+hora. */
    @Test
    void event_startDateTimeAndDescriptionPreserved() {
        AiActionProposal p = createProposal(ContextEntityType.EVENT,
                java.util.Map.of("title", "Reunião com Manel",
                        "description", "Reunião de equipa",
                        "location", "Sala 3",
                        "start", "2026-09-07 15:00"));
        assertTrue(ActionExecutor.execute(p));
        Event event = VaultManager.listEvents().get(0);
        assertEquals("Reunião com Manel", event.getTitle());
        assertEquals("Reunião de equipa", event.getDescription());
        assertEquals("Sala 3", event.getLocation());
        assertEquals(LocalDateTime.parse("2026-09-07T15:00:00"), event.getStartDateTime());
    }

    /** O modelo emite "date"+"time" separados → combinados em "start". */
    @Test
    void event_dateAndTimeCombinedIntoStart() {
        AiActionProposal p = createProposal(ContextEntityType.EVENT,
                java.util.Map.of("title", "Workshop",
                        "date", "2026-09-10", "time", "15:00"));
        assertTrue(ActionExecutor.execute(p));
        Event event = VaultManager.listEvents().get(0);
        assertNotNull(event.getStartDateTime(), "date+time devem combinar-se em start");
        assertEquals(LocalDateTime.parse("2026-09-10T15:00:00"), event.getStartDateTime());
    }

    @Test
    void note_contentPreserved() {
        AiActionProposal p = createProposal(ContextEntityType.NOTE,
                java.util.Map.of("content", "Notas da reunião com o Manel."));
        assertTrue(ActionExecutor.execute(p));
        Note note = VaultManager.listNotes().get(0);
        // O conteúdo da nota é preservado no corpo do Markdown (com o cabeçalho
        // derivado do próprio conteúdo à frente).
        assertTrue(note.getContent().contains("Notas da reunião com o Manel."),
                "o conteúdo da nota deve chegar ao Markdown");
    }

    /** O modelo emite "text" em vez de "content" → normalizado. */
    @Test
    void note_textAliasNormalizedToContent() {
        AiActionProposal p = createProposal(ContextEntityType.NOTE,
                java.util.Map.of("text", "Lembrete de pagar contas"));
        assertTrue(ActionExecutor.execute(p));
        Note note = VaultManager.listNotes().get(0);
        assertTrue(note.getContent().contains("Lembrete de pagar contas"),
                "o alias 'text' deve ser normalizado para o conteúdo da nota");
    }

    /** "Reunião com o Manel" → o evento é ligado à pessoa Manel via wikilink. */
    @Test
    void event_relationshipLinksToPerson() {
        // Cria primeiro a pessoa Manel.
        ActionExecutor.execute(createProposal(ContextEntityType.PERSON,
                java.util.Map.of("name", "Manel")));
        // Cria o evento com a relação a Manel.
        AiActionProposal p = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.EVENT)
                .field("title", "Reunião com Manel")
                .field("start", "2026-09-07 15:00")
                .relationships(List.of("Manel"))
                .reason("Test")
                .trustLevel(TrustLevel.SUGGESTED)
                .build();
        assertTrue(ActionExecutor.execute(p));

        String md = readVaultFile("Events", "Reunião_com_Manel");
        assertTrue(md.contains("[[Manel]]"),
                "o Markdown do evento deve conter o wikilink para a pessoa Manel");
    }

    private String readVaultFile(String dir, String fileBaseName) {
        // O nome do ficheiro é sanitizado a partir do nome/título; procuramos
        // pelo id no diretório correspondente para ser robusto a sanitização.
        Path vault = VaultManager.getVaultPath().resolve(dir);
        try (var files = Files.list(vault)) {
            var match = files.filter(f -> f.toString().endsWith(".md")).findFirst();
            if (match.isPresent()) {
                return Files.readString(match.get());
            }
        } catch (Exception ignored) {
            // fall through
        }
        // Fallback: procura pelo nome base aproximado.
        try {
            return Files.readString(vault.resolve(fileBaseName + ".md"));
        } catch (Exception e) {
            throw new AssertionError("Não foi possível ler o ficheiro do vault: " + dir + "/" + fileBaseName, e);
        }
    }
}
