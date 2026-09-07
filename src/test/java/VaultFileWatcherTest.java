import domain.entities.Person;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import util.VaultFileWatcher;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VaultFileWatcher}: external (Obsidian) edits to vault files
 * are detected and invalidate the {@link VaultIndex}, while internal writes by
 * AETHER itself are suppressed (no sync loop).
 */
class VaultFileWatcherTest {

    private Path tmpVault;

    @BeforeEach
    void setup() throws Exception {
        tmpVault = Files.createTempDirectory("aether-watcher-test");
        System.setProperty("aether.data.dir", tmpVault.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
        VaultFileWatcher.getInstance().start();
    }

    @AfterEach
    void teardown() {
        VaultFileWatcher.getInstance().stop();
    }

    @Test
    void externalEdit_invalidatesIndex() throws Exception {
        Person p = new Person();
        p.setName("Original Name");
        VaultManager.savePerson(p);
        assertEquals(1, VaultIndex.getInstance().people().size());
        assertEquals("Original Name", VaultIndex.getInstance().people().get(0).getName());

        // Simulate an external edit (Obsidian): modify the markdown file directly
        // without going through VaultManager. Wait past the internal-write
        // suppression window so the watcher treats this as external, not as an
        // echo of the save above.
        Thread.sleep(1700);
        Path personFile = tmpVault.resolve("AETHER-Vault").resolve("People").resolve("original-name.md");
        assertTrue(Files.exists(personFile), "person file should exist");
        String original = Files.readString(personFile);
        String edited = original.replace("Original Name", "Edited Externally");
        Files.writeString(personFile, edited);

        // Wait for the watcher to process the event (polls every 1s).
        await(() -> {
            var people = VaultIndex.getInstance().people();
            return !people.isEmpty() && people.get(0).getName().equals("Edited Externally");
        }, 6000);
    }

    @Test
    void internalWrite_doesNotCauseSyncLoop() {
        long versionBefore = VaultIndex.getInstance().version();
        Person p = new Person();
        p.setName("Loop Test");
        VaultManager.savePerson(p);
        long versionAfter = VaultIndex.getInstance().version();
        // The internal write invalidates the index exactly once (no loop).
        assertTrue(versionAfter > versionBefore, "index version should advance after internal write");
        assertEquals(1, VaultIndex.getInstance().people().size());
    }

    /** Awaits a condition for up to timeoutMs, checking every 100ms. */
    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        // Final check to surface a clear assertion failure.
        assertTrue(condition.getAsBoolean(), "condition not met within " + timeoutMs + "ms");
    }
}
