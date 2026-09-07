import ai.AiActionOrchestrator;
import ai.AiActionType;
import ai.NoteAnalysisService;
import ai.NoteUnderstandingExtractor;
import ai.ParsedAction;
import ai.ResolvedAction;
import ai.TrustLevel;
import domain.entities.ContextEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NoteUnderstandingPipelineTest — valida o pipeline de compreensão de notas:
 * propagação de classificação/confiança/sourceQuote, hard gate de citação
 * literal, tolerância de campos (name→title), preservação da classificação
 * semântica (FACT não vira INFERENCE), e os quatro tipos de entidade.
 */
class NoteUnderstandingPipelineTest {

    private Path tmpDir;

    @BeforeEach
    void setup() throws Exception {
        tmpDir = Files.createTempDirectory("note-pipe-test");
        System.setProperty("aether.data.dir", tmpDir.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
    }

    private static ParsedAction enriched(String classification, double confidence, String evidence) {
        return ParsedAction.of("CREATE_ENTITY", "PERSON",
                Map.of("name", "Manel"), List.of("AETHER"), "", "razão",
                classification, confidence, evidence);
    }

    private static AiActionOrchestrator with(ParsedAction... actions) {
        return new AiActionOrchestrator((um, ar) -> List.of(actions));
    }

    /** Corre o orquestrador no caminho de notas (enforceSourceQuote=true). */
    private static List<ResolvedAction> note(AiActionOrchestrator orch, String note, String label) {
        return orch.orchestrate(note, "", label, true);
    }

    private static List<ResolvedAction> note(AiActionOrchestrator orch, String note) {
        return note(orch, note, "Nota");
    }

    @Test
    void propagatesConfidenceTrustLevelAndSourceContext() {
        String note = "Hoje falei com o Manel, meu colega de trabalho, sobre o AETHER.";
        ParsedAction pa = enriched("FACT", 0.96, "falei com o Manel, meu colega de trabalho");
        List<ResolvedAction> out = note(with(pa), note, "Nota: Reunião");
        assertEquals(1, out.size());
        ResolvedAction ra = out.get(0);
        assertEquals(0.96, ra.proposal.getConfidence(), 0.001);
        assertEquals(TrustLevel.INFERRED, ra.proposal.getTrustLevel());
        String src = ra.proposal.getSourceContext();
        assertTrue(src.contains("Nota: Reunião"), "sourceContext deve conter a fonte: " + src);
        assertTrue(src.contains("Manel"), "sourceContext deve conter a evidência: " + src);
    }

    @Test
    void factMapsToInferredNeverConfirmed() {
        String note = "falei com o Manel sobre o AETHER";
        String ev = "falei com o Manel";
        assertEquals(TrustLevel.INFERRED, note(with(enriched("FACT", 0.9, ev)), note).get(0).proposal.getTrustLevel());
        assertEquals(TrustLevel.INFERRED, note(with(enriched("INFERENCE", 0.8, ev)), note).get(0).proposal.getTrustLevel());
        assertEquals(TrustLevel.SUGGESTED, note(with(enriched("SUGGESTION", 0.5, ev)), note).get(0).proposal.getTrustLevel());
    }

    @Test
    void semanticClassificationPreservedExactly() {
        // A classificação semântica é preservada tal como o modelo a deu —
        // FACT continua FACT mesmo antes de aprovação (não vira INFERENCE).
        String note = "falei com o Manel sobre o AETHER";
        String ev = "falei com o Manel";
        assertEquals("FACT", note(with(enriched("FACT", 0.9, ev)), note).get(0).proposal.getSemanticClassification());
        assertEquals("INFERENCE", note(with(enriched("INFERENCE", 0.8, ev)), note).get(0).proposal.getSemanticClassification());
        assertEquals("SUGGESTION", note(with(enriched("SUGGESTION", 0.5, ev)), note).get(0).proposal.getSemanticClassification());
        assertEquals("SUGGESTION", note(with(enriched("GUESS", 0.3, ev)), note).get(0).proposal.getSemanticClassification());
    }

    @Test
    void unknownClassificationDefaultsToSuggested() {
        String note = "falei com o Manel sobre o AETHER";
        String ev = "falei com o Manel";
        assertEquals(TrustLevel.SUGGESTED, note(with(enriched("GUESS", 0.3, ev)), note).get(0).proposal.getTrustLevel());
        assertEquals(TrustLevel.SUGGESTED, note(with(enriched("", 0.3, ev)), note).get(0).proposal.getTrustLevel());
    }

    @Test
    void noteExtractorReturnsEmptyWhenNoModel() {
        NoteUnderstandingExtractor ext = new NoteUnderstandingExtractor("", List::of);
        assertEquals(List.of(), ext.extract("qualquer nota", "Nota"));
        NoteUnderstandingExtractor ext2 = new NoteUnderstandingExtractor(null, List::of);
        assertEquals(List.of(), ext2.extract("qualquer nota", "Nota"));
    }

    @Test
    void analysisServiceReturnsEmptyWhenNoModel() {
        NoteAnalysisService svc = new NoteAnalysisService("", List::of);
        assertEquals(List.of(), svc.analyze("Hoje falei com o Manel sobre o AETHER.", "Nota"));
        NoteAnalysisService svc2 = new NoteAnalysisService(null, List::of);
        assertEquals(List.of(), svc2.analyze("nota", "Nota"));
    }

    @Test
    void analysisServiceReturnsEmptyForBlankNote() {
        NoteAnalysisService svc = new NoteAnalysisService("llama3", List::of);
        assertEquals(List.of(), svc.analyze("", "Nota"));
        assertEquals(List.of(), svc.analyze(null, "Nota"));
    }

    @Test
    void createdEntityProposalHasCorrectTypeAndEntity() {
        String note = "O Manel é meu colega de trabalho.";
        ParsedAction pa = enriched("FACT", 0.9, "O Manel é meu colega de trabalho");
        ResolvedAction ra = note(with(pa), note).get(0);
        assertEquals(AiActionType.CREATE_ENTITY, ra.proposal.getActionType());
        assertEquals(ContextEntityType.PERSON, ra.proposal.getEntityType());
    }

    // ------------------------------------------------------------------
    // HARD GATE: sourceQuote obrigatório e literal
    // ------------------------------------------------------------------

    @Test
    void rejectsProposalWithoutSourceQuote() {
        ParsedAction pa = ParsedAction.of("CREATE_ENTITY", "PERSON",
                Map.of("name", "Manel"), List.of(), "", "razão", "FACT", 0.9, "");
        assertEquals(0, note(with(pa), "nota sobre o Manel").size());
    }

    @Test
    void rejectsProposalWhenQuoteNotInNote() {
        ParsedAction pa = enriched("FACT", 0.9, "o Manel é de Lisboa");
        assertEquals(0, note(with(pa), "falei com o Manel sobre o AETHER").size());
    }

    @Test
    void acceptsProposalWhenQuoteIsLiteralSubstring() {
        ParsedAction pa = enriched("FACT", 0.9, "  Falei com o Manel  ");
        assertEquals(1, note(with(pa), "hoje falei com o manel sobre o aether").size());
    }

    // ------------------------------------------------------------------
    // Quatro tipos de entidade
    // ------------------------------------------------------------------

    private static ParsedAction entity(String type, Map<String, String> fields,
                                       String classification, double conf, String evidence) {
        return ParsedAction.of("CREATE_ENTITY", type, fields, List.of(), "", "",
                classification, conf, evidence);
    }

    @Test
    void extractsAllFourEntityTypes() {
        String note = "Falei com o João sobre o AETHER. O AETHER é um projeto de IA local. "
                + "Precisamos de terminar a integração do Ollama até sexta-feira. "
                + "Na terça-feira temos uma reunião às 15h para rever o progresso.";
        ParsedAction person = entity("PERSON", Map.of("name", "João"), "FACT", 0.97, "Falei com o João sobre o AETHER");
        ParsedAction project = entity("PROJECT", Map.of("name", "AETHER", "description", "Projeto de IA local"), "FACT", 0.98, "O AETHER é um projeto de IA local");
        ParsedAction task = entity("TASK", Map.of("title", "Terminar a integração do Ollama"), "FACT", 0.95, "Precisamos de terminar a integração do Ollama até sexta-feira");
        ParsedAction event = entity("EVENT", Map.of("title", "Reunião para rever o progresso"), "FACT", 0.93, "Na terça-feira temos uma reunião às 15h para rever o progresso");
        List<ResolvedAction> out = note(with(person, project, task, event), note);
        assertEquals(4, out.size(), "deve extrair os quatro tipos de entidade");
        long types = out.stream().map(r -> r.proposal.getEntityType()).distinct().count();
        assertEquals(4, types, "PERSON, PROJECT, TASK, EVENT todos presentes");
    }

    @Test
    void tolerantNameToTitleForTaskAndEvent() {
        // Se o modelo usou "name" em vez de "title" para TASK/EVENT, o pipeline
        // remapeia (name→title) e NÃO dropa silenciosamente a entidade.
        String note = "Terminar a integração do Ollama é urgente. A reunião de progresso está marcada.";
        ParsedAction task = entity("TASK", Map.of("name", "Terminar a integração do Ollama"), "FACT", 0.9, "Terminar a integração do Ollama é urgente");
        ParsedAction event = entity("EVENT", Map.of("name", "Reunião de progresso"), "FACT", 0.9, "A reunião de progresso está marcada");
        List<ResolvedAction> out = note(with(task, event), note);
        assertEquals(2, out.size());
        assertEquals(ContextEntityType.TASK, out.get(0).proposal.getEntityType());
        assertEquals("Terminar a integração do Ollama", out.get(0).proposal.getFields().get("title"));
        assertEquals(ContextEntityType.EVENT, out.get(1).proposal.getEntityType());
        assertEquals("Reunião de progresso", out.get(1).proposal.getFields().get("title"));
    }

    @Test
    void noArtificialLimitOnManyEntities() {
        // 20 pessoas na mesma nota → 20 propostas (sem limite artificial).
        StringBuilder nb = new StringBuilder("Lista de contactos: ");
        List<ParsedAction> actions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String name = "Pessoa" + i;
            String phrase = "vi a " + name + " hoje";
            nb.append(phrase).append(". ");
            actions.add(entity("PERSON", Map.of("name", name), "FACT", 0.8, phrase));
        }
        List<ResolvedAction> out = note(with(actions.toArray(new ParsedAction[0])), nb.toString());
        assertEquals(20, out.size(), "nenhum limite artificial de propostas");
    }

    @Test
    void projectAndTaskBothExtracted() {
        String note = "O AETHER é um projeto de IA. Precisamos de terminar a integração do Ollama.";
        ParsedAction project = entity("PROJECT", Map.of("name", "AETHER"), "FACT", 0.97, "O AETHER é um projeto de IA");
        ParsedAction task = entity("TASK", Map.of("title", "Terminar a integração do Ollama"), "FACT", 0.95, "Precisamos de terminar a integração do Ollama");
        assertEquals(2, note(with(project, task), note).size());
    }

    @Test
    void eventAndPersonBothExtracted() {
        String note = "O João vai à reunião de progresso amanhã.";
        ParsedAction person = entity("PERSON", Map.of("name", "João"), "FACT", 0.95, "O João vai à reunião");
        ParsedAction event = entity("EVENT", Map.of("title", "Reunião de progresso"), "FACT", 0.9, "reunião de progresso amanhã");
        assertEquals(2, note(with(person, event), note).size());
    }
}
