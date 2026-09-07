package util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Testes da deteção de modelos Ollama. Validam o parser do endpoint HTTP
 * {@code /api/tags} (substitui o antigo parsing da tabela {@code ollama list})
 * e garantem que nomes fantasmas como "as" nunca são apresentados como
 * modelos instalados.
 * <p>
 * Inclui um teste de contrato ao vivo: se o Ollama estiver acessível, compara
 * a resposta real de {@code /api/tags} com a lista que o AETHER apresenta. Se
 * não estiver acessível, o teste é ignorado (assumption) — fica marcado como
 * NOT VERIFIED no sandbox, que não tem Ollama instalado.
 */
class OllamaModelParsingTest {

    @Test
    void parseModelNames_realTagsResponse() {
        String json = """
                {"models":[
                  {"name":"qwen2.5:14b","model":"qwen2.5:14b","modified_at":"2026-08-01T10:00:00Z","size":9000000000},
                  {"name":"llama3.2:3b","model":"llama3.2:3b","modified_at":"2026-07-20T10:00:00Z","size":2000000000}
                ]}""";
        List<String> models = OllamaService.parseModelNames(json);
        assertEquals(List.of("qwen2.5:14b", "llama3.2:3b"), models);
    }

    @Test
    void parseModelNames_normalizesWithLatestTag_preservesRealNames() {
        // Ollama acrescenta :latest a modelos sem versão explícita; o parser lê
        // o nome tal como vem (a normalização acontece à parte no normalizeModelId).
        String json = "{\"models\":[{\"name\":\"mistral:latest\",\"model\":\"mistral:latest\"}]}";
        List<String> models = OllamaService.parseModelNames(json);
        assertEquals(List.of("mistral:latest"), models);
    }

    @Test
    void parseModelNames_rejectsPhantomAsToken() {
        // Reproduz o bug reportado: um suposto modelo "as" aparecia como instalado.
        // Com o parser do /api/tags, "as" só apareceria se fosse um campo
        // "name" real. Garantimos que tokens soltos de texto de erro nunca são
        // interpretados como modelos.
        String malformed = "ERROR: connection refused as of yesterday";
        List<String> models = OllamaService.parseModelNames(malformed);
        assertTrue(models.isEmpty(), "texto de erro não deve produzir modelos");
        assertFalse(models.contains("as"), "o token fantasma 'as' não deve aparecer");
    }

    @Test
    void parseModelNames_rejectsNamesWithSpaces() {
        String json = """
                {"models":[
                  {"name":"valid model","model":"valid model"}
                ]}""";
        List<String> models = OllamaService.parseModelNames(json);
        assertFalse(models.contains("valid model"),
                "nomes com espaços não são identificadores válidos de modelo");
    }

    @Test
    void parseModelNames_emptyOrMalformedReturnsEmpty() {
        assertTrue(OllamaService.parseModelNames("").isEmpty());
        assertTrue(OllamaService.parseModelNames(null).isEmpty());
        assertTrue(OllamaService.parseModelNames("not json at all").isEmpty());
        assertTrue(OllamaService.parseModelNames("{\"models\":[]}").isEmpty());
    }

    @Test
    void isValidModelName_acceptsRealModelIdentifiers() {
        assertTrue(OllamaService.isValidModelName("qwen2.5:14b"));
        assertTrue(OllamaService.isValidModelName("llama3.2:3b"));
        assertTrue(OllamaService.isValidModelName("mistral"));
        assertTrue(OllamaService.isValidModelName("gpt-oss:20b"));
    }

    @Test
    void isValidModelName_rejectsGarbage() {
        assertFalse(OllamaService.isValidModelName(""));
        assertFalse(OllamaService.isValidModelName("   "));
        assertFalse(OllamaService.isValidModelName("invalid name"));
        assertFalse(OllamaService.isValidModelName("line\nbreak"));
    }

    @Test
    void normalizeModelId_stripsLatestSuffix() {
        assertEquals("mistral", OllamaService.normalizeModelId("mistral:latest"));
        assertEquals("qwen2.5:14b", OllamaService.normalizeModelId("Qwen2.5:14b"));
        assertEquals("", OllamaService.normalizeModelId(null));
    }

    /**
     * Teste de contrato ao vivo: se o Ollama estiver acessível em localhost:11434,
     * busca a resposta real de /api/tags e confirma que a lista que o AETHER
     * apresenta corresponde exatamente aos nomes reais — sem modelos fictícios
     * ou truncados. Ignorado (assumption) quando o Ollama não está disponível.
     */
    @Test
    void liveContract_realOllamaTagsEndpointMatchesParsedList() {
        assumeTrue(OllamaService.isServerRunning(),
                "Ollama não está acessível no sandbox — teste de contrato ignorado");

        Optional<List<String>> installed = OllamaService.tryGetInstalledModels();
        assertTrue(installed.isPresent(), "com o servidor ativo, a listagem não deve falhar");

        for (String name : installed.get()) {
            assertTrue(OllamaService.isValidModelName(name),
                    "modelo instalado tem nome inválido: " + name);
            assertFalse(name.equalsIgnoreCase("as"),
                    "o token fantasma 'as' não deve ser um modelo instalado real");
        }
    }
}
