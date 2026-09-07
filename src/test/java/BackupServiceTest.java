import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.AetherPreferences;
import util.BackupService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BackupServiceTest — valida a criação de backups locais (zip) que incluem a
 * base de dados e o vault. Nenhum dado sai do dispositivo.
 */
class BackupServiceTest {

    private Path tmpDir;

    @BeforeEach
    void setup() throws Exception {
        tmpDir = Files.createTempDirectory("backup-test");
        System.setProperty("aether.data.dir", tmpDir.toString());
    }

    @Test
    void backupDirectoryIsInsideDataDir() {
        assertEquals(tmpDir.resolve("backups"), BackupService.backupDirectory());
    }

    @Test
    void createBackupProducesZipWithVaultContent() throws Exception {
        // Initialize the vault (creates People/Events/Tasks/Notes under the data dir).
        persistence.VaultManager.initializeVault();
        util.VaultIndex.getInstance().invalidate();
        // Seed a vault task file in the correct location.
        Files.writeString(persistence.VaultManager.getVaultPath().resolve("Tasks").resolve("test-task.md"),
                "# Test\n\nSome content.");

        Path zip = BackupService.createBackup();

        assertTrue(Files.isRegularFile(zip), "backup zip should exist");
        assertTrue(zip.toString().endsWith(".zip"));
        assertTrue(Files.size(zip) > 0, "backup should not be empty");

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            boolean hasTask = zf.stream().anyMatch(e -> e.getName().contains("test-task.md"));
            assertTrue(hasTask, "backup should contain the vault task file");
        }
    }
}
