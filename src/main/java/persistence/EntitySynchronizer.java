package persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;

/**
 * EntitySynchronizer — mantém o repositório SQLite (autoritativo) e a
 * projeção no vault (Obsidian) coerentes.
 * <p>
 * SQLite é a fonte da verdade das entidades desde a migração v3; os ficheiros
 * Markdown do vault são uma projeção para leitura/edição humana no Obsidian.
 * Este sincronizador resolve os dois sentidos:
 * </p>
 * <ul>
 *   <li><b>Vault → SQLite (importação):</b> ficheiros com id desconhecido são
 *       importados (ex.: entidades criadas manualmente no Obsidian);
 *       ficheiros cujo hash difere do último projetado pelo AETHER são
 *       edições externas — o ficheiro vence e é reimportado;</li>
 *   <li><b>SQLite → Vault (projeção):</b> entidades no repositório sem o
 *       ficheiro correspondente são reprojetadas (ex.: vault apagado ou
 *       ficheiro eliminado externamente — os dados NUNCA se perdem).</li>
 * </ul>
 * <p>
 * <b>Politica de eliminação externa:</b> apagar um ficheiro no Obsidian NÃO
 * elimina a entidade — o AETHER nunca destrói dados por omissão. A entidade é
 * reprojetada no arranque seguinte. Para eliminar de facto, usar a UI do
 * AETHER.
 * </p>
 * <p>
 * A sincronização de arranque corre uma vez por sessão (e uma vez por mudança
 * de vault), de forma lazily pelo {@link VaultManager#ensureVault()}.
 * Alterações externas durante a sessão chegam pelo {@link util.VaultFileWatcher}
 * através de {@link #onExternalChange(Path)}.
 * </p>
 *
 * @author AETHER
 */
public final class EntitySynchronizer {

    private static final Logger LOGGER = Logger.getLogger(EntitySynchronizer.class.getName());

    /** Diretórios de entidades: nome da pasta → tipo lógico. */
    static final Map<String, String> ENTITY_DIRS = Map.of(
            "People", "person",
            "Events", "event",
            "Tasks", "task",
            "Notes", "note",
            "Projects", "project");

    /** Guard contra reentrada: a projeção chama saveX → ensureVault → aqui. */
    private static boolean reconciling = false;

    /** Vault já sincronizado nesta sessão (reconcile uma vez por vault path). */
    private static Path lastReconciledVault = null;

    private EntitySynchronizer() {
        // Utility class.
    }

    /**
     * Sincroniza o vault atual com o repositório, UMA vez por sessão/vault.
     * Chamado (de forma barata) pelo {@link VaultManager#ensureVault()}.
     */
    static synchronized void reconcileIfNeeded() {
        if (reconciling) {
            return; // Reentrada: a própria reconciliação está a projetar.
        }
        Path vault = VaultManager.getVaultPath();
        if (vault.equals(lastReconciledVault)) {
            return;
        }
        reconcile();
    }

    /**
     * Sincronização completa vault ↔ repositório. Nunca lança exceção — uma
     * falha de sincronização não pode bloquear a aplicação.
     */
    public static synchronized void reconcile() {
        if (reconciling || !VaultManager.isVaultInitialized()) {
            return;
        }
        reconciling = true;
        try {
            SqliteEntityRepository repo = VaultManager.entityRepository();
            for (Map.Entry<String, String> entry : ENTITY_DIRS.entrySet()) {
                importDirectory(repo, entry.getKey(), entry.getValue());
                projectMissing(repo, entry.getKey(), entry.getValue());
            }
            lastReconciledVault = VaultManager.getVaultPath();
            LOGGER.info("Vault reconciled with entity store.");
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "[AETHER] Entity reconciliation failed: " + e.getMessage());
        } finally {
            reconciling = false;
        }
    }

    /**
     * Importa um único ficheiro alterado externamente (chamado pelo watcher).
     * Só reimporta se o conteúdo diferir do último estado projetado.
     */
    public static void onExternalChange(Path file) {
        if (file == null || !file.toString().endsWith(".md") || !Files.isRegularFile(file)) {
            return;
        }
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        String dirName = parent.getFileName() == null ? "" : parent.getFileName().toString();
        String type = ENTITY_DIRS.get(dirName);
        if (type == null) {
            return; // User/ ou outra pasta: não é entidade CRUD.
        }
        try {
            importFile(VaultManager.entityRepository(), file, type);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "[AETHER] Could not import external change " + file + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Vault → SQLite
    // ------------------------------------------------------------------

    private static void importDirectory(SqliteEntityRepository repo, String dirName, String type) {
        Path dir = VaultManager.getVaultPath().resolve(dirName);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> importFile(repo, p, type));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "[AETHER] Could not list " + dir + " for import: " + e.getMessage());
        }
    }

    /**
     * Importa um ficheiro markdown de entidade para o repositório quando é
     * novo ou foi editado externamente.
     */
    private static void importFile(SqliteEntityRepository repo, Path file, String type) {
        try {
            String content = Files.readString(file);
            Map<String, String> fm = VaultManager.parseFrontmatter(content);
            String id = fm.get("id");
            if (id == null || id.isBlank()) {
                return; // Ficheiro sem id: não é uma entidade AETHER gerida.
            }
            String fileHash = sha256(content);
            String knownHash = repo.projectionHash(id);
            boolean knownEntity = findEntity(repo, type, id) != null;
            if (knownEntity && knownHash != null && knownHash.equals(fileHash)) {
                return; // Projeção intacta — nada a fazer.
            }
            if (!knownEntity) {
                LOGGER.info("Importing new entity from vault: " + file.getFileName());
            } else {
                LOGGER.info("Importing external edit from vault: " + file.getFileName());
            }
            upsertParsed(repo, type, content, fm);
            // Captura as chaves de frontmatter do utilizador (não-AETHER) —
            // o ficheiro vence nas edições externas, portanto o conjunto
            // armazenado substitui o anterior (remove chaves eliminadas à mão).
            repo.setCustomFrontmatter(type, id,
                    CustomFrontmatter.toJson(CustomFrontmatter.extract(type, fm)));
            repo.setProjectionHash(id, fileHash);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "[AETHER] Could not read " + file + " for import: " + e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "[AETHER] Could not import " + file + ": " + e.getMessage());
        }
    }

    private static void upsertParsed(SqliteEntityRepository repo, String type,
                                     String content, Map<String, String> fm) {
        switch (type) {
            case "person" -> {
                Person p = VaultManager.parsePerson(content);
                if (p != null) {
                    repo.upsertPerson(p);
                }
            }
            case "event" -> {
                Event e = VaultManager.parseEvent(content);
                if (e != null) {
                    repo.upsertEvent(e);
                }
            }
            case "task" -> {
                Task t = VaultManager.parseTask(content);
                if (t != null) {
                    repo.upsertTask(t);
                }
            }
            case "note" -> {
                Note n = VaultManager.parseNote(content);
                if (n != null) {
                    String original = fm.getOrDefault("original_text", "");
                    repo.upsertNote(n, original);
                }
            }
            case "project" -> {
                Project p = VaultManager.parseProject(content);
                if (p != null) {
                    repo.upsertProject(p);
                }
            }
            default -> { /* tipo desconhecido: ignorar */ }
        }
    }

    private static Object findEntity(SqliteEntityRepository repo, String type, String id) {
        return switch (type) {
            case "person" -> repo.findPerson(id);
            case "event" -> repo.findEvent(id);
            case "task" -> repo.findTask(id);
            case "note" -> repo.findNote(id);
            case "project" -> repo.findProject(id);
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // SQLite → Vault (projeção)
    // ------------------------------------------------------------------

    private static void projectMissing(SqliteEntityRepository repo, String dirName, String type) {
        // ids presentes no vault
        java.util.Set<String> fileIds = new java.util.HashSet<>();
        Path dir = VaultManager.getVaultPath().resolve(dirName);
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                    try {
                        String id = VaultManager.parseFrontmatter(Files.readString(p)).get("id");
                        if (id != null && !id.isBlank()) {
                            fileIds.add(id);
                        }
                    } catch (IOException ignored) {
                        // Ignorar ficheiros ilegíveis.
                    }
                });
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[AETHER] Could not list " + dir + ": " + e.getMessage());
            }
        }
        // Reprojetar entidades do repositório sem ficheiro correspondente.
        for (Object entity : listEntities(repo, type)) {
            String id = entityId(entity);
            if (id != null && !fileIds.contains(id)) {
                LOGGER.info("Re-projecting entity " + id + " (" + type + ") into vault.");
                projectEntity(entity);
            }
        }
    }

    private static List<?> listEntities(SqliteEntityRepository repo, String type) {
        return switch (type) {
            case "person" -> repo.listPeople();
            case "event" -> repo.listEvents();
            case "task" -> repo.listTasks();
            case "note" -> repo.listNotes();
            case "project" -> repo.listProjects();
            default -> List.of();
        };
    }

    private static String entityId(Object entity) {
        if (entity instanceof Person p) {
            return p.getId();
        }
        if (entity instanceof Event e) {
            return e.getId();
        }
        if (entity instanceof Task t) {
            return t.getId();
        }
        if (entity instanceof Note n) {
            return n.getId();
        }
        if (entity instanceof Project p) {
            return p.getId();
        }
        return null;
    }

    private static void projectEntity(Object entity) {
        // Os saveX do VaultManager escrevem no repositório (idempotente, mesmo
        // id) e projetam o ficheiro — chamá-los aqui é seguro e evita lógica
        // duplicada. A reentrada no reconcile é bloqueada pelo flag.
        if (entity instanceof Person p) {
            VaultManager.savePerson(p);
        } else if (entity instanceof Event e) {
            VaultManager.saveEvent(e);
        } else if (entity instanceof Task t) {
            VaultManager.saveTask(t);
        } else if (entity instanceof Note n) {
            VaultManager.saveNote(n, null);
        } else if (entity instanceof Project p) {
            VaultManager.saveProject(p);
        }
    }

    // ------------------------------------------------------------------
    // Hash
    // ------------------------------------------------------------------

    /** SHA-256 do conteúdo (hex) — deteção de edições externas. */
    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Impossível na JVM padrão; fallback determinístico simples.
            return String.valueOf((content == null ? "" : content).hashCode());
        }
    }

    /** Reinicia o estado de sessão (testes). */
    /** Reinicia o estado de sessão (testes). */
    public static synchronized void resetForTest() {
        lastReconciledVault = null;
        reconciling = false;
    }
}
