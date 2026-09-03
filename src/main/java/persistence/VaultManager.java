package persistence;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * VaultManager — gestor do vault de notas do AETHER.
 * <p>
 * Lê e escreve ficheiros markdown com YAML frontmatter no vault do AETHER.
 * O vault é uma pasta no sistema de ficheiros que pode ser aberta no Obsidian
 * para visualização do grafo, mas o AETHER funciona independentemente do
 * Obsidian estar ou não instalado.
 * </p>
 * <p>
 * Cada entidade (Person, Event, Task, Note, Project) é armazenada como um
 * ficheiro .md com frontmatter YAML (dados estruturados) e wikilinks {@code [[ ]}
 * (relações entre entidades).
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class VaultManager {

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
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Person CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda uma pessoa no vault como ficheiro markdown.
     *
     * @param person a pessoa a guardar
     * @return o caminho do ficheiro criado, ou {@code null} se falhou
     */
    public static Path savePerson(Person person) {
        ensureVault();
        String filename = sanitizeFilename(person.getName()) + MD_EXT;
        Path dir = getVaultPath().resolve(PEOPLE_DIR);
        removeStaleFiles(dir, person.getId(), filename);
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

        StringBuilder body = new StringBuilder();
        body.append("# ").append(person.getName()).append("\n\n");
        if (!person.getAbout().isBlank()) {
            body.append(person.getAbout()).append("\n");
        }

        return writeMarkdown(file, frontmatter, body.toString());
    }

    /**
     * Lista todas as pessoas no vault.
     *
     * @return lista de pessoas, vazia se não houver
     */
    public static List<Person> listPeople() {
        return listEntities(PEOPLE_DIR, VaultManager::parsePerson);
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
     * Guarda um evento no vault.
     *
     * @param event o evento a guardar
     * @return o caminho do ficheiro, ou {@code null} se falhou
     */
    public static Path saveEvent(Event event) {
        ensureVault();
        String filename = sanitizeFilename(event.getTitle()) + MD_EXT;
        Path dir = getVaultPath().resolve(EVENTS_DIR);
        removeStaleFiles(dir, event.getId(), filename);
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

        StringBuilder body = new StringBuilder();
        body.append("# ").append(event.getTitle()).append("\n\n");
        if (!event.getDescription().isBlank()) {
            body.append(event.getDescription()).append("\n");
        }

        return writeMarkdown(file, fm, body.toString());
    }

    /**
     * Lista todos os eventos no vault.
     *
     * @return lista de eventos
     */
    public static List<Event> listEvents() {
        return listEntities(EVENTS_DIR, VaultManager::parseEvent);
    }

    // ------------------------------------------------------------------
    // Task CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda uma tarefa no vault.
     *
     * @param task a tarefa a guardar
     * @return o caminho do ficheiro, ou {@code null} se falhou
     */
    public static Path saveTask(Task task) {
        ensureVault();
        String filename = sanitizeFilename(task.getTitle()) + MD_EXT;
        Path dir = getVaultPath().resolve(TASKS_DIR);
        removeStaleFiles(dir, task.getId(), filename);
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

        StringBuilder body = new StringBuilder();
        body.append("# ").append(task.getTitle()).append("\n\n");
        if (!task.getDescription().isBlank()) {
            body.append(task.getDescription()).append("\n");
        }

        return writeMarkdown(file, fm, body.toString());
    }

    /**
     * Lista todas as tarefas no vault.
     *
     * @return lista de tarefas
     */
    public static List<Task> listTasks() {
        return listEntities(TASKS_DIR, VaultManager::parseTask);
    }

    // ------------------------------------------------------------------
    // Note CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda uma nota no vault.
     *
     * @param note a nota a guardar
     * @param originalText o texto original em linguagem natural (se aplicável)
     * @return o caminho do ficheiro, ou {@code null} se falhou
     */
    public static Path saveNote(Note note, String originalText) {
        ensureVault();
        String title = deriveNoteTitle(note.getContent());
        String filename = sanitizeFilename(title) + MD_EXT;
        Path dir = getVaultPath().resolve(NOTES_DIR);
        removeStaleFiles(dir, note.getId(), filename);
        Path file = dir.resolve(filename);

        Map<String, String> fm = new HashMap<>();
        fm.put("type", "note");
        fm.put("id", note.getId());
        if (originalText != null && !originalText.isBlank()) {
            fm.put("source", "quick_note");
            fm.put("original_text", originalText);
        }
        fm.put("created", note.getCreatedAt() != null ? note.getCreatedAt().format(DATETIME_FMT) : "");
        fm.put("updated", note.getUpdatedAt() != null ? note.getUpdatedAt().format(DATETIME_FMT) : "");

        StringBuilder body = new StringBuilder();
        body.append("# ").append(title).append("\n\n");
        body.append(note.getContent()).append("\n");

        return writeMarkdown(file, fm, body.toString());
    }

    /**
     * Lista todas as notas no vault.
     *
     * @return lista de notas
     */
    public static List<Note> listNotes() {
        return listEntities(NOTES_DIR, VaultManager::parseNote);
    }

    // ------------------------------------------------------------------
    // Project CRUD
    // ------------------------------------------------------------------

    /**
     * Guarda um projeto no vault.
     *
     * @param project o projeto a guardar
     * @return o caminho do ficheiro, ou {@code null} se falhou
     */
    public static Path saveProject(Project project) {
        ensureVault();
        String filename = sanitizeFilename(project.getName()) + MD_EXT;
        Path dir = getVaultPath().resolve(PROJECTS_DIR);
        removeStaleFiles(dir, project.getId(), filename);
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

        StringBuilder body = new StringBuilder();
        body.append("# ").append(project.getName()).append("\n\n");
        if (!project.getDescription().isBlank()) {
            body.append(project.getDescription()).append("\n");
        }

        return writeMarkdown(file, fm, body.toString());
    }

    /**
     * Lista todos os projetos no vault.
     *
     * @return lista de projetos
     */
    public static List<Project> listProjects() {
        return listEntities(PROJECTS_DIR, VaultManager::parseProject);
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
     * Elimina um ficheiro do vault.
     *
     * @param filePath o caminho do ficheiro a eliminar
     * @return {@code true} se eliminado com sucesso
     */
    public static boolean deleteEntity(Path filePath) {
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Elimina do vault o ficheiro cuja entidade tem o id indicado, no diretório
     * dado. Ao procurar por id (em vez de por nome de ficheiro) funciona mesmo
     * se a entidade tiver sido renomeada — o nome do ficheiro é derivado do
     * nome/título, pelo que apagar por nome falharia após uma edição.
     *
     * @param dirName o subdiretório do tipo de entidade (ex.: {@link #PEOPLE_DIR})
     * @param id     o id estável da entidade a eliminar
     * @return {@code true} se algum ficheiro foi eliminado
     */
    private static boolean deleteEntityById(String dirName, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        Path dir = getVaultPath().resolve(dirName);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        boolean deleted = false;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.toString().endsWith(MD_EXT)).toList()) {
                try {
                    String content = Files.readString(p);
                    if (id.equals(parseFrontmatter(content).get("id"))) {
                        deleted |= Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                    // Ignorar ficheiros ilegíveis.
                }
            }
        } catch (IOException ignored) {
            // Ignorar erros de listagem.
        }
        return deleted;
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
            Files.writeString(file, sb.toString());
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Lê o frontmatter YAML de um ficheiro markdown.
     *
     * @param content o conteúdo completo do ficheiro
     * @return mapa de pares chave-valor
     */
    private static Map<String, String> parseFrontmatter(String content) {
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
                fm.put(key, value);
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
    private static Person parsePerson(String content) {
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
    private static Event parseEvent(String content) {
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
    private static Task parseTask(String content) {
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
    private static Note parseNote(String content) {
        Map<String, String> fm = parseFrontmatter(content);
        String body = extractBody(content);
        String id = fm.getOrDefault("id", "");
        LocalDateTime created = parseDateTime(fm.get("created"));
        LocalDateTime updated = parseDateTime(fm.get("updated"));

        return new Note(id, body, created, updated);
    }

    /**
     * Faz parse de um ficheiro markdown para um Project.
     */
    private static Project parseProject(String content) {
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

    /**
     * Garante que o vault está inicializado antes de qualquer operação.
     */
    private static void ensureVault() {
        if (!isVaultInitialized()) {
            initializeVault();
        }
    }

    /**
     * Lista todas as entidades de um diretório do vault, fazendo parse de cada ficheiro.
     *
     * @param dirName o nome do diretório
     * @param parser a função de parse
     * @param <T> o tipo de entidade
     * @return lista de entidades
     */
    private static <T> List<T> listEntities(String dirName, java.util.function.Function<String, T> parser) {
        Path dir = getVaultPath().resolve(dirName);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(MD_EXT))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(content -> content != null && !content.isBlank())
                    .map(parser)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
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
        return name.trim()
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
