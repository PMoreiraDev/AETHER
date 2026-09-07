import ai.AiActionOrchestrator;
import ai.AiActionProposal;
import ai.AiActionType;
import ai.ActionExtractor;
import ai.JsonActionParser;
import ai.ParsedAction;
import ai.ResolvedAction;
import domain.entities.ContextEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiOrchestratorAndParserTest — valida o parser JSON defensivo e o
 * orquestrador (com extrator falso, sem Ollama). Garante que o modelo nunca
 * consegue produzir uma ação perigosa/inexecutável: JSON malformado → nenhuma
 * ação; entidades desconhecidas → descartadas; alvos inexistentes → descartados.
 */
class AiOrchestratorAndParserTest {

    private Path tmpDir;

    @BeforeEach
    void setup() throws Exception {
        tmpDir = Files.createTempDirectory("orch-test");
        System.setProperty("aether.data.dir", tmpDir.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
    }

    // ---- Parser ----

    @Test
    void parserParsesValidActionArray() {
        String json = """
                [
                  {"action":"CREATE_ENTITY","entity":"TASK","fields":{"title":"Preparar apresentação","deadline":"2026-09-06"},"relationships":["AETHER"],"target":"","reason":"O utilizador pediu."},
                  {"action":"LINK_ENTITIES","entity":"TASK","relationships":["João Costa"],"target":"Preparar apresentação","reason":"Associar à pessoa."}
                ]
                """;
        List<ParsedAction> actions = JsonActionParser.parse(json);
        assertEquals(2, actions.size());
        assertEquals("CREATE_ENTITY", actions.get(0).action);
        assertEquals("TASK", actions.get(0).entity);
        assertEquals("Preparar apresentação", actions.get(0).fields.get("title"));
        assertEquals(List.of("AETHER"), actions.get(0).relationships);
        assertEquals("LINK_ENTITIES", actions.get(1).action);
    }

    @Test
    void parserIgnoresMalformedJsonSafely() {
        assertEquals(List.of(), JsonActionParser.parse(null));
        assertEquals(List.of(), JsonActionParser.parse(""));
        assertEquals(List.of(), JsonActionParser.parse("not json at all"));
        // missing action/entity → skipped
        assertEquals(List.of(), JsonActionParser.parse("[{\"entity\":\"TASK\",\"fields\":{}}]"));
        assertEquals(List.of(), JsonActionParser.parse("[{\"action\":\"CREATE_ENTITY\",\"fields\":{}}]"));
        // unknown action/entity strings are returned raw (parser is schema-agnostic);
        // the orchestrator is responsible for discarding unknown enum values.
        assertEquals(1, JsonActionParser.parse("[{\"action\":\"EXPLODE\",\"entity\":\"TASK\",\"fields\":{\"title\":\"x\"}}]").size());
        assertEquals(1, JsonActionParser.parse("[{\"action\":\"CREATE_ENTITY\",\"entity\":\"ROCKET\",\"fields\":{\"title\":\"x\"}}]").size());
    }

    @Test
    void parserHandlesDiacriticsAndEscapes() {
        String json = "[{\"action\":\"CREATE_ENTITY\",\"entity\":\"PERSON\",\"fields\":{\"name\":\"João Costa\"},\"reason\":\"Pediu.\"}]";
        List<ParsedAction> actions = JsonActionParser.parse(json);
        assertEquals(1, actions.size());
        assertEquals("João Costa", actions.get(0).fields.get("name"));
    }

    // ---- Orchestrator with fake extractor ----

    private ActionExtractor fakeExtractor(List<ParsedAction> actions) {
        return (userMessage, reply) -> actions;
    }

    @Test
    void orchestratorConvertsParsedCreateToProposal() {
        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "TASK",
                Map.of("title", "Preparar apresentação"), List.of(), "", "Pediste.");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("cria uma tarefa", "Claro.");
        assertEquals(1, out.size());
        ResolvedAction ra = out.get(0);
        assertEquals(AiActionType.CREATE_ENTITY, ra.proposal.getActionType());
        assertEquals(ContextEntityType.TASK, ra.proposal.getEntityType());
        assertEquals("Preparar apresentação", ra.proposal.getFields().get("title"));
    }

    @Test
    void orchestratorDiscardsUnknownActionAndEntity() {
        ParsedAction bad1 = ParsedAction.of("EXPLODE", "TASK", Map.of("title", "x"), List.of(), "", "");
        ParsedAction bad2 = ParsedAction.of("CREATE_ENTITY", "ROCKET", Map.of("title", "x"), List.of(), "", "");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(bad1, bad2)));
        assertEquals(List.of(), orch.orchestrate("x", "y"));
    }

    @Test
    void orchestratorDiscardsUpdateOnUnknownTarget() {
        ParsedAction pa = ParsedAction.of("UPDATE_ENTITY", "TASK",
                Map.of("title", "Novo título"), List.of(), "Tarefa Inexistente", "");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        assertEquals(List.of(), orch.orchestrate("x", "y"));
    }

    @Test
    void orchestratorResolvesUpdateTargetByName() {
        // Seed a task named "Reunião".
        domain.entities.Task t = new domain.entities.Task();
        t.setTitle("Reunião");
        VaultManager.saveTask(t);
        VaultIndex.getInstance().invalidate();

        ParsedAction pa = ParsedAction.of("UPDATE_ENTITY", "TASK",
                Map.of("title", "Reunião com cliente"), List.of(), "Reunião", "Atualiza o título.");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("muda o título da reunião", "Ok.");
        assertEquals(1, out.size());
        assertNotNull(out.get(0).proposal.getEntityId());
        assertFalse(out.get(0).proposal.getEntityId().isBlank());
    }

    @Test
    void orchestratorDetectsExactDuplicateOnCreate() {
        domain.entities.Person p = new domain.entities.Person();
        p.setName("João Costa");
        VaultManager.savePerson(p);
        VaultIndex.getInstance().invalidate();

        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "PERSON",
                Map.of("name", "João Costa"), List.of(), "", "Criar pessoa.");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("cria o joão costa", "Ok.");
        assertEquals(1, out.size());
        assertTrue(out.get(0).hasDuplicate());
        assertEquals(ai.DuplicateResolver.Outcome.EXACT_DUPLICATE, out.get(0).duplicate.outcome);
    }

    @Test
    void orchestratorNoDuplicateForNewName() {
        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "PROJECT",
                Map.of("name", "Projeto Novo"), List.of(), "", "");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("cria o projeto", "Ok.");
        assertEquals(1, out.size());
        assertFalse(out.get(0).hasDuplicate());
    }

    // ---- Alias normalization through the orchestrator (production path) ----
    // Antes da correção, propostas com alias de campo (description, due, text,
    // date+time) eram rejeitadas pela validação e nunca chegavam ao utilizador
    // para aprovação. Estes testes confirmam que agora passam a validação e
    // chegam como propostas com campos canónicos.

    @Test
    void orchestratorNormalizesPersonDescriptionAliasToAbout() {
        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "PERSON",
                Map.of("name", "Manel", "description", "Colega de trabalho"), List.of(), "", "");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("guarda o manel como colega", "Ok.");
        assertEquals(1, out.size(), "a proposta com alias 'description' não deve ser descartada");
        AiActionProposal proposal = out.get(0).proposal;
        assertEquals("Colega de trabalho", proposal.getFields().get("about"),
                "o alias 'description' deve ser normalizado para 'about'");
        assertFalse(proposal.getFields().containsKey("description"),
                "o alias original deve ser removido após normalização");
    }

    @Test
    void orchestratorNormalizesTaskDueAliasToDeadline() {
        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "TASK",
                Map.of("title", "Revisar código", "due", "2026-09-08"), List.of(), "", "");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("cria tarefa", "Ok.");
        assertEquals(1, out.size());
        assertEquals("2026-09-08", out.get(0).proposal.getFields().get("deadline"));
    }

    @Test
    void orchestratorNormalizesNoteTextAliasToContent() {
        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "NOTE",
                Map.of("text", "Lembrete de pagar contas"), List.of(), "", "");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("nota rápida", "Ok.");
        assertEquals(1, out.size(), "a nota com alias 'text' não deve ser descartada");
        assertEquals("Lembrete de pagar contas", out.get(0).proposal.getFields().get("content"));
    }

    @Test
    void orchestratorCombinesEventDateAndTimeIntoStart() {
        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "EVENT",
                Map.of("title", "Reunião", "date", "2026-09-07", "time", "15:00"), List.of(), "", "");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("marca reunião", "Ok.");
        assertEquals(1, out.size());
        assertEquals("2026-09-07 15:00", out.get(0).proposal.getFields().get("start"));
    }
}
