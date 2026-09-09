import domain.entities.Person;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security tests for vault operations:
 * <ul>
 *   <li>deletion by id is CONFINED to the entity folders of the vault —
 *       traversal attempts ({@code ../}) and unknown folders are refused;</li>
 *   <li>the arbitrary-path deletion API ({@code deleteEntity(Path)}) no
 *       longer exists (compile-time guarantee; this test documents the
 *       replacement contract).</li>
 * </ul>
 */
class VaultSecurityTest {

    private Path dataDir;

    @BeforeEach
    void setup() throws Exception {
        dataDir = Files.createTempDirectory("aether-vault-security");
        System.setProperty("aether.data.dir", dataDir.toString());
        VaultManager.initializeVault();
    }

    @Test
    void deleteById_cannotEscapeVaultWithTraversal() throws Exception {
        // A sensitive file OUTSIDE the vault.
        Path outside = dataDir.resolve("segredo.txt");
        Files.writeString(outside, "dado sensível fora do vault");

        // Traversal attempts must be refused.
        assertFalse(VaultManager.deleteEntityById("../segredo", "x"));
        assertFalse(VaultManager.deleteEntityById("../../" + dataDir.getFileName() + "/segredo", "x"));
        assertFalse(VaultManager.deleteEntityById("People/../../segredo", "x"));
        // Absolute paths must be refused.
        assertFalse(VaultManager.deleteEntityById(outside.toAbsolutePath().toString(), "x"));

        // The file is untouched.
        assertTrue(Files.exists(outside), "no file outside the vault may be deleted");
    }

    @Test
    void deleteById_refusesUnknownFolder() {
        // "User" is a real vault folder but not an entity CRUD folder: the
        // delete API must refuse it (no entity deletion from User files).
        assertFalse(VaultManager.deleteEntityById("User", "qualquer"));
        // Unknown folders likewise.
        assertFalse(VaultManager.deleteEntityById("Documents", "qualquer"));
        assertFalse(VaultManager.deleteEntityById("QualquerCoisa", "qualquer"));
        // Blank/missing ids are refused.
        assertFalse(VaultManager.deleteEntityById("People", " "));
        assertFalse(VaultManager.deleteEntityById("People", null));
    }

    @Test
    void deleteById_removesOnlyTheTargetedEntity() {
        Person a = new Person();
        a.setName("Alvo");
        Person b = new Person();
        b.setName("Inocente");
        VaultManager.savePerson(a);
        VaultManager.savePerson(b);

        assertTrue(VaultManager.deleteEntityById("People", a.getId()));
        assertFalse(VaultManager.listPeople().stream()
                        .anyMatch(p -> p.getId().equals(a.getId())),
                "the target must be gone");
        assertTrue(VaultManager.listPeople().stream()
                        .anyMatch(p -> p.getId().equals(b.getId())),
                "other entities must be untouched");
    }
}
