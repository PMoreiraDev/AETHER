package ai;

import domain.entities.AetherEntity;
import domain.entities.ContextEntityType;
import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import domain.entities.TaskPriority;
import domain.entities.TaskStatus;
import persistence.VaultManager;
import util.VaultFileWatcher;
import util.VaultIndex;
import util.VaultRefreshBus;
import util.AtomicWrites;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * ActionExecutor — executa propostas de ação aprovadas pelo utilizador.
 * <p>
 * <b>Nunca</b> escreve ficheiros Markdown diretamente com lógica de negócio:
 * toda a persistência de entidades passa pelo {@link VaultManager}, que é a
 * fonte de verdade do vault. O executor apenas traduz uma
 * {@link AiActionProposal} aprovada numa chamada ao VaultManager, e o index é
 * invalidado pelo próprio VaultManager após a escrita. As operações de relação
 * (link/unlink) e a limpeza de referências órfãs no delete usam
 * {@link AtomicWrites} + {@link VaultFileWatcher#markInternalWrite} + index
 * invalidation, para não contornar as garantias de atomicidade do vault.
 * </p>
 * <p>
 * Após cada mutação bem-sucedida, publica um evento no
 * {@link VaultRefreshBus} para que a UI (calendário, listas, pesquisa) se
 * refresque sem reiniciar a app.
 * </p>
 * <p>
 * Princípio: AI proposes → User approves → AETHER executes → Vault persists.
 * </p>
 *
 * @author AETHER
 */
public final class ActionExecutor {

    private static final Logger LOGGER = Logger.getLogger(ActionExecutor.class.getName());

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> ENTITY_DIRS = List.of("People", "Events", "Tasks", "Notes", "Projects");

    private ActionExecutor() {
        // Classe de utilitário estático.
    }

    /**
     * Executa uma proposta aprovada.
     *
     * @param proposal a proposta (deve estar aprovada e validada)
     * @return {@code true} se executada com sucesso
     */
    public static boolean execute(AiActionProposal proposal) {
        if (proposal == null) {
            return false;
        }
        // Normaliza os alias de campos (description→about, due→deadline,
        // text→content, date+time→start) ANTES de validar. Propostas que chegam
        // diretamente ao executor (ou via caminhos que não o orchestrator) com
        // aliases ainda assim passam a validação e persistem corretamente.
        AiActionProposal normalized = withNormalizedFields(proposal);
        if (!ActionValidator.isValid(normalized)) {
            return false;
        }
        try {
            boolean ok = switch (normalized.getActionType()) {
                case CREATE_ENTITY -> executeCreate(normalized);
                case UPDATE_ENTITY -> executeUpdate(normalized);
                case LINK_ENTITIES -> executeLink(normalized, true);
                case UNLINK_ENTITIES -> executeLink(normalized, false);
                // DELETE exige confirmação muito explícita — só chega aqui depois de
                // confirmada duas vezes pelo ApprovalFlow / UI.
                case DELETE_ENTITY -> executeDelete(normalized);
            };
            if (ok) {
                VaultRefreshBus.publish(VaultRefreshBus.ChangeType.UPDATED, normalized.getEntityType().name());
            }
            return ok;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao executar proposta " + normalized.describe(), e);
            return false;
        }
    }

    /**
     * Reconstrói a proposta com campos normalizados (aliases → nomes canónicos
     * esperados pelas entidades e pelo validador). Preserva tipo, entidade, id,
     * relações, razão, confiança, fonte e nível de confiança.
     */
    private static AiActionProposal withNormalizedFields(AiActionProposal p) {
        Map<String, String> nf = normalizeFields(p.getEntityType(), p.getFields());
        AiActionProposal.Builder b = new AiActionProposal.Builder()
                .actionType(p.getActionType())
                .entityType(p.getEntityType())
                .reason(p.getReason())
                .confidence(p.getConfidence())
                .sourceContext(p.getSourceContext())
                .trustLevel(p.getTrustLevel())
                .semanticClassification(p.getSemanticClassification());
        String id = p.getEntityId();
        if (id != null && !id.isBlank()) {
            b.entityId(id);
        }
        List<String> rels = p.getRelationships();
        if (rels != null && !rels.isEmpty()) {
            b.relationships(rels);
        }
        nf.forEach(b::field);
        return b.build();
    }

    private static boolean executeCreate(AiActionProposal p) {
        Map<String, String> f = normalizeFields(p.getEntityType(), p.getFields());
        ContextEntityType t = p.getEntityType();
        Path saved = switch (t) {
            case PERSON -> {
                Person person = new Person();
                person.setName(f.getOrDefault("name", ""));
                person.setOccupation(f.getOrDefault("occupation", ""));
                person.setAbout(f.getOrDefault("about", ""));
                if (f.containsKey("birthday")) {
                    person.setBirthDate(parseDate(f.get("birthday")));
                }
                yield VaultManager.savePerson(person);
            }
            case PROJECT -> {
                Project project = new Project();
                project.setName(f.getOrDefault("name", ""));
                project.setDescription(f.getOrDefault("description", ""));
                if (f.containsKey("deadline")) project.setDeadline(parseDt(f.get("deadline")));
                if (f.containsKey("status")) project.setStatus(parseEnum(domain.entities.ProjectStatus.class, f.get("status"), domain.entities.ProjectStatus.PLANNED));
                yield VaultManager.saveProject(project);
            }
            case EVENT -> {
                Event event = new Event();
                event.setTitle(f.getOrDefault("title", ""));
                event.setLocation(f.getOrDefault("location", ""));
                event.setDescription(f.getOrDefault("description", ""));
                if (f.containsKey("start")) event.setStartDateTime(parseDt(f.get("start")));
                if (f.containsKey("end")) event.setEndDateTime(parseDt(f.get("end")));
                yield VaultManager.saveEvent(event);
            }
            case TASK -> {
                Task task = new Task();
                task.setTitle(f.getOrDefault("title", ""));
                task.setDescription(f.getOrDefault("description", ""));
                if (f.containsKey("deadline")) task.setDeadline(parseDt(f.get("deadline")));
                if (f.containsKey("status"))
                    task.setStatus(parseEnum(TaskStatus.class, f.get("status"), TaskStatus.TODO));
                if (f.containsKey("priority"))
                    task.setPriority(parseEnum(TaskPriority.class, f.get("priority"), TaskPriority.NONE));
                yield VaultManager.saveTask(task);
            }
            case NOTE -> {
                Note note = new Note();
                note.setContent(f.getOrDefault("content", ""));
                yield VaultManager.saveNote(note, f.getOrDefault("original_text", ""));
            }
            case PROFILE -> null;
        };
        boolean ok = saved != null;
        // Depois de criar a entidade, aplica as relações indicadas (ex.: ligar um
        // evento/tarefa à pessoa mencionada) como wikilinks no Markdown da nova
        // entidade. Sem isto, "reunião com o Manel" criaria o evento sem o ligar
        // à pessoa.
        if (ok && p.getRelationships() != null && !p.getRelationships().isEmpty()) {
            applyRelationships(saved, p.getRelationships());
        }
        if (ok) {
            VaultRefreshBus.publish(VaultRefreshBus.ChangeType.CREATED, t.name());
        }
        return ok;
    }

    /**
     * Acrescenta wikilinks {@code [[Nome]]} ao ficheiro de uma entidade recém-criada,
     * para materializar as relações indicadas pelo extrator.
     */
    private static void applyRelationships(Path file, List<String> relationships) {
        if (file == null) return;
        try {
            String content = Files.readString(file);
            boolean changed = false;
            for (String rel : relationships) {
                if (rel == null || rel.isBlank()) continue;
                String updated = VaultManager.addWikilink(content, rel);
                if (!updated.equals(content)) {
                    content = updated;
                    changed = true;
                }
            }
            if (changed) {
                AtomicWrites.write(file, content);
                VaultFileWatcher.markInternalWrite(file);
                VaultIndex.getInstance().invalidate();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao aplicar relações ao ficheiro " + file, e);
        }
    }

    private static boolean executeUpdate(AiActionProposal p) {
        ContextEntityType t = p.getEntityType();
        Map<String, String> f = normalizeFields(t, p.getFields());
        boolean ok = switch (t) {
            case TASK -> {
                Task task = findTaskById(p.getEntityId());
                if (task == null) yield false;
                else {
                    applyFieldsToTask(task, f);
                    yield VaultManager.saveTask(task) != null;
                }
            }
            case EVENT -> {
                Event event = findEventById(p.getEntityId());
                if (event == null) yield false;
                else {
                    applyFieldsToEvent(event, f);
                    yield VaultManager.saveEvent(event) != null;
                }
            }
            case PERSON -> {
                Person person = findPersonById(p.getEntityId());
                if (person == null) yield false;
                else {
                    applyFieldsToPerson(person, f);
                    yield VaultManager.savePerson(person) != null;
                }
            }
            case PROJECT -> {
                Project project = findProjectById(p.getEntityId());
                if (project == null) yield false;
                else {
                    applyFieldsToProject(project, f);
                    yield VaultManager.saveProject(project) != null;
                }
            }
            case NOTE -> {
                Note note = findNoteById(p.getEntityId());
                if (note == null) yield false;
                else {
                    applyFieldsToNote(note, f);
                    yield VaultManager.saveNote(note, "") != null;
                }
            }
            case PROFILE -> executeProfileUpdate(f, p);
        };
        return ok;
    }

    /**
     * Applies an approved profile update through the canonical UserSession repository.
     *
     * <p>ACCEPT COORDENADO (specs #10, #12, #23): numa única operação, o mesmo
     * facto aceite atualiza (1) o perfil SQLite (fonte canónica), (2) o registo
     * de memória com proveniência (SqliteUserMemoryRepository), (3) a pasta
     * User/ do Vault Obsidian (UserVaultSync, merge-read-write), (4) a
     * Timeline histórica (valor antigo preservado) e (5) publica o refresh da
     * UI. A proveniência (fonte, citação, classificação, confiança) viaja da
     * proposta original até ao registo persistente.</p>
     */
    private static boolean executeProfileUpdate(Map<String, String> fields, AiActionProposal proposal) {
        if (fields == null || fields.isEmpty()) return false;
        session.UserSession currentSession = session.UserSession.getInstance();
        domain.UserProfile profile = currentSession.getUserProfile();
        java.util.Map<String, String> oldValues = new java.util.HashMap<>();
        for (String key : fields.keySet()) oldValues.put(key, profileValue(profile, key));
        boolean changed = false;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isBlank()) continue;
            switch (e.getKey()) {
                case "fullName" -> { profile.setFullName(value); changed = true; }
                case "preferredName" -> { profile.setPreferredName(value); changed = true; }
                case "about" -> { profile.setAbout(value); changed = true; }
                case "occupation" -> { profile.setOccupation(value); changed = true; }
                case "studies" -> { profile.setStudies(value); changed = true; }
                case "experience" -> { profile.setExperience(value); changed = true; }
                case "skills" -> { profile.setSkills(value); changed = true; }
                case "interests" -> { profile.setInterests(value); changed = true; }
                case "objectives" -> { profile.setObjectives(value); changed = true; }
                case "preferences" -> { profile.setPreferences(value); changed = true; }
                case "projects" -> { profile.setProjects(value); changed = true; }
                case "workStyle" -> { profile.setWorkStyle(value); changed = true; }
                case "location" -> { profile.setLocation(value); changed = true; }
                case "birthDate" -> {
                    try {
                        profile.setBirthDate(java.time.LocalDate.parse(value));
                        changed = true;
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // Formato inválido: ignora o campo (não falha o ACCEPT todo).
                    }
                }
                case "profileSummary" -> { profile.setProfileSummary(value); changed = true; }
                // inferredContext: contexto aceito pela IA (sem campo estruturado).
                // É SEPARADO do aiContext (texto manual do utilizador) — o user
                // edita o aiContext no editor de markdown; o inferredContext acumula
                // informação inferida e aceite a partir das conversas.
                case "inferredContext" -> {
                    profile.setInferredContext(appendContextBullet(profile.getInferredContext(), value));
                    changed = true;
                }
                // aiContext: legado — se uma proposta antiga usar este campo,
                // encaminha para inferredContext (não sobrescreve o texto manual).
                case "aiContext" -> {
                    profile.setInferredContext(appendContextBullet(profile.getInferredContext(), value));
                    changed = true;
                }
                // Campos desconhecidos: vão para inferredContext como fallback
                // (spec #7 — se não houver campo estruturado, usar inferredContext).
                default -> {
                    profile.setInferredContext(appendContextBullet(profile.getInferredContext(), value));
                    changed = true;
                }
            }
        }
        if (!changed) return false;
        boolean saved = currentSession.saveUserProfile();
        if (!saved) {
            oldValues.forEach((key, value) -> restoreProfileValue(profile, key, value));
            return false;
        }

        // ------------------------------------------------------------------
        // ACCEPT COORDENADO — proveniência, histórico e vault (specs #9, #10, #12, #23).
        // ------------------------------------------------------------------
        String sourceType = sourceTypeOf(proposal);
        String sourceContext = proposal == null ? "" : proposal.getSourceContext();
        String classification = proposal == null || proposal.getSemanticClassification() == null
                ? "FACT" : proposal.getSemanticClassification();
        double confidence = proposal == null ? 0.5 : proposal.getConfidence();
        // sourceId (spec #9): fingerprint determinístico da proposta aceite —
        // liga a memória persistida à proposta exata que o utilizador aprovou.
        String sourceId = "";
        if (proposal != null) {
            sourceId = ai.ProposalStore.idFor(proposal.getActionType().name(),
                    proposal.getEntityType().name(), "profile", proposal.getFields());
        }
        persistence.SqliteUserMemoryRepository memoryRepo =
                new persistence.SqliteUserMemoryRepository();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            // Campos desconhecidos são escritos em inferredContext (default do
            // switch acima) — a proveniência regista o campo FINAL, não o
            // desconhecido original (spec #7 coerência de esquema).
            String rawKey = e.getKey();
            String key = ProfileFieldSchema.isKnown(rawKey)
                    ? ProfileFieldSchema.canonical(rawKey) : "inferredContext";
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isBlank()) continue;
            String prev = oldValues.get(rawKey);
            String prevNorm = ProfileFieldSchema.normalizeForCompare(prev);
            String newNorm = ProfileFieldSchema.normalizeForCompare(
                    "inferredContext".equals(key)
                            ? appendContextBullet("", value) : value);
            if (prevNorm.equals(newNorm)) continue; // sem alteração real → sem registo
            try {
                memoryRepo.record(key, value, prev, sourceType, sourceId, sourceType, sourceContext,
                        confidence, classification);
                persistence.UserVaultSync.appendTimeline(persistence.UserVaultSync.timelineLine(
                        key, ProfileFieldSchema.label(key), value, prev, sourceType, null));
            } catch (RuntimeException rex) {
                LOGGER.log(Level.WARNING, "Falha a registar memória/proveniência (field="
                        + key + "): " + rex.getMessage(), rex);
            }
        }

        // Vault do utilizador (merge-read-write; preserva dados manuais).
        try {
            persistence.UserVaultSync.syncProfile(profile, "AI_ACCEPT");
        } catch (RuntimeException rex) {
            LOGGER.log(Level.WARNING, "Falha ao sincronizar a pasta User/ do vault: "
                    + rex.getMessage(), rex);
        }
        VaultIndex.getInstance().invalidate();
        VaultRefreshBus.publish(VaultRefreshBus.ChangeType.UPDATED, "PROFILE");
        return true;
    }

    /** Tipo de fonte a partir do contexto da proposta (conversa/nota/edição). */
    private static String sourceTypeOf(AiActionProposal proposal) {
        String ctx = proposal == null ? "" : String.valueOf(proposal.getSourceContext());
        if (ctx.toLowerCase().contains("nota")) return "note";
        return "conversation";
    }

    private static String profileValue(domain.UserProfile p, String key) {
        return switch (key) {
            case "fullName" -> p.getFullName(); case "preferredName" -> p.getPreferredName();
            case "about" -> p.getAbout(); case "occupation" -> p.getOccupation(); case "studies" -> p.getStudies();
            case "experience" -> p.getExperience(); case "skills" -> p.getSkills(); case "interests" -> p.getInterests();
            case "objectives" -> p.getObjectives(); case "preferences" -> p.getPreferences(); case "projects" -> p.getProjects();
            case "workStyle" -> p.getWorkStyle(); case "location" -> p.getLocation(); case "aiContext" -> p.getAiContext();
            case "inferredContext" -> p.getInferredContext();
            case "profileSummary" -> p.getProfileSummary(); default -> "";
        };
    }

    private static void restoreProfileValue(domain.UserProfile p, String key, String value) {
        switch (key) {
            case "fullName" -> p.setFullName(value); case "preferredName" -> p.setPreferredName(value);
            case "about" -> p.setAbout(value); case "occupation" -> p.setOccupation(value); case "studies" -> p.setStudies(value);
            case "experience" -> p.setExperience(value); case "skills" -> p.setSkills(value); case "interests" -> p.setInterests(value);
            case "objectives" -> p.setObjectives(value); case "preferences" -> p.setPreferences(value); case "projects" -> p.setProjects(value);
            case "workStyle" -> p.setWorkStyle(value); case "location" -> p.setLocation(value); case "aiContext" -> p.setAiContext(value);
            case "inferredContext" -> p.setInferredContext(value);
            case "profileSummary" -> p.setProfileSummary(value); default -> { }
        }
    }

    /**
     * Acrescenta um bullet ao AI Context existente (spec #8). Se o contexto já
     * contiver o mesmo bullet (ignorando maiúsculas/minúsculas), não duplica.
     *
     * @param existing contexto atual (pode ser nulo ou vazio)
     * @param bullet   nova informação a acrescentar
     * @return contexto atualizado
     */
    private static String appendContextBullet(String existing, String bullet) {
        if (bullet == null || bullet.isBlank()) return existing;
        String b = bullet.trim();
        String existingOrEmpty = existing == null ? "" : existing.trim();
        // Evita duplicar informação já presente (semelhante, spec #10).
        if (!existingOrEmpty.isBlank()
                && existingOrEmpty.toLowerCase().contains(b.toLowerCase())) {
            return existing;
        }
        String formatted = b.startsWith("•") ? b : "• " + b;
        if (existingOrEmpty.isBlank()) return formatted;
        return existingOrEmpty + "\n" + formatted;
    }

    private static boolean executeLink(AiActionProposal p, boolean link) {
        ContextEntityType t = p.getEntityType();
        String sourceId = p.getEntityId();
        if (sourceId == null || sourceId.isBlank()) return false;

        String dir = dirForType(t);
        if (dir == null) return false;
        Path file = VaultManager.findFileById(dir, sourceId);
        if (file == null) return false;
        try {
            String content = Files.readString(file);
            for (String rel : p.getRelationships()) {
                if (link) {
                    content = VaultManager.addWikilink(content, rel);
                } else {
                    content = content.replace("[[" + rel + "]]", rel);
                }
            }
            AtomicWrites.write(file, content);
            VaultFileWatcher.markInternalWrite(file);
            VaultIndex.getInstance().invalidate();
            VaultRefreshBus.publish(VaultRefreshBus.ChangeType.UPDATED, t.name());
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao atualizar relações.", e);
            return false;
        }
    }

    /**
     * Elimina uma entidade e limpa as referências (wikilinks) órfãs noutros
     * ficheiros do vault. Os wikilinks {@code [[Nome]]} que apontavam para a
     * entidade eliminada são convertidos em texto simples (o nome sem link),
     * para não deixar relações quebradas. Nunca elimina sem confirmação explícita
     * (garantida pelo ApprovalFlow / UI antes de chegar aqui).
     */
    private static boolean executeDelete(AiActionProposal p) {
        ContextEntityType t = p.getEntityType();
        String id = p.getEntityId();
        if (id == null || id.isBlank()) return false;
        String dir = dirForType(t);
        if (dir == null) return false;

        // Nome de apresentação da entidade (ANTES de eliminar), para limpar
        // referências órfãs.
        String name = displayName(findEntityById(t, id));

        // Eliminação SEMPRE pelo VaultManager (repositório SQLite — fonte da
        // verdade — E projeção do vault, por id). Nunca eliminar ficheiros
        // diretamente: com o SQLite autoritativo, apagar só o ficheiro deixava
        // a entidade "viva" no repositório e ela reapareceria.
        boolean deleted = VaultManager.deleteEntityById(dir, id);

        if (deleted) {
            // Limpa wikilinks órfãs em todo o vault: [[Nome]] -> Nome
            if (name != null && !name.isBlank()) {
                cleanupOrphanedWikilinks(name);
            }
            VaultRefreshBus.publish(VaultRefreshBus.ChangeType.DELETED, t.name());
        }
        return deleted;
    }

    /** Substitui {@code [[name]]} por {@code name} em todos os ficheiros do vault. */
    private static void cleanupOrphanedWikilinks(String name) {
        Path vault = persistence.VaultManager.getVaultPath();
        for (String dirName : ENTITY_DIRS) {
            Path dir = vault.resolve(dirName);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".md")).forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        String updated = content.replace("[[" + name + "]]", name);
                        if (!updated.equals(content)) {
                            AtomicWrites.write(file, updated);
                            VaultFileWatcher.markInternalWrite(file);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Não foi possível limpar wikilinks órfãos em " + file, e);
                    }
                });
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Não foi possível listar o diretório " + dir + " para limpeza.", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String dirForType(ContextEntityType t) {
        return switch (t) {
            case PERSON -> "People";
            case EVENT -> "Events";
            case TASK -> "Tasks";
            case NOTE -> "Notes";
            case PROJECT -> "Projects";
            case PROFILE -> null;
        };
    }

    private static LocalDateTime parseDt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.trim(), DT);
        } catch (Exception e) {
            try {
                return LocalDate.parse(value.trim()).atStartOfDay();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> cls, String v, T def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Enum.valueOf(cls, v.trim().toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Normaliza os campos extraídos para as chaves canónicas que o executor
     * consome. Isto evita que informação contextual válida se perca só porque o
     * modelo usou um nome de campo alternativo (ex.: {@code description} em vez
     * de {@code about} para uma pessoa; {@code date}+{@code time} em vez de
     * {@code start} para um evento).
     * <p>
     * Não inventa informação: só mapeia chaves que já estão presentes.
     *
     * @param t o tipo de entidade
     * @param in os campos extraídos (pode ser {@code null})
     * @return um novo mapa com as chaves normalizadas
     */
    static Map<String, String> normalizeFields(ContextEntityType t, Map<String, String> in) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (in != null) {
            out.putAll(in);
        }
        if (t == null) {
            return out;
        }
        switch (t) {
            case PERSON -> {
                if (!out.containsKey("about")) {
                    String about = firstNonBlank(out.get("description"), out.get("bio"));
                    if (about != null) {
                        out.put("about", about);
                        out.remove("description");
                        out.remove("bio");
                    }
                }
                if (!out.containsKey("occupation") && out.containsKey("role")) {
                    out.put("occupation", out.get("role"));
                    out.remove("role");
                }
            }
            case PROJECT -> {
                if (!out.containsKey("description") && out.containsKey("summary")) {
                    out.put("description", out.get("summary"));
                    out.remove("summary");
                }
            }
            case EVENT -> {
                // Tolerante: se o modelo usou "name" em vez de "title" para um
                // evento, remapeia para "title" — evita drop silencioso no
                // ActionValidator (que exige "title" para EVENT/TASK).
                if (!out.containsKey("title") && out.containsKey("name")) {
                    out.put("title", out.get("name"));
                    out.remove("name");
                }
                if (!out.containsKey("start")) {
                    String date = firstNonBlank(out.get("date"), out.get("start_date"));
                    String time = firstNonBlank(out.get("time"), out.get("start_time"));
                    if (date != null && time != null) {
                        out.put("start", date.trim() + " " + time.trim());
                        out.remove("date");
                        out.remove("time");
                    } else if (date != null) {
                        out.put("start", date.trim());
                        out.remove("date");
                    } else if (out.containsKey("when")) {
                        out.put("start", out.get("when"));
                        out.remove("when");
                    }
                }
                if (!out.containsKey("end")) {
                    String date = out.get("end_date");
                    String time = out.get("end_time");
                    if (date != null && time != null) {
                        out.put("end", date.trim() + " " + time.trim());
                    } else if (date != null) {
                        out.put("end", date.trim());
                    }
                }
                if (!out.containsKey("location") && out.containsKey("place")) {
                    out.put("location", out.get("place"));
                    out.remove("place");
                }
            }
            case TASK -> {
                // Tolerante: remapeia "name" -> "title" para TASK.
                if (!out.containsKey("title") && out.containsKey("name")) {
                    out.put("title", out.get("name"));
                    out.remove("name");
                }
                if (!out.containsKey("deadline")) {
                    String due = firstNonBlank(out.get("due"), out.get("dueDate"), out.get("date"), out.get("when"));
                    if (due != null) {
                        out.put("deadline", due);
                        out.remove("due");
                        out.remove("dueDate");
                        out.remove("date");
                        out.remove("when");
                    }
                }
            }
            case NOTE -> {
                if (!out.containsKey("content")) {
                    String content = firstNonBlank(out.get("text"), out.get("body"));
                    if (content != null) {
                        out.put("content", content);
                        out.remove("text");
                        out.remove("body");
                    }
                }
            }
            case PROFILE -> {
                // Profile fields use the canonical UserProfile property names.
                if (!out.containsKey("studies") && out.containsKey("education")) out.put("studies", out.remove("education"));
                if (!out.containsKey("skills") && out.containsKey("competencies")) out.put("skills", out.remove("competencies"));
                if (!out.containsKey("workStyle") && out.containsKey("work_style")) out.put("workStyle", out.remove("work_style"));
                if (!out.containsKey("occupation") && out.containsKey("profession")) out.put("occupation", out.remove("profession"));
                // birthDate canónico (aliases birthday/birth_date, spec #7).
                if (!out.containsKey("birthDate") && out.containsKey("birthday")) out.put("birthDate", out.remove("birthday"));
                if (!out.containsKey("birthDate") && out.containsKey("birth_date")) out.put("birthDate", out.remove("birth_date"));
                // aiContext é texto MANUAL do utilizador. Informação inferida sem
                // campo estruturado deve ir para inferredContext (spec). Reemapeia
                // cedo para que ProposalStore, dedup, UI e notificações fiquem
                // coerentes desde o início — não só no momento do Accept.
                if (out.containsKey("aiContext") && !out.containsKey("inferredContext")) {
                    out.put("inferredContext", out.remove("aiContext"));
                } else if (out.containsKey("aiContext")) {
                    out.remove("aiContext");
                }
                if (!out.containsKey("inferredContext") && out.containsKey("inferred_context")) out.put("inferredContext", out.remove("inferred_context"));
            }
        }
        // Deterministic Java-side temporal normalization for relative language.
        if (out.containsKey("deadline")) out.put("deadline", normalizeDateTime(out.get("deadline")));
        if (out.containsKey("start")) out.put("start", normalizeDateTime(out.get("start")));
        if (out.containsKey("end")) out.put("end", normalizeDateTime(out.get("end")));
        return out;
    }

    /** Resolves natural relative dates with the existing system timezone temporal parser. */
    private static String normalizeDateTime(String value) {
        if (value == null || value.isBlank()) return value;
        String v = value.trim();
        try {
            if (v.matches("\\d{4}-\\d{2}-\\d{2}(?: \\d{2}:\\d{2})?")) return v;
            java.util.Optional<java.time.LocalDateTime> resolved = util.TemporalParser
                    .resolve(v, java.time.ZoneId.systemDefault(), java.util.Locale.getDefault())
                    .asDateTime();
            return resolved.map(dt -> dt.format(DT)).orElse(v);
        } catch (RuntimeException e) {
            return v;
        }
    }

    /** Devolve o primeiro argumento não nulo e não em branco, ou {@code null}. */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Task findTaskById(String id) {
        return VaultManager.listTasks().stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
    }

    private static Event findEventById(String id) {
        return VaultManager.listEvents().stream().filter(e -> id.equals(e.getId())).findFirst().orElse(null);
    }

    private static Person findPersonById(String id) {
        return VaultManager.listPeople().stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null);
    }

    private static Project findProjectById(String id) {
        return VaultManager.listProjects().stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null);
    }

    private static Note findNoteById(String id) {
        return VaultManager.listNotes().stream().filter(n -> id.equals(n.getId())).findFirst().orElse(null);
    }

    /** Encontra uma entidade por id, qualquer que seja o tipo, para obter o nome. */
    private static AetherEntity findEntityById(ContextEntityType t, String id) {
        return switch (t) {
            case PERSON -> findPersonById(id);
            case PROJECT -> findProjectById(id);
            case EVENT -> findEventById(id);
            case TASK -> findTaskById(id);
            case NOTE -> findNoteById(id);
            case PROFILE -> null;
        };
    }

    private static String displayName(AetherEntity e) {
        if (e == null) return null;
        return switch (e) {
            case Person p -> p.getName();
            case Project p -> p.getName();
            case Event ev -> ev.getTitle();
            case Task tk -> tk.getTitle();
            case Note n -> n.getContent();
            default -> null;
        };
    }

    private static void applyFieldsToTask(Task task, Map<String, String> f) {
        if (f.containsKey("title")) task.setTitle(f.get("title"));
        if (f.containsKey("description")) task.setDescription(f.get("description"));
        if (f.containsKey("deadline")) task.setDeadline(parseDt(f.get("deadline")));
        if (f.containsKey("status")) task.setStatus(parseEnum(TaskStatus.class, f.get("status"), task.getStatus()));
        if (f.containsKey("priority")) task.setPriority(parseEnum(TaskPriority.class, f.get("priority"), task.getPriority()));
    }

    private static void applyFieldsToEvent(Event event, Map<String, String> f) {
        if (f.containsKey("title")) event.setTitle(f.get("title"));
        if (f.containsKey("description")) event.setDescription(f.get("description"));
        if (f.containsKey("location")) event.setLocation(f.get("location"));
        if (f.containsKey("start")) event.setStartDateTime(parseDt(f.get("start")));
        if (f.containsKey("end")) event.setEndDateTime(parseDt(f.get("end")));
    }

    private static void applyFieldsToPerson(Person person, Map<String, String> f) {
        if (f.containsKey("name")) person.setName(f.get("name"));
        if (f.containsKey("occupation")) person.setOccupation(f.get("occupation"));
        if (f.containsKey("about")) person.setAbout(f.get("about"));
        if (f.containsKey("birthday")) person.setBirthDate(parseDate(f.get("birthday")));
    }

    private static void applyFieldsToProject(Project project, Map<String, String> f) {
        if (f.containsKey("name")) project.setName(f.get("name"));
        if (f.containsKey("description")) project.setDescription(f.get("description"));
        if (f.containsKey("deadline")) project.setDeadline(parseDt(f.get("deadline")));
        if (f.containsKey("status")) project.setStatus(parseEnum(domain.entities.ProjectStatus.class, f.get("status"), project.getStatus()));
    }

    private static void applyFieldsToNote(Note note, Map<String, String> f) {
        if (f.containsKey("content")) note.setContent(f.get("content"));
    }
}
