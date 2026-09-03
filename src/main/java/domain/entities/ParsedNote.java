package domain.entities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resultado do parse de uma nota em linguagem natural pelo NoteParser.
 * <p>
 * Contém as entidades extraídas (pessoas, projetos, eventos, tarefas),
 * as relações entre elas, tags e níveis de confiança.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class ParsedNote {

    /** Tipo principal da nota: event, task, note, person, project. */
    private String type = "note";

    /** Título sugerido para a nota. */
    private String title = "";

    /** Data extraída (formato yyyy-MM-dd), ou null. */
    private String date = null;

    /** Hora extraída (formato HH:mm), ou null. */
    private String time = null;

    /** Local extraído, ou null. */
    private String location = null;

    /** Pessoas mencionadas na nota. */
    private List<String> people = new ArrayList<>();

    /** Projetos mencionados na nota. */
    private List<String> projects = new ArrayList<>();

    /** Tags extraídas da nota. */
    private List<String> tags = new ArrayList<>();

    /** Relações entre entidades: {from, to, type}. */
    private List<Map<String, String>> relationships = new ArrayList<>();

    /** Níveis de confiança por categoria. */
    private Map<String, String> confidence = new HashMap<>();

    /** Texto original em linguagem natural. */
    private String originalText = "";

    /** Resumo do que o AETHER extraiu (para mostrar ao utilizador). */
    private String summary = "";

    /**
     * Cria um ParsedNote vazio.
     */
    public ParsedNote() {
        // Construtor por omissão.
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type != null ? type : "note";
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title != null ? title : "";
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public List<String> getPeople() {
        return people;
    }

    public void setPeople(List<String> people) {
        this.people = people != null ? people : new ArrayList<>();
    }

    public List<String> getProjects() {
        return projects;
    }

    public void setProjects(List<String> projects) {
        this.projects = projects != null ? projects : new ArrayList<>();
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public List<Map<String, String>> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<Map<String, String>> relationships) {
        this.relationships = relationships != null ? relationships : new ArrayList<>();
    }

    public Map<String, String> getConfidence() {
        return confidence;
    }

    public void setConfidence(Map<String, String> confidence) {
        this.confidence = confidence != null ? confidence : new HashMap<>();
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText != null ? originalText : "";
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary != null ? summary : "";
    }

    /**
     * Verifica se o parse extraiu alguma entidade útil.
     *
     * @return {@code true} se há pessoas, projetos, eventos ou tarefas
     */
    public boolean hasExtractions() {
        return !people.isEmpty() || !projects.isEmpty() || type != null && !type.equals("note");
    }

    @Override
    public String toString() {
        return "ParsedNote{" +
                "type='" + type + '\'' +
                ", title='" + title + '\'' +
                ", date='" + date + '\'' +
                ", time='" + time + '\'' +
                ", people=" + people +
                ", projects=" + projects +
                ", tags=" + tags +
                ", confidence=" + confidence +
                '}';
    }
}
