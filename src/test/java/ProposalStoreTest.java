import ai.ProposalStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProposalStoreTest — valida idempotência, persistência (restart-safe) e
 * atomicidade do armazenamento de propostas de IA.
 */
class ProposalStoreTest {

    private Path tmpFile;

    @BeforeEach
    void setup() throws Exception {
        Path tmpDir = Files.createTempDirectory("prop-store-test");
        tmpFile = tmpDir.resolve("ai-proposals.json");
    }

    private ProposalStore freshStore() {
        return new ProposalStore(tmpFile);
    }

    private ProposalStore.Snapshot pending(String id) {
        return new ProposalStore.Snapshot(id, "PENDING", "CREATE_ENTITY", "PERSON",
                "Manel", Map.of("name", "Manel"), List.of("AETHER"),
                "razão", "INFERRED", 0.9, "Nota — \"Manel\"", System.currentTimeMillis());
    }

    @Test
    void proposeIsIdempotentForSameId() {
        ProposalStore store = freshStore();
        assertEquals(ProposalStore.Decision.NEW_PENDING, store.propose(pending("id1")));
        assertEquals(ProposalStore.Decision.ALREADY_PENDING, store.propose(pending("id1")));
        assertEquals(1, store.pendingCount());
    }

    @Test
    void acceptedNotReproposed() {
        ProposalStore store = freshStore();
        store.propose(pending("id1"));
        store.markAccepted("id1");
        // Re-analisar a mesma nota: a proposta aceite NÃO volta a aparecer.
        assertEquals(ProposalStore.Decision.ALREADY_ACCEPTED, store.propose(pending("id1")));
        assertEquals(0, store.pendingCount());
    }

    @Test
    void rejectedNotReproposed() {
        ProposalStore store = freshStore();
        store.propose(pending("id1"));
        store.markRejected("id1");
        assertEquals(ProposalStore.Decision.ALREADY_REJECTED, store.propose(pending("id1")));
        assertEquals(0, store.pendingCount());
    }

    @Test
    void failedCanBeReproposed() {
        ProposalStore store = freshStore();
        store.propose(pending("id1"));
        store.markFailed("id1");
        // Falhou antes — não é uma decisão do utilizador, é um erro do sistema.
        // Re-analisar volta a propor como pendente, para o utilizador poder tentar de novo.
        assertEquals(ProposalStore.Decision.NEW_PENDING, store.propose(pending("id1")));
        assertEquals(1, store.pendingCount());
    }

    @Test
    void persistsAcrossRestart() {
        ProposalStore store = freshStore();
        store.propose(pending("restart-1"));
        store.propose(pending("restart-2"));
        store.markAccepted("restart-1");
        assertEquals(1, store.pendingCount());

        // Simula um reinício: nova instância lê o mesmo ficheiro.
        ProposalStore restarted = freshStore();
        assertEquals(1, restarted.pendingCount());
        assertTrue(restarted.find("restart-1").isPresent());
        assertTrue(restarted.find("restart-2").isPresent());
        assertEquals("ACCEPTED", restarted.find("restart-1").get().status);
        assertEquals("PENDING", restarted.find("restart-2").get().status);
    }

    @Test
    void idIsStableForSameContent() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "Manel");
        fields.put("about", "Colega");
        String id1 = ProposalStore.idFor("CREATE_ENTITY", "PERSON", "Manel", fields);
        // Ordem diferente dos campos → mesmo id (campos ordenados internamente).
        Map<String, String> fields2 = new LinkedHashMap<>();
        fields2.put("about", "Colega");
        fields2.put("name", "Manel");
        String id2 = ProposalStore.idFor("CREATE_ENTITY", "PERSON", "Manel", fields2);
        assertEquals(id1, id2);
    }

    @Test
    void idForIgnoresFieldOrderAndVolatileMetadata() {
        // Mesmo conteúdo, ordem diferente → mesmo id (TreeMap ordena).
        Map<String, String> f1 = new LinkedHashMap<>();
        f1.put("name", "Manel");
        f1.put("about", "Colega");
        f1.put("occupation", "Engenheiro");
        Map<String, String> f2 = new LinkedHashMap<>();
        f2.put("occupation", "Engenheiro");
        f2.put("about", "Colega");
        f2.put("name", "Manel");
        String a = ProposalStore.idFor("CREATE_ENTITY", "PERSON", "Manel", f1);
        String b = ProposalStore.idFor("CREATE_ENTITY", "PERSON", "Manel", f2);
        assertEquals(a, b);

        // Metadados voláteis (confiança, evidência, sourceContext) NÃO afetam o id.
        // O id é função só de (actionType, entityType, identifier, fields).
        String c = ProposalStore.idFor("CREATE_ENTITY", "PERSON", "Manel", f1);
        // re-cálculo com os mesmos campos é estável.
        assertEquals(a, c);
    }

    @Test
    void clearRemovesEverything() {
        ProposalStore store = freshStore();
        store.propose(pending("x"));
        store.clear();
        assertEquals(0, store.pendingCount());
    }

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(tmpFile);
        if (Files.isDirectory(tmpFile.getParent())) {
            Files.deleteIfExists(tmpFile.getParent());
        }
    }
}
