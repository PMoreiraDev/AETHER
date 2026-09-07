package util;

import persistence.AetherPaths;
import persistence.Database;
import session.UserSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * BackupService — proteção de dados do utilizador antes da Private Alpha.
 * <p>
 * Cria um backup local (zip) contendo:
 * <ul>
 *   <li>a base de dados SQLite do AETHER (configuração, perfil, chat sessions);</li>
 *   <li>o vault de notas Markdown (que continua recuperável de forma independente);</li>
 *   <li>configuração de paths do vault / Ollama.</li>
 * </ul>
 * Os backups são locais — nenhum dado sai do dispositivo. Recomendado antes de
 * operações potencialmente destrutivas (eliminar entidades, mudar de vault,
 * migrações).
 * </p>
 *
 * @author AETHER
 */
public final class BackupService {

    private BackupService() {
        // Classe de utilitário estático.
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Pasta onde os backups são guardados, dentro do diretório de dados. */
    public static Path backupDirectory() {
        return AetherPaths.dataDirectory().resolve("backups");
    }

    /**
     * Cria um backup local completo.
     *
     * @return o caminho do ficheiro de backup criado
     * @throws IOException se o backup falhar
     */
    public static Path createBackup() throws IOException {
        Path backups = backupDirectory();
        Files.createDirectories(backups);
        Path zipFile = backups.resolve("aether-backup-" + LocalDateTime.now().format(TS) + ".zip");

        List<Path> sources = new ArrayList<>();
        // SQLite database.
        Path db = Database.getDatabasePath();
        if (Files.isRegularFile(db)) {
            sources.add(db);
        }
        // Vault folder (all markdown).
        Path vault = persistence.VaultManager.getVaultPath();
        if (Files.isDirectory(vault)) {
            sources.add(vault);
        }

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Path src : sources) {
                if (Files.isDirectory(src)) {
                    addDirectory(zos, src, src);
                } else {
                    addFile(zos, src, src.getFileName().toString());
                }
            }
        }
        return zipFile;
    }

    private static void addDirectory(ZipOutputStream zos, Path root, Path current) throws IOException {
        try (var paths = Files.walk(current)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(p)) {
                    String entryName = root.relativize(p).toString().replace('\\', '/');
                    addFile(zos, p, entryName);
                }
            }
        }
    }

    private static void addFile(ZipOutputStream zos, Path file, String entryName) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zos);
        zos.closeEntry();
    }
}
