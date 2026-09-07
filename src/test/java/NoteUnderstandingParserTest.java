import ai.JsonActionParser;
import ai.NoteUnderstandingExtractor;
import ai.ParsedAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NoteUnderstandingParserTest — valida o parse defensivo dos campos
 * enriquecidos (confidence, classification, evidence) e a construção dos
 * prompts do NoteUnderstandingExtractor (sem chamar o Ollama).
 */
class NoteUnderstandingParserTest {

    @Test
    void parserExtractsConfidenceClassificationEvidence() {
        String json = """
                [
                  {"action":"CREATE_ENTITY","entity":"PERSON",
                   "fields":{"name":"Manel"},
                   "classification":"FACT","confidence":0.96,
                   "evidence":"O Manel é meu colega de trabalho"}
                ]
                """;
        List<ParsedAction> actions = JsonActionParser.parse(json);
        assertEquals(1, actions.size());
        ParsedAction pa = actions.get(0);
        assertEquals("FACT", pa.classification);
        assertEquals(0.96, pa.confidence, 0.001);
        assertEquals("O Manel é meu colega de trabalho", pa.evidence);
    }

    @Test
    void parserClampsConfidenceOutOfRange() {
        String json = """
                [{"action":"CREATE_ENTITY","entity":"PERSON",
                  "fields":{"name":"Ana"},"confidence":1.9,"classification":"INFERENCE"}]
                """;
        ParsedAction pa = JsonActionParser.parse(json).get(0);
        assertEquals(1.0, pa.confidence, 0.001);
    }

    @Test
    void parserDefaultsWhenEnrichedFieldsAbsent() {
        // Sem classification/confidence/evidence → retrocompatível.
        String json = """
                [{"action":"CREATE_ENTITY","entity":"TASK",
                  "fields":{"title":"Estudar"}}]
                """;
        ParsedAction pa = JsonActionParser.parse(json).get(0);
        assertEquals("", pa.classification);
        assertEquals(0.0, pa.confidence, 0.001);
        assertEquals("", pa.evidence);
    }

    @Test
    void parserToleratesMalformedConfidence() {
        String json = """
                [{"action":"CREATE_ENTITY","entity":"PERSON",
                  "fields":{"name":"X"},"confidence":"not-a-number"}]
                """;
        ParsedAction pa = JsonActionParser.parse(json).get(0);
        assertEquals(0.0, pa.confidence, 0.001);
    }

    @Test
    void parserHandlesAlternateClassificationKey() {
        String json = """
                [{"action":"CREATE_ENTITY","entity":"PERSON",
                  "fields":{"name":"X"},"class":"SUGGESTION"}]
                """;
        ParsedAction pa = JsonActionParser.parse(json).get(0);
        assertEquals("SUGGESTION", pa.classification);
    }

    @Test
    void noteUnderstandingInstructionsContainSafetyRules() {
        String instr = NoteUnderstandingExtractor.buildInstructions();
        assertNotNull(instr);
        // Regras não-negociáveis presentes no prompt:
        assertTrue(instr.contains("NEVER invent"), "prompt deve proibir invenção");
        assertTrue(instr.contains("FACT") && instr.contains("INFERENCE") && instr.contains("SUGGESTION"),
                "prompt deve definir as 3 classificações");
        assertTrue(instr.contains("evidence"), "prompt deve pedir evidência");
        assertTrue(instr.contains("confidence"), "prompt deve pedir confiança");
        assertTrue(instr.contains("Europe/Lisbon") || instr.contains("current date/time"),
                "prompt deve referenciar data/fuso atuais");
    }

    @Test
    void noteUnderstandingPromptIncludesExistingEntities() {
        String prompt = NoteUnderstandingExtractor.buildPrompt(
                "Hoje falei com o Manel sobre o projeto AETHER.",
                "Nota: Reunião", "2026-09-06 12:00 (Sunday, WEST)",
                List.of("Manel Silva", "Ana Rocha"), List.of("AETHER"));
        assertTrue(prompt.contains("Manel Silva"), "prompt deve listar pessoas existentes");
        assertTrue(prompt.contains("AETHER"), "prompt deve listar projetos existentes");
        assertTrue(prompt.contains("Manel"), "prompt deve incluir o texto da nota");
    }
}
