package util;

import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import domain.entities.ProjectDocument;
import persistence.VaultManager;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GlobalSearchService — pesquisa global do AETHER sobre todos os tipos de
 * entidade do vault.
 * <p>
 * Não cria cinco mecanismos de pesquisa independentes: delega ao
 * {@link VaultIndex} (fonte única de verdade em memória) e aplica correspondência
 * case-insensitive e diacritics-insensitive sobre títulos, nomes, conteúdo e
 * descrições. Suporta correspondência exata, parcial e por tokens.
 * </p>
 * <p>
 * O vault permanece a fonte de persistência; este serviço é um leitor derivado.
 * </p>
 *
 * @author AETHER
 */
public final class GlobalSearchService {

    /** Limite de resultados por tipo, para evitarUI sobrecarregada. */
    private static final int MAX_PER_TYPE = 12;

    private GlobalSearchService() {
        // Classe de utilitário estático.
    }

    /**
     * Pesquisa em todas as entidades do vault.
     *
     * @param query o termo de pesquisa (pode ser várias palavras)
     * @return resultados agrupados por tipo, nunca {@code null}
     */
    public static SearchResult search(String query) {
        return search(query, VaultIndex.getInstance());
    }

    /**
     * Variante injetável para testes: permite passar um VaultIndex controlado.
     *
     * @param query o termo de pesquisa
     * @param index o index a pesquisar
     * @return resultados agrupados por tipo
     */
    static SearchResult search(String query, VaultIndex index) {
        String q = normalize(query);
        SearchResult result = new SearchResult();

        if (q.isBlank()) {
            return result;
        }
        // Tokens significativos (>=3 chars) para correspondência parcial.
        List<String> qTokens = tokens(q);

        for (Person p : index.people()) {
            String hay = normalize(p.getName() + " " + p.getOccupation() + " " + p.getAbout());
            if (matches(q, qTokens, hay)) {
                result.people.add(p);
                if (result.people.size() >= MAX_PER_TYPE) {
                    break;
                }
            }
        }
        for (Project p : index.projects()) {
            String hay = normalize(p.getName() + " " + p.getDescription());
            if (matches(q, qTokens, hay)) {
                result.projects.add(p);
                if (result.projects.size() >= MAX_PER_TYPE) {
                    break;
                }
            }
        }
        for (Event e : index.events()) {
            String hay = normalize(e.getTitle() + " " + e.getLocation() + " " + e.getDescription());
            if (matches(q, qTokens, hay)) {
                result.events.add(e);
                if (result.events.size() >= MAX_PER_TYPE) {
                    break;
                }
            }
        }
        for (Task t : index.tasks()) {
            String hay = normalize(t.getTitle() + " " + t.getDescription());
            if (matches(q, qTokens, hay)) {
                result.tasks.add(t);
                if (result.tasks.size() >= MAX_PER_TYPE) {
                    break;
                }
            }
        }
        for (Note n : index.notes()) {
            String hay = normalize(n.getContent());
            if (matches(q, qTokens, hay)) {
                result.notes.add(n);
                if (result.notes.size() >= MAX_PER_TYPE) break;
            }
        }
        for (ProjectDocument d : ProjectDocumentService.listAll()) {
            String hay = normalize(d.getOriginalFilename() + " " + d.getType());
            if (matches(q, qTokens, hay)) {
                result.documents.add(d);
                if (result.documents.size() >= MAX_PER_TYPE) break;
            }
        }

        return result;
    }

    /**
     * Devolve as relações (wikilinks) de uma entidade pelo seu label, útil como
     * pesquisa auxiliar de relações.
     *
     * @param label o nome/título da entidade
     * @return lista de nomes relacionados, ou vazia
     */
    public static List<String> relatedLinks(String label) {
        if (label == null || label.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> graph = VaultManager.buildGraphData();
        return graph.getOrDefault(label.trim(), List.of());
    }

    // ------------------------------------------------------------------
    // Matching helpers
    // ------------------------------------------------------------------

    /**
     * Verifica se o query corresponde ao haystack. Correspondência exata do
     * query completo tem prioridade; em seguida, correspondência por tokens.
     */
    private static boolean matches(String qNorm, List<String> qTokens, String hayNorm) {
        if (hayNorm.isBlank()) {
            return false;
        }
        if (hayNorm.contains(qNorm)) {
            return true;
        }
        for (String t : qTokens) {
            if (hayNorm.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** Normaliza: minúsculas + sem diacríticos. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();
    }

    /** Tokeniza um query normalizado em termos significativos. */
    private static List<String> tokens(String norm) {
        List<String> out = new ArrayList<>();
        for (String t : norm.split("[^a-z0-9]+")) {
            if (t.length() >= 3) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Result container
    // ------------------------------------------------------------------

    /**
     * Resultado de pesquisa global, agrupado por tipo de entidade.
     */
    public static final class SearchResult {
        private final List<Person> people = new ArrayList<>();
        private final List<Project> projects = new ArrayList<>();
        private final List<Event> events = new ArrayList<>();
        private final List<Task> tasks = new ArrayList<>();
        private final List<Note> notes = new ArrayList<>();
        private final List<ProjectDocument> documents = new ArrayList<>();

        public List<Person> people() {
            return people;
        }

        public List<Project> projects() {
            return projects;
        }

        public List<Event> events() {
            return events;
        }

        public List<Task> tasks() {
            return tasks;
        }

        public List<Note> notes() {
            return notes;
        }

        public List<ProjectDocument> documents() {
            return documents;
        }

        /** Total de resultados em todos os tipos. */
        public int total() {
            return people.size() + projects.size() + events.size()
                    + tasks.size() + notes.size() + documents.size();
        }

        /** Indica se há resultados. */
        public boolean isEmpty() {
            return total() == 0;
        }

        /** Devolve o conjunto de tipos com resultados (para headers de UI). */
        public Set<String> typesWithResults() {
            Set<String> types = new LinkedHashSet<>();
            if (!people.isEmpty()) types.add("People");
            if (!projects.isEmpty()) types.add("Projects");
            if (!events.isEmpty()) types.add("Events");
            if (!tasks.isEmpty()) types.add("Tasks");
            if (!notes.isEmpty()) types.add("Notes");
            if (!documents.isEmpty()) types.add("Documents");
            return types;
        }
    }
}
