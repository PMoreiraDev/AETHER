package controller;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import domain.entities.Note;
import persistence.VaultManager;
import util.AetherDialogs;
import util.NoteService;

/**
 * Controlador da vista de Notas ({@code notes_view.fxml}).
 * <p>
 * Apresenta a lista de notas do vault e permite adicionar novas notas
 * em linguagem natural. O AETHER processa cada nota com o NoteParser
 * para extrair entidades (pessoas, eventos, tarefas) automaticamente.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class NotesViewController implements Initializable {

    public NotesViewController() {
        // Construtor por omissão.
    }

    private static final Logger LOGGER = Logger.getLogger(NotesViewController.class.getName());

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private VBox notesContainer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadNotes();
    }

    private void loadNotes() {
        notesContainer.getChildren().clear();
        var notes = VaultManager.listNotes();

        if (notes.isEmpty()) {
            Label empty = new Label("No notes yet. Add a quick note to get started.");
            empty.getStyleClass().add("dash-empty-state");
            notesContainer.getChildren().add(empty);
            return;
        }

        for (Note note : notes) {
            notesContainer.getChildren().add(createNoteCard(note));
        }
    }

    private VBox createNoteCard(Note note) {
        VBox card = new VBox(6);
        card.getStyleClass().addAll("dash-card", "dash-summary-card");
        card.setPadding(new Insets(14));
        card.setCursor(javafx.scene.Cursor.HAND);

        String rawContent = note.getContent();
        String firstLine = "Untitled Note";
        if (rawContent != null && !rawContent.isBlank()) {
            firstLine = rawContent.trim().split("\n")[0];
            if (firstLine.length() > 50) {
                firstLine = firstLine.substring(0, 50) + "...";
            }
            if (firstLine.isEmpty()) {
                firstLine = "Untitled Note";
            }
        }
        Label titleLabel = new Label(firstLine);
        titleLabel.getStyleClass().add("dash-card-title-sm");

        // Cabeçalho com título à esquerda e botão Delete à direita.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, titleLabel, spacer,
                AetherDialogs.deleteButton(firstLine, () -> {
                    VaultManager.deleteNote(note);
                    loadNotes();
                }));
        header.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(header);

        if (rawContent != null && !rawContent.isBlank() && rawContent.length() > 50) {
            String preview = rawContent.length() > 120 ? rawContent.substring(0, 120) + "..." : rawContent;
            Label previewLabel = new Label(preview);
            previewLabel.getStyleClass().add("dash-empty-state");
            previewLabel.setWrapText(true);
            card.getChildren().add(previewLabel);
        }

        if (note.getCreatedAt() != null) {
            Label date = new Label(note.getCreatedAt().format(DT_FMT));
            date.getStyleClass().add("dash-list-item-sub");
            card.getChildren().add(date);
        }

        return card;
    }

    @FXML
    private void handleAddNote() {
        showAddNoteDialog();
    }

    private void showAddNoteDialog() {
        AetherDialogs.showAddNoteDialog().ifPresent(this::processAndSaveNote);
    }

    /**
     * Processa uma nota em linguagem natural e guarda-a no vault.
     * <p>
     * Delega no {@link NoteService}, que persiste a nota imediatamente e,
     * quando o Ollama está disponível, extrai entidades, cria pessoas/projetos
     * em falta e enriquece a nota com wikilinks.
     * </p>
     *
     * @param text o texto da nota
     */
    private void processAndSaveNote(String text) {
        // Mostrar estado de processamento
        Label processingLabel = new Label("Processing note with AETHER AI...");
        processingLabel.getStyleClass().add("dash-empty-state");
        notesContainer.getChildren().add(processingLabel);

        Task<String> parseTask = new Task<>() {
            @Override
            protected String call() {
                return NoteService.processAndPersist(text);
            }
        };

        parseTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                notesContainer.getChildren().remove(processingLabel);
                loadNotes();

                // Mostrar feedback do que foi extraído
                String summary = parseTask.getValue();
                if (summary != null && !summary.isBlank()) {
                    Label feedback = new Label("AETHER: " + summary);
                    feedback.getStyleClass().add("dash-list-item-sub");
                    feedback.setWrapText(true);
                    notesContainer.getChildren().add(0, feedback);
                }
            });
        });

        parseTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                notesContainer.getChildren().remove(processingLabel);
                loadNotes();
            });
        });

        Thread thread = new Thread(parseTask, "aether-note-parse");
        thread.setDaemon(true);
        thread.start();
    }
}
