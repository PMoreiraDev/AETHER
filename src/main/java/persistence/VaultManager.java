package persistence;

import java.util.logging.Level;
import java.util.logging.Logger;

import domain.entities.AetherEntity;
import domain.entities.ContextEntityType;
import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import domain.entities.TaskPriority;
import domain.entities.TaskStatus;
import domain.entities.ProjectStatus;
import session.UserSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.stream.Stream;

/**
 * VaultManager — fachada única para as entidades do AETHER (Person, Event,
 * Task, Note, Project).
 * <p>
 * <b>Modelo de propriedade de dados (desde a migração de esquema v3):</b>
 * o repositório SQLite ({@link SqliteEntityRepository}) é a FONTE DA
 * VERDADE; o vault de ficheiros markdown é uma PROJEÇÃO otimizada para
 * leitura e edição humana no Obsidian. Toda a mutação passa por esta classe:
 * grava no SQLite primeiro (por id estável) e só depois projeta o ficheiro.
 * Se o vault falhar, ficar indisponível ou for apagado, os dados continuam
 * intactos no SQLite e a projeção é recriada pelo {@link EntitySynchronizer}
 * (no arranque) ou pelo watcher (em tempo real).
 * </p>
 * <p>
 * Cada entidade é projetada como um ficheiro .md com frontmatter YAML
 * (dados estruturados, id estável) e wikilinks {@code [[ ]]} (relações).
 * Edições feitas no Obsidian são reimportadas para o SQLite via comparação
 * de hash — o AETHER continua a funcionar com ou sem Obsidian.
 * </p>
 *
 * @author Paulo Moreira
 * @version 2.0
 */
public final class VaultManager {
    private static final Logger LOGGER = Logger.getLogger(VaultManager.class.getName());


    /** Nome da pasta raiz do vault. */
    private static final String VAULT_FOLDER = "AETHER-Vault";

    /** Subpastas do vault. */
    private static final String PEOPLE_DIR = "People";
    private static final String EVENTS_DIR = "Events";
    private static final String TASKS_DIR = "Tasks";
    private static final String NOTES_DIR = "Notes";
    private static final String PROJECTS_DIR = "Projects";

    /** Extensão dos ficheiros. */
    private static final String MD_EXT = ".md";

    /** Separador do frontmatter YAML. */
    private static final String FM_DELIMITER = "---";

    /** Pattern para extrair wikilinks [[Nome]] do conteúdo markdown. */
    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    /** Formato de data para o frontmatter. */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Formato de data e hora para o frontmatter. */
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ------------------------------------------------------------------
    // Vault path
    // ------------------------------------------------------------------

    /**
     * Devolve o caminho base do vault.
     * <p>
     * O vault é criado dentro do diretório de dados do AETHER.
     * </p>
     *
     * @return o caminho do vault
     */
    public static Path getVaultPath() {
        String override = session.UserSession.getInstance().getAppSettings().getVaultPathOverride();
        if (override != null && !override.isBlank()) {
            return java.nio.file.Paths.get(override).toAbsolutePath().normalize();
        }
        Path dataDir = AetherPaths.dataDirectory();
        return dataDir.resolve(VAULT_FOLDER);
    }

    /**
     * Verifica se o vault já foi inicializado.
     *
     * @return {@code true} se o vault existir e tiver a estrutura de pastas
     */
    public static boolean isVaultInitialized() {
        Path vault = getVaultPath();
        return Files.isDirectory(vault)
                && Files.isDirectory(vault.resolve(PEOPLE_DIR))
                && Files.isDirectory(vault.resolve(EVENTS_DIR))
                && Files.isDirectory(vault.resolve(TASKS_DIR))
                && Files.isDirectory(vault.resolve(NOTES_DIR))
                // A pasta Projects fazia falta aqui: vaults verificados como
                // "inicializados" sem ela faziam listProjects() devolver
                // sempre uma lista vazia (a pasta nunca era criada em
                // ensureVault(), porque isVaultInitialized() já dizia que
                // sim), pelo que os projetos nunca chegavam ao contexto da IA.
                && Files.isDirectory(vault.resolve(PROJECTS_DIR));
    }

    /**
     * Cria a estrutura de pastas do vault.
     *
     * @return {@code true} se o vault foi criado ou já existia
     */
    public static boolean initializeVault() {
        Path vault = getVaultPath();
        try {
            Files.createDirectories(vault.resolve(PEOPLE_DIR));
            Files.createDirectories(vault.resolve(EVENTS_DIR));
            Files.createDirectories(vault.resolve(TASKS_DIR));
            Files.createDirectories(vault.resolve(NOTES_DIR));
            Files.createDirectories(vault.resolve(PROJECTS_DIR));
            // Pasta do UTILIZADOR (spec #14): ficheiros do perfil em markdown,
            // sincronizados pelo UserVaultSync com semântica merge. Não entra
            // em isVaultInitialized() para manter retrocompatibilidade com
            // vaults existentes (é criada aqui e re-sincronizada no arranque).
            Files.createDirectories(vault.resolve(UserVaultSync.USER_DIR));
            // Pastas reservadas da estrutura canónica do vault (spec #14):
            // ainda sem CRUD de domínio dedicado, mas a estrutura existe desde
            // o arranque para o utilizador as povoar manualmente no Obsidian.
            Files.createDirectories(vault.resolve("Organizations"));
            Files.createDirectories(vault.resolve("Documents"));
            Files.createDirectories(vault.resolve("Relationships"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Person CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda uma pessoa. SQLite é a fonte da verdade (o repositório é
     * escrito PRIMEIRO); o ficheiro markdown no vault é uma projeção para o
     * Obsidian — se a projeção falhar, a entidade continua guardada e o
     * ficheiro é recriado na sincronização seguinte.
     *
     * @param person a pessoa a guardar
     * @return o caminho do ficheiro projetado no vault
     */
    public static Path savePerson(Person person) {
        ensureVault();
        entityRepository().upsertPerson(person);
        String filename = sanitizeFilename(person.getName()) + MD_EXT;
        Path dir = getVaultPath().resolve(PEOPLE_DIR);
        Path file = dir.resolve(filename);

        Map<String, String> frontmatter = new HashMap<>();
        frontmatter.put("type", "person");
        frontmatter.put("id", person.getId());
        frontmatter.put("name", person.getName());
        if (person.getBirthDate() != null) {
            frontmatter.put("birthday", person.getBirthDate().format(DATE_FMT));
        }
        frontmatter.put("occupation", person.getOccupation());
        frontmatter.put("about", person.getAbout());
        frontmatter.put("created", person.getCreatedAt() != null ? person.getCreatedAt().format(DATETIME_FMT) : "");
        frontmatter.put("updated", person.getUpdatedAt() != null ? person.getUpdatedAt().format(DATETIME_FMT) : "");

        // Preserva chaves de frontmatter definidas pelo utilizador (spec:
        // chaves desconhecidas DEVEM sobreviver às re-projeções). Antes de
        // removeStaleFiles para que o ficheiro antigo (rename) ainda exista.
        mergeCustomFrontmatter("person", PEOPLE_DIR, person.getId(), frontmatter);
        removeStaleFiles(dir, person.getId(), filename);

        StringBuilder body = new StringBuilder();
        body.append("# ").append(person.getName()).append("\n\n");
        if (!person.getAbout().isBlank()) {
            body.append(person.getAbout()).append("\n");
        }

        return project(file, frontmatter, body.toString());
    }

    /**
     * Lista todas as pessoas (do repositório SQLite — fonte da verdade;
     * sincronizado com o vault no arranque e pelo watcher).
     *
     * @return lista de pessoas, vazia se não houver
     */
    public static List<Person> listPeople() {
        ensureVault(); // sincroniza vault ↔ SQLite (uma vez por sessão/vault)
        return entityRepository().listPeople();
    }

    /**
     * Procura uma pessoa pelo nome (match exato ou parcial, case-insensitive).
     *
     * @param name o nome a procurar
     * @return a pessoa encontrada, ou empty
     */
    public static Optional<Person> findPerson(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String lowerName = name.toLowerCase().trim();
        return listPeople().stream()
                .filter(p -> p.getName().toLowerCase().contains(lowerName)
                        || lowerName.contains(p.getName().toLowerCase()))
                .findFirst();
    }

    // ------------------------------------------------------------------
    // Event CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda um evento. SQLite primeiro (fonte da verdade); vault como
     * projeção.
     *
     * @param event o evento a guardar
     * @return o caminho do ficheiro projetado no vault
     */
    public static Path saveEvent(Event event) {
        ensureVault();
        entityRepository().upsertEvent(event);
        String filename = sanitizeFilename(event.getTitle()) + MD_EXT;
        Path dir = getVaultPath().resolve(EVENTS_DIR);
        Path file = dir.resolve(filename);

        Map<String, String> fm = new HashMap<>();
        fm.put("type", "event");
        fm.put("id", event.getId());
        fm.put("title", event.getTitle());
        if (event.getStartDateTime() != null) {
            fm.put("start", event.getStartDateTime().format(DATETIME_FMT));
        }
        if (event.getEndDateTime() != null) {
            fm.put("end", event.getEndDateTime().format(DATETIME_FMT));
        }
        fm.put("location", event.getLocation());
        fm.put("description", event.getDescription());
        fm.put("created", event.getCreatedAt() != null ? event.getCreatedAt().format(DATETIME_FMT) : "");
        fm.put("updated", event.getUpdatedAt() != null ? event.getUpdatedAt().format(DATETIME_FMT) : "");

        mergeCustomFrontmatter("event", EVENTS_DIR, event.getId(), fm);
        removeStaleFiles(dir, event.getId(), filename);

        StringBuilder body = new StringBuilder();
        body.append("# ").append(event.getTitle()).append("\n\n");
        if (!event.getDescription().isBlank()) {
            body.append(event.getDescription()).append("\n");
        }

        return project(file, fm, body.toString());
    }

    /**
     * Lista todos os eventos (do repositório SQLite — fonte da verdade).
     *
     * @return lista de eventos
     */
    public static List<Event> listEvents() {
        ensureVault(); // sincroniza vault ↔ SQLite (uma vez por sessão/vault)
        return entityRepository().listEvents();
    }

    // ------------------------------------------------------------------
    // Task CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda uma tarefa. SQLite primeiro (fonte da verdade); vault como
     * projeção.
     *
     * @param task a tarefa a guardar
     * @return o caminho do ficheiro projetado no vault
     */
    public static Path saveTask(Task task) {
        ensureVault();
        entityRepository().upsertTask(task);
        String filename = sanitizeFilename(task.getTitle()) + MD_EXT;
        Path dir = getVaultPath().resolve(TASKS_DIR);
        Path file = dir.resolve(filename);

        Map<String, String> fm = new HashMap<>();
        fm.put("type", "task");
        fm.put("id", task.getId());
        fm.put("title", task.getTitle());
        if (task.getDeadline() != null) {
            fm.put("deadline", task.getDeadline().format(DATETIME_FMT));
        }
        fm.put("status", task.getStatus().name());
        fm.put("priority", task.getPriority().name());
        fm.put("description", task.getDescription());
        fm.put("created", task.getCreatedAt() != null ? task.getCreatedAt().format(DATETIME_FMT) : "");
        fm.put("updated", task.getUpdatedAt() != null ? task.getUpdatedAt().format(DATETIME_FMT) : "");

        mergeCustomFrontmatter("task", TASKS_DIR, task.getId(), fm);
        removeStaleFiles(dir, task.getId(), filename);

        StringBuilder body = new StringBuilder();
        body.append("# ").append(task.getTitle()).append("\n\n");
        if (!task.getDescription().isBlank()) {
            body.append(task.getDescription()).append("\n");
        }

        return project(file, fm, body.toString());
    }

    /**
     * Lista todas as tarefas (do repositório SQLite — fonte da verdade).
     *
     * @return lista de tarefas
     */
    public static List<Task> listTasks() {
        ensureVault(); // sincroniza vault ↔ SQLite (uma vez por sessão/vault)
        return entityRepository().listTasks();
    }

    // ------------------------------------------------------------------
    // Note CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda uma nota. SQLite primeiro (fonte da verdade, incluindo o texto
     * original em linguagem natural); vault como projeção.
     *
     * @param note a nota a guardar
     * @param originalText o texto original em linguagem natural (se aplicável)
     * @return o caminho do ficheiro projetado no vault
     */
    public static Path saveNote(Note note, String originalText) {
        ensureVault();
        entityRepository().upsertNote(note, originalText);
        String title = note.getTitle() != null && !note.getTitle().isBlank()
                ? note.getTitle().trim() : deriveNoteTitle(note.getContent());
        String filename = sanitizeFilename(title) + MD_EXT;
        Path dir = getVaultPath().resolve(NOTES_DIR);
        Path file = dir.resolve(filename);

        Map<String, String> fm = new HashMap<>();
        fm.put("type", "note");
        fm.put("id", note.getId());
        fm.put("title", title);
        if (originalText != null && !originalText.isBlank()) {
            fm.put("source", "quick_note");
            fm.put("original_text", originalText);
        }
        fm.put("created", note.getCreatedAt() != null ? note.getCreatedAt().format(DATETIME_FMT) : "");
        fm.put("updated", note.getUpdatedAt() != null ? note.getUpdatedAt().format(DATETIME_FMT) : "");

        // O corpo guarda apenas o conteúdo da nota — o título vive no frontmatter.
        mergeCustomFrontmatter("note", NOTES_DIR, note.getId(), fm);
        removeStaleFiles(dir, note.getId(), filename);

        String body = note.getContent() == null ? "" : note.getContent();

        return project(file, fm, body);
    }

    /**
     * Lista todas as notas (do repositório SQLite — fonte da verdade).
     *
     * @return lista de notas
     */
    public static List<Note> listNotes() {
        ensureVault(); // sincroniza vault ↔ SQLite (uma vez por sessão/vault)
        return entityRepository().listNotes();
    }

    // ------------------------------------------------------------------
    // Project CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda um projeto. SQLite primeiro (fonte da verdade); vault como
     * projeção.
     *
     * @param project o projeto a guardar
     * @return o caminho do ficheiro projetado no vault
     */
    public static Path saveProject(Project project) {
        ensureVault();
        entityRepository().upsertProject(project);
        String filename = sanitizeFilename(project.getName()) + MD_EXT;
        Path dir = getVaultPath().resolve(PROJECTS_DIR);
        Path file = dir.resolve(filename);

        Map<String, String> fm = new HashMap<>();
        fm.put("type", "project");
        fm.put("id", project.getId());
        fm.put("name", project.getName());
        if (project.getDeadline() != null) {
            fm.put("deadline", project.getDeadline().format(DATETIME_FMT));
        }
        fm.put("status", project.getStatus().name());
        fm.put("description", project.getDescription());
        fm.put("created", project.getCreatedAt() != null ? project.getCreatedAt().format(DATETIME_FMT) : "");
        fm.put("updated", project.getUpdatedAt() != null ? project.getUpdatedAt().format(DATETIME_FMT) : "");

        mergeCustomFrontmatter("project", PROJECTS_DIR, project.getId(), fm);
        removeStaleFiles(dir, project.getId(), filename);

        StringBuilder body = new StringBuilder();
        body.append("# ").append(project.getName()).append("\n\n");
        if (!project.getDescription().isBlank()) {
            body.append(project.getDescription()).append("\n");
        }

        return project(file, fm, body.toString());
    }

    /**
     * Lista todos os projetos (do repositório SQLite — fonte da verdade).
     *
     * @return lista de projetos
     */
    public static List<Project> listProjects() {
        ensureVault(); // sincroniza vault ↔ SQLite (uma vez por sessão/vault)
        return entityRepository().listProjects();
    }

    /**
     * Procura um projeto pelo nome.
     *
     * @param name o nome a procurar
     * @return o projeto encontrado, ou empty
     */
    public static Optional<Project> findProject(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String lower = name.toLowerCase().trim();
        return listProjects().stream()
                .filter(p -> p.getName().toLowerCase().contains(lower)
                        || lower.contains(p.getName().toLowerCase()))
                .findFirst();
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    /**
     * Devolve o caminho do ficheiro markdown da entidade com o id indicado, no
     * diretório do tipo de entidade dado. Procura por id (não por nome de
     * ficheiro), pelo que funciona mesmo após renomeações.
     *
     * @param dirName o subdiretório do tipo de entidade (ex.: {@link #PEOPLE_DIR})
     * @param id      o id estável da entidade
     * @return o caminho do ficheiro, ou {@code null} se não encontrado
     */
    public static Path findFileById(String dirName, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Path dir = getVaultPath().resolve(dirName);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.toString().endsWith(MD_EXT)).toList()) {
                try {
                    String content = Files.readString(p);
                    if (id.equals(parseFrontmatter(content).get("id"))) {
                        return p;
                    }
                } catch (IOException ignored) {
                    // Ignorar ficheiros ilegíveis.
                }
            }
        } catch (IOException ignored) {
            // Ignorar erros de listagem.
        }
        return null;
    }

    /**
     * Elimina do repositório (fonte da verdade) e do vault (projeção) a
     * entidade com o id indicado, no diretório dado. Ao procurar por id (em
     * vez de por nome de ficheiro) funciona mesmo se a entidade tiver sido
     * renomeada.
     *
     * @param dirName o subdiretório do tipo de entidade (ex.: {@link #PEOPLE_DIR})
     * @param id     o id estável da entidade a eliminar
     * @return {@code true} se a entidade foi eliminada (do repositório e/ou da projeção)
     */
    public static boolean deleteEntityById(String dirName, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        // 1) Eliminar da fonte da verdade (SQLite).
        String type = dirNameToType(dirName);
        if (type == null) {
            return false; // Tipo desconhecido: nunca eliminar.
        }
        boolean repoDeleted = entityRepository().delete(type, id);
        // 2) Eliminar a projeção no vault (por id, confinado à pasta).
        Path vault = getVaultPath();
        Path dir = vault.resolve(dirName).normalize();
        // Confinamento: a pasta-alvo tem de ser um filho DIRETO do vault (não
        // ../, nem caminhos absolutos) — eliminação nunca sai do vault.
        if (!dir.getParent().equals(vault) || !Files.isDirectory(dir)) {
            return repoDeleted;
        }
        boolean deleted = false;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.toString().endsWith(MD_EXT)).toList()) {
                try {
                    String content = Files.readString(p);
                    if (id.equals(parseFrontmatter(content).get("id"))) {
                        deleted |= Files.deleteIfExists(p);
                        util.VaultFileWatcher.markInternalWrite(p);
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "[AETHER] Could not read vault file during delete: " + p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            // Erro de listagem do diretório — não fatal, mas registo de diagnóstico.
            LOGGER.log(Level.WARNING, "[AETHER] Could not list vault directory for delete: " + dir + ": " + e.getMessage());
        }
        if (deleted) {
            util.VaultIndex.getInstance().invalidate();
        }
        return deleted || repoDeleted;
    }

    /**
     * Elimina uma pessoa do vault (procura por id).
     *
     * @param person a pessoa a eliminar
     * @return {@code true} se eliminada com sucesso
     */
    public static boolean deletePerson(Person person) {
        return person != null && deleteEntityById(PEOPLE_DIR, person.getId());
    }

    /**
     * Elimina um evento do vault (procura por id).
     *
     * @param event o evento a eliminar
     * @return {@code true} se eliminado com sucesso
     */
    public static boolean deleteEvent(Event event) {
        return event != null && deleteEntityById(EVENTS_DIR, event.getId());
    }

    /**
     * Elimina uma tarefa do vault (procura por id).
     *
     * @param task a tarefa a eliminar
     * @return {@code true} se eliminada com sucesso
     */
    public static boolean deleteTask(Task task) {
        return task != null && deleteEntityById(TASKS_DIR, task.getId());
    }

    /**
     * Elimina uma nota do vault (procura por id).
     *
     * @param note a nota a eliminar
     * @return {@code true} se eliminada com sucesso
     */
    public static boolean deleteNote(Note note) {
        return note != null && deleteEntityById(NOTES_DIR, note.getId());
    }

    /**
     * Elimina um projeto do vault (procura por id).
     *
     * @param project o projeto a eliminar
     * @return {@code true} se eliminado com sucesso
     */
    public static boolean deleteProject(Project project) {
        return project != null && deleteEntityById(PROJECTS_DIR, project.getId());
    }

    // ------------------------------------------------------------------
    // Wikilinks
    // ------------------------------------------------------------------

    /**
     * Extrai todos os wikilinks {@code [[Nome]]} de um texto markdown.
     *
     * @param markdown o texto a analisar
     * @return lista de nomes referenciados
     */
    public static List<String> extractWikilinks(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<String> links = new ArrayList<>();
        Matcher matcher = WIKILINK_PATTERN.matcher(markdown);
        while (matcher.find()) {
            links.add(matcher.group(1).trim());
        }
        return links;
    }

    /**
     * Adiciona um wikilink {@code [[Nome]]} ao conteúdo markdown.
     *
     * @param markdown o conteúdo atual
     * @param linkName o nome a ligar
     * @return o conteúdo atualizado
     */
    public static String addWikilink(String markdown, String linkName) {
        String link = "[[" + linkName + "]]";
        if (markdown != null && markdown.contains(link)) {
            return markdown;
        }
        StringBuilder sb = new StringBuilder(markdown != null ? markdown : "");
        if (!sb.isEmpty() && !sb.toString().endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("\n## Related\n");
        sb.append("- ").append(link).append("\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Graph data
    // ------------------------------------------------------------------

    /**
     * Lê todos os wikilinks de todos os ficheiros do vault para construir
     * o grafo de relações.
     *
     * @return mapa de nome do ficheiro → lista de nomes referenciados
     */
    public static Map<String, List<String>> buildGraphData() {
        Map<String, List<String>> graph = new HashMap<>();
        if (!isVaultInitialized()) {
            return graph;
        }
        try (Stream<Path> files = Files.walk(getVaultPath())) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(MD_EXT))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            String label = resolveDisplayLabel(p, content);
                            if (label == null || label.isBlank()) {
                                return;
                            }
                            List<String> links = extractWikilinks(content);
                            if (!links.isEmpty()) {
                                graph.put(label, links);
                            }
                        } catch (IOException ignored) {
                            // Ignorar ficheiros ilegíveis.
                        }
                    });
        } catch (IOException e) {
            return graph;
        }
        return graph;
    }

    /**
     * Resolve o nome visível de uma entidade a partir do seu ficheiro, para
     * servir de chave consistente no grafo de relações (igual ao label usado
     * pelo GraphRenderer para os nós).
     *
     * @param file    o caminho do ficheiro
     * @param content o conteúdo completo do ficheiro
     * @return o nome visível da entidade, ou string vazia se não for possível
     */
    private static String resolveDisplayLabel(Path file, String content) {
        Map<String, String> fm = parseFrontmatter(content);
        String type = fm.getOrDefault("type", "");
        String label = switch (type) {
            case "person", "project" -> fm.getOrDefault("name", "");
            case "event", "task" -> fm.getOrDefault("title", "");
            default -> "";
        };
        if (label == null || label.isBlank()) {
            // Notas e tipos desconhecidos: derivar a partir do corpo.
            label = stripLeadingHash(deriveNoteTitle(extractBody(content)));
        }
        return label;
    }

    /**
     * Remove ficheiros antigos do mesmo tipo que partilham o mesmo id mas
     * cujo nome de ficheiro mudou (renomeação de entidade). Isto garante que
     * atualizar uma entidade não deixa cópias obsoletas no vault.
     *
     * @param dir           o diretório do tipo de entidade
     * @param id           o id estável da entidade
     * @param keepFilename o nome do ficheiro a manter
     */
    private static void removeStaleFiles(Path dir, String id, String keepFilename) {
        if (id == null || id.isBlank() || keepFilename == null) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(MD_EXT))
                    .filter(p -> !p.getFileName().toString().equals(keepFilename))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (id.equals(parseFrontmatter(content).get("id"))) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException ignored) {
                            // Ignorar ficheiros ilegíveis.
                        }
                    });
        } catch (IOException ignored) {
            // Ignorar erros de listagem.
        }
    }

    /**
     * Remove um eventual {@code # } inicial de um título derivado de nota.
     *
     * @param title o título potencialmente prefixado
     * @return o título limpo
     */
    private static String stripLeadingHash(String title) {
        if (title == null) {
            return "";
        }
        String t = title.trim();
        if (t.startsWith("# ")) {
            return t.substring(2).trim();
        }
        if (t.startsWith("#")) {
            return t.substring(1).trim();
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Markdown parsing
    // ------------------------------------------------------------------

    /**
     * Escreve um ficheiro markdown com frontmatter YAML e corpo.
     *
     * @param file o caminho do ficheiro
     * @param frontmatter os pares chave-valor do frontmatter
     * @param body o corpo markdown
     * @return o caminho do ficheiro, ou {@code null} se falhou
     */
    private static Path writeMarkdown(Path file, Map<String, String> frontmatter, String body) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(FM_DELIMITER).append("\n");
            for (Map.Entry<String, String> entry : frontmatter.entrySet()) {
                String value = entry.getValue();
                if (value == null) value = "";
                // Escapar newlines no valor do YAML
                value = value.replace("\n", " ").replace("\r", "");
                sb.append(entry.getKey()).append(": ").append(value).append("\n");
            }
            sb.append(FM_DELIMITER).append("\n\n");
            sb.append(body);
            // Escrita atómica: temp -> flush -> atomic move. Evita corrupção de
            // ficheiros Markdown se o processo for interrompido a meio da escrita.
            util.AtomicWrites.write(file, sb.toString());
            // Registar o hash do conteúdo projetado: permite ao
            // EntitySynchronizer distinguir "projeção intacta" de "editado
            // externamente no Obsidian" na próxima sincronização.
            String projectedId = frontmatter.get("id");
            if (projectedId != null && !projectedId.isBlank()) {
                try {
                    entityRepository().setProjectionHash(
                            projectedId, EntitySynchronizer.sha256(sb.toString()));
                } catch (RuntimeException hashError) {
                    LOGGER.log(Level.WARNING,
                            "[AETHER] Could not record projection hash: " + hashError.getMessage());
                }
            }
            // Sinalizar ao FileWatcher que esta escrita é interna (supressão de
            // loop) e invalidar o index para que leituras seguintes vejam dados
            // atualizados. O SQLite é a fonte da verdade; o vault é a projeção.
            util.VaultFileWatcher.markInternalWrite(file);
            util.VaultIndex.getInstance().invalidate();
            return file;
        } catch (IOException e) {
            // Registar a falha em vez de a engolir silenciosamente.
            LOGGER.log(Level.WARNING, "[AETHER] Failed to write vault file " + file + ": " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[AETHER] Unexpected error writing vault file " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Lê o frontmatter YAML de um ficheiro markdown.
     *
     * @param content o conteúdo completo do ficheiro
     * @return mapa de pares chave-valor
     */
    static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> fm = new HashMap<>();
        if (content == null || !content.startsWith(FM_DELIMITER)) {
            return fm;
        }
        int endIdx = content.indexOf(FM_DELIMITER, FM_DELIMITER.length());
        if (endIdx < 0) {
            return fm;
        }
        String fmBlock = content.substring(FM_DELIMITER.length(), endIdx);
        for (String line : fmBlock.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                // Primeira ocorrência vence: ficheiros bem-formados nunca têm
                // chaves duplicadas, e a projeção do AETHER escreve as chaves
                // autoritárias primeiro — uma chave injetada/anexada não pode
                // sequestrar o id ou o tipo de uma entidade.
                fm.putIfAbsent(key, value);
            }
        }
        return fm;
    }

    /**
     * Extrai o corpo markdown (sem frontmatter) de um ficheiro.
     *
     * @param content o conteúdo completo
     * @return o corpo markdown
     */
    private static String extractBody(String content) {
        if (content == null || !content.startsWith(FM_DELIMITER)) {
            return content != null ? content : "";
        }
        int endIdx = content.indexOf(FM_DELIMITER, FM_DELIMITER.length());
        if (endIdx < 0) {
            return content;
        }
        return content.substring(endIdx + FM_DELIMITER.length()).trim();
    }

    // ------------------------------------------------------------------
    // Entity parsers
    // ------------------------------------------------------------------

    /**
     * Faz parse de um ficheiro markdown para uma Person.
     */
    static Person parsePerson(String content) {
        Map<String, String> fm = parseFrontmatter(content);
        String name = fm.getOrDefault("name", "");
        LocalDate birthDate = parseDate(fm.get("birthday"));
        String occupation = fm.getOrDefault("occupation", "");
        String about = fm.getOrDefault("about", "");
        String id = fm.getOrDefault("id", "");
        LocalDateTime created = parseDateTime(fm.get("created"));
        LocalDateTime updated = parseDateTime(fm.get("updated"));

        Person person = new Person(id, name, birthDate, occupation, about, created, updated);
        return person;
    }

    /**
     * Faz parse de um ficheiro markdown para um Event.
     */
    static Event parseEvent(String content) {
        Map<String, String> fm = parseFrontmatter(content);
        String title = fm.getOrDefault("title", "");
        LocalDateTime start = parseDateTime(fm.get("start"));
        LocalDateTime end = parseDateTime(fm.get("end"));
        String location = fm.getOrDefault("location", "");
        String description = fm.getOrDefault("description", "");
        String id = fm.getOrDefault("id", "");
        LocalDateTime created = parseDateTime(fm.get("created"));
        LocalDateTime updated = parseDateTime(fm.get("updated"));

        return new Event(id, title, start, end, location, description, created, updated);
    }

    /**
     * Faz parse de um ficheiro markdown para uma Task.
     */
    static Task parseTask(String content) {
        Map<String, String> fm = parseFrontmatter(content);
        String title = fm.getOrDefault("title", "");
        LocalDateTime deadline = parseDateTime(fm.get("deadline"));
        TaskStatus status = parseEnum(TaskStatus.class, fm.get("status"), TaskStatus.TODO);
        TaskPriority priority = parseEnum(TaskPriority.class, fm.get("priority"), TaskPriority.NONE);
        String description = fm.getOrDefault("description", "");
        String id = fm.getOrDefault("id", "");
        LocalDateTime created = parseDateTime(fm.get("created"));
        LocalDateTime updated = parseDateTime(fm.get("updated"));

        return new Task(id, title, deadline, status, priority, description, created, updated);
    }

    /**
     * Faz parse de um ficheiro markdown para uma Note.
     */
    static Note parseNote(String content) {
        Map<String, String> fm = parseFrontmatter(content);
        String body = extractBody(content);
        String id = fm.getOrDefault("id", "");
        String title = fm.getOrDefault("title", "");
        LocalDateTime created = parseDateTime(fm.get("created"));
        LocalDateTime updated = parseDateTime(fm.get("updated"));

        // Retrocompatibilidade: notas antigas guardavam "# Título\n\n..." no
        // corpo sem título no frontmatter. Recupera-se o título do cabeçalho e
        // limpa-se o corpo para que o editor não mostre o título duplicado.
        if ((title == null || title.isBlank()) && body != null && body.startsWith("# ")) {
            String[] lines = body.split("\n", 2);
            title = lines[0].substring(2).trim();
            body = lines.length > 1 ? lines[1].replaceFirst("^\n+", "") : "";
        }

        return new Note(id, title, body, created, updated);
    }

    /**
     * Faz parse de um ficheiro markdown para um Project.
     */
    static Project parseProject(String content) {
        Map<String, String> fm = parseFrontmatter(content);
        String name = fm.getOrDefault("name", "");
        LocalDateTime deadline = parseDateTime(fm.get("deadline"));
        ProjectStatus status = parseEnum(ProjectStatus.class, fm.get("status"), ProjectStatus.PLANNED);
        String description = fm.getOrDefault("description", "");
        String id = fm.getOrDefault("id", "");
        LocalDateTime created = parseDateTime(fm.get("created"));
        LocalDateTime updated = parseDateTime(fm.get("updated"));

        return new Project(id, name, deadline, status, description, created, updated);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Repositório de entidades (SQLite — fonte da verdade). */
    private static volatile SqliteEntityRepository entityRepo;

    /**
     * Acesso ao repositório de entidades (SQLite, autoritativo desde a
     * migração v3). Compartilhado com o {@link EntitySynchronizer}.
     *
     * @return o repositório (nunca {@code null})
     */
    static SqliteEntityRepository entityRepository() {
        SqliteEntityRepository current = entityRepo;
        if (current == null) {
            synchronized (VaultManager.class) {
                current = entityRepo;
                if (current == null) {
                    current = new SqliteEntityRepository();
                    entityRepo = current;
                }
            }
        }
        return current;
    }

    /**
     * Mapeia o nome do diretório do vault no tipo lógico da entidade.
     */
    private static String dirNameToType(String dirName) {
        return switch (dirName == null ? "" : dirName) {
            case PEOPLE_DIR -> "person";
            case EVENTS_DIR -> "event";
            case TASKS_DIR -> "task";
            case NOTES_DIR -> "note";
            case PROJECTS_DIR -> "project";
            default -> null;
        };
    }

    /**
     * Projeta uma entidade no vault. O repositório SQLite JÁ foi escrito
     * quando este método é chamado; falhar aqui não perde dados — devolve o
     * caminho previsto para a projeção, e o ficheiro é recriado na próxima
     * sincronização ({@link EntitySynchronizer}).
     *
     * @param file o caminho destino no vault
     * @param frontmatter o frontmatter da entidade
     * @param body o corpo markdown
     * @return o caminho do ficheiro projetado
     */
    private static Path project(Path file, Map<String, String> frontmatter, String body) {
        Path written = writeMarkdown(file, frontmatter, body);
        return written != null ? written : file;
    }

    /**
     * Preserva chaves de frontmatter definidas pelo utilizador durante a
     * re-projeção (spec final: "unknown / user-defined frontmatter fields
     * MUST be preserved").
     * <p>
     * Os campos AETHER (whitelist por tipo em {@link CustomFrontmatter}) são
     * sempre autoritários; os restantes pertencem ao utilizador e sobrevivem
     * a saves, re-projeções e renames. As chaves são lidas de duas fontes:
     * </p>
     * <ul>
     *   <li>o ficheiro atual no vault com o MESMO id (procura por id, não por
     *   nome de ficheiro — cobre o caso do rename), estado manual mais
     *   recente — vence em caso de conflito;</li>
     *   <li>a coluna {@code custom_frontmatter} do repositório (capturada nas
     *   importações) — cobre vault apagado e ficheiros eliminados.</li>
     * </ul>
     * <p>
     * O conjunto fundido é re-persistido no repositório para sobreviver à
     * próxima re-projeção, e adicionado ao frontmatter a projetar. Não pode
     * colidir com campos AETHER: a extração exclui-os por construção.
     * Silenciosamente ignorado em caso de erro — a projeção nunca falha por
     * causa de metadados do utilizador.
     * </p>
     *
     * @param type        o tipo lógico da entidade
     * @param dirName     o diretório do vault da entidade
     * @param id          o id estável da entidade
     * @param frontmatter o frontmatter AETHER (recebe as chaves do utilizador)
     */
    private static void mergeCustomFrontmatter(String type, String dirName,
                                                String id, Map<String, String> frontmatter) {
        if (id == null || id.isBlank()) {
            return;
        }
        java.util.LinkedHashMap<String, String> custom = new java.util.LinkedHashMap<>();

        // 1) Chaves ainda presentes no ficheiro atual (por id — inclui o
        //    ficheiro com o nome antigo durante um rename).
        Path dir = getVaultPath().resolve(dirName);
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var files = java.nio.file.Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(MD_EXT)).forEach(p -> {
                    try {
                        Map<String, String> fm = parseFrontmatter(
                                java.nio.file.Files.readString(p));
                        if (id.equals(fm.get("id"))) {
                            custom.putAll(CustomFrontmatter.extract(type, fm));
                        }
                    } catch (java.io.IOException ignored) {
                        // Ficheiro ilegível: ignorar.
                    }
                });
            } catch (java.io.IOException ignored) {
                // Diretório ilegível: seguir só com o repositório.
            }
        }

        // 2) Chaves conhecidas de importações anteriores (sobrevivem a vault
        //    apagado e a ficheiros eliminados externamente).
        try {
            String stored = entityRepository().customFrontmatter(type, id);
            if (stored != null) {
                CustomFrontmatter.fromJson(stored).forEach(custom::putIfAbsent);
            }
        } catch (RuntimeException ignored) {
            // Repositório indisponível: projetar sem metadados, sem falhar.
        }

        if (custom.isEmpty()) {
            return;
        }
        // Re-persistir o conjunto fundido (sobrevive ao próximo rename/wipe).
        try {
            entityRepository().setCustomFrontmatter(type, id,
                    CustomFrontmatter.toJson(custom));
        } catch (RuntimeException ignored) {
            // Ver acima: nunca falhar a projeção por metadados.
        }
        frontmatter.putAll(custom);
    }

    /**
     * Garante que o vault está inicializado antes de qualquer operação e —
     * uma vez por sessão/vault — sincroniza-o com o repositório SQLite
     * (importa edições externas, reprojeta ficheiros em falta).
     */
    private static void ensureVault() {
        if (!isVaultInitialized()) {
            initializeVault();
        }
        try {
            EntitySynchronizer.reconcileIfNeeded();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "[AETHER] Vault sync skipped: " + e.getMessage());
        }
    }

    /**
     * Converte um nome num nome de ficheiro seguro.
     *
     * @param name o nome a sanitizar
     * @return o nome de ficheiro sanitizado
     */
    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "untitled";
        }
        // Normaliza diacríticos antes de remover caracteres especiais:
        // "João" → "joao" (antes era "joo" — perda de informação no nome
        // do ficheiro). NFD decompõe "ã" em "a"+til, o filtro remove o til.
        return java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-zA-Z0-9\\-\\s]", "")
                .replaceAll("\\s+", "-")
                .toLowerCase();
    }

    /**
     * Deriva um título para uma nota a partir do seu conteúdo.
     *
     * @param content o conteúdo da nota
     * @return o título derivado
     */
    private static String deriveNoteTitle(String content) {
        if (content == null || content.isBlank()) {
            return "Untitled Note";
        }
        String firstLine = content.trim().split("\n")[0];
        if (firstLine.length() > 50) {
            return firstLine.substring(0, 50).trim() + "...";
        }
        return firstLine.isEmpty() ? "Untitled Note" : firstLine;
    }

    /**
     * Faz parse de uma string de data no formato yyyy-MM-dd.
     */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Faz parse de uma string de data e hora no formato yyyy-MM-dd HH:mm.
     */
    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DATETIME_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Faz parse de um valor de enum de forma segura.
     */
    private static <T extends Enum<T>> T parseEnum(Class<T> enumClass, String value, T defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }
}
