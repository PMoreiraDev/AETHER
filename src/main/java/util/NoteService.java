package util;

import domain.entities.Note;
import domain.entities.ParsedNote;
import domain.entities.Person;
import domain.entities.Project;
import persistence.VaultManager;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NoteService — a ligação em falta entre o {@link NoteParser} e o
 * {@link VaultManager}.
 * <p>
 * Quando o utilizador adiciona uma nota (a partir do dashboard ou da vista de
 * Notas), este serviço:
 * </p>
 * <ol>
 *   <li>Persiste imediatamente a nota no vault (mesmo que o Ollama esteja
 *       indisponível), corrigindo o erro em que as notas eram processadas mas
 *       nunca guardadas.</li>
 *   <li>Extrai entidades (pessoas, projetos) com o {@code NoteParser}, quando o
 *       Ollama está disponível.</li>
 *   <li>Cria {@code Person}/{@code Project} em falta no vault e enriquece o
 *       corpo da nota com wikilinks {@code [[Nome]]}, para que as relações
 *       fiquem registadas nos dados (não apenas no grafo desenhado).</li>
 *   <li>Re-guarda a nota enriquecida e devolve um resumo para feedback na UI.</li>
 * </ol>
 * <p>
 * Este é o único caminho de processamento de notas — não duplica nenhum
 * sistema existente. É chamado a partir de tarefas em segundo plano para não
 * bloquear o fio da interface JavaFX.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class NoteService {

    /** Logger. */
    private static final Logger LOGGER = Logger.getLogger(NoteService.class.getName());

    private NoteService() {
        // Classe utilitária.
    }

    /**
     * Processa e persiste uma nota em linguagem natural.
     * <p>
     * A nota é sempre guardada no vault, mesmo se a extração de entidades não
     * estiver disponível. A extração e o enriquecimento (criação de pessoas/
     * projetos em falta e adição de wikilinks) decorrem como melhor esforço
     * quando o Ollama está disponível.
     * </p>
     *
     * @param text o texto da nota
     * @return resumo do que foi extraído, ou uma mensagem de confirmação
     */
    public static String processAndPersist(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isBlank()) {
            return "Empty note.";
        }

        // 1. Persistir imediatamente — a nota existe no vault mesmo se o
        //    Ollama estiver em baixo.
        Note note = new Note();
        note.setContent(trimmed);
        VaultManager.saveNote(note, trimmed);

        // 2. Extração de entidades como melhor esforço (requer Ollama).
        ParsedNote parsed = null;
        try {
            parsed = NoteParser.parse(trimmed);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "NoteParser falhou ao processar a nota: {0}", e.getMessage());
        }

        if (parsed == null) {
            return "Note saved.";
        }

        // 3. Criar pessoas/projetos em falta e enriquecer o corpo da nota com
        //    wikilinks, para que as relações fiquem nos dados.
        String enriched = trimmed;
        for (String name : parsed.getPeople()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String cleanName = name.trim();
            if (VaultManager.findPerson(cleanName).isEmpty()) {
                Person stub = new Person();
                stub.setName(cleanName);
                VaultManager.savePerson(stub);
            }
            enriched = VaultManager.addWikilink(enriched, cleanName);
        }
        for (String name : parsed.getProjects()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String cleanName = name.trim();
            if (VaultManager.findProject(cleanName).isEmpty()) {
                Project stub = new Project();
                stub.setName(cleanName);
                VaultManager.saveProject(stub);
            }
            enriched = VaultManager.addWikilink(enriched, cleanName);
        }

        // 4. Re-guardar a nota enriquecida (mesmo id → mesmo ficheiro).
        if (!enriched.equals(trimmed)) {
            note.setContent(enriched);
            VaultManager.saveNote(note, trimmed);
        }

        return parsed.getSummary();
    }
}
