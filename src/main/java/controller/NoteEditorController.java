package controller;

import ai.NoteAnalysisService;
import ai.ResolvedAction;
import domain.entities.Note;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import persistence.VaultManager;
import session.UserSession;
import util.I18n;
import util.NoteEditorContext;
import util.NoteMarkdownRenderer;
import util.NoteService;
import util.VaultRefreshBus;

/**
 * NoteEditorController — o editor de notas dedicado do AETHER.
 *
 * <p>Substitui o antigo modal de 4 linhas por uma experiência de escrita real,
 * dentro do shell existente:</p>
 * <ul>
 *   <li>Campo de <b>título</b> explícito (persistido no frontmatter da nota).</li>
 *   <li>Toolbar de markdown que insere sintaxe na posição do cursor (Bold,
 *       Italic, H1/H2, listas, checklist, quote, code, link, divisor).</li>
 *   <li>Toggle <b>Edit / Preview</b> — a pré-visualização é gerada por
 *       {@link NoteMarkdownRenderer} num {@link TextFlow} seguro.</li>
 *   <li><b>Autosave</b> debounced (2s de inatividade) com estado visível:
 *       "A guardar..." / "Guardado" / "Alterações por guardar" / "Erro".</li>
 *   <li><b>Feedback assíncrono da IA</b> após guardar: "✦ O AETHER está a
 *       perceber esta nota..." → "✦ O AETHER encontrou N sugestões".</li>
 * </ul>
 *
 * <p>A IA nunca escreve no vault como consequência direta de guardar. As
 * propostas resultantes ficam disponíveis na vista de Notas, onde o
 * utilizador aprova ou rejeita — o modelo de confiança do AETHER mantém-se.</p>
 *
 * @author AETHER
 */
public class NoteEditorController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(NoteEditorController.class.getName());

    /** Instância ativa do editor (para o atalho Ctrl+S do shell). */
    private static final AtomicReference<NoteEditorController> ACTIVE = new AtomicReference<>();

    @FXML private VBox root;
    @FXML private Button backButton;
    @FXML private TextField titleField;
    @FXML private ToggleButton editModeBtn;
    @FXML private ToggleButton previewModeBtn;
    @FXML private StackPane editorStack;
    @FXML private TextArea bodyArea;
    @FXML private VBox previewFlow;
    @FXML private HBox aiBanner;
    @FXML private Label aiBannerIcon;
    @FXML private Label aiBannerText;
    @FXML private Label saveStateLabel;
    @FXML private Button saveButton;

    private Note note;
    private boolean dirty = false;
    private boolean previewMode = false;
    private java.util.Timer autosaveTimer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        ACTIVE.set(this);
        this.note = NoteEditorContext.consumePending();

        if (note.getTitle() != null && !note.getTitle().isBlank()) {
            titleField.setText(note.getTitle());
        }
        if (note.getContent() != null) {
            bodyArea.setText(note.getContent());
        }

        // Estado inicial: só "Alterações por guardar" se a nota for nova.
        if (note.getId() == null || note.getId().isBlank()) {
            setSaveState(SaveState.UNSAVED);
        } else {
            setSaveState(SaveState.SAVED);
            dirty = false;
        }

        // Listener de sujidade → autosave debounced.
        ChangeListener<String> dirtyListener = (obs, oldV, newV) -> markDirty();
        titleField.textProperty().addListener(dirtyListener);
        bodyArea.textProperty().addListener(dirtyListener);

        // Modo inicial: Edit.
        selectMode(true);

        backButton.setOnAction(e -> handleBack());
    }

    // ------------------------------------------------------------------
    // Save state
    // ------------------------------------------------------------------

    private enum SaveState { SAVING, SAVED, UNSAVED, ERROR }

    private void setSaveState(SaveState state) {
        Platform.runLater(() -> {
            saveStateLabel.getStyleClass().removeAll(
                    "save-state-saving", "save-state-saved", "save-state-unsaved", "save-state-error");
            switch (state) {
                case SAVING -> {
                    saveStateLabel.setText(I18n.tr("notes.editor.saving"));
                    saveStateLabel.getStyleClass().add("save-state-saving");
                }
                case SAVED -> {
                    saveStateLabel.setText(I18n.tr("notes.editor.saved"));
                    saveStateLabel.getStyleClass().add("save-state-saved");
                }
                case UNSAVED -> {
                    saveStateLabel.setText(I18n.tr("notes.editor.unsaved"));
                    saveStateLabel.getStyleClass().add("save-state-unsaved");
                }
                case ERROR -> {
                    saveStateLabel.setText(I18n.tr("notes.editor.error"));
                    saveStateLabel.getStyleClass().add("save-state-error");
                }
            }
        });
    }

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            if (!previewMode) {
                setSaveState(SaveState.UNSAVED);
            }
        }
        scheduleAutosave();
    }

    /** Autosave debounced: guarda 2s após a última alteração. */
    private void scheduleAutosave() {
        if (autosaveTimer != null) {
            autosaveTimer.cancel();
        }
        autosaveTimer = new java.util.Timer("aether-note-autosave", true);
        autosaveTimer.schedule(new java.util.TimerTask() {
            @Override public void run() {
                Platform.runLater(() -> doSave(false));
            }
        }, 2000L);
    }

    // ------------------------------------------------------------------
    // Save + AI understanding
    // ------------------------------------------------------------------

    /** Guarda a nota. @param explicit true se vindo do botão/atalho. */
    private void doSave(boolean explicit) {
        if (!dirty && !explicit) {
            return;
        }
        setSaveState(SaveState.SAVING);
        String title = titleField.getText();
        String body = bodyArea.getText();

        Task<Note> task = new Task<>() {
            @Override protected Note call() {
                note.setTitle(title);
                note.setContent(body);
                if (note.getCreatedAt() == null) {
                    note.setCreatedAt(java.time.LocalDateTime.now());
                }
                note.setUpdatedAt(java.time.LocalDateTime.now());
                Note saved = NoteService.persist(note);
                return saved;
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            Note saved = task.getValue();
            if (saved != null) {
                this.note = saved;
                dirty = false;
                setSaveState(SaveState.SAVED);
                VaultRefreshBus.publish(VaultRefreshBus.ChangeType.UPDATED, "note");
                // Após guardar: a IA compreende a nota em segundo plano.
                triggerUnderstanding(saved);
            } else {
                setSaveState(SaveState.ERROR);
            }
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            LOGGER.warning(() -> "Save failed: " + (task.getException() != null ? task.getException().getMessage() : "unknown"));
            setSaveState(SaveState.ERROR);
        }));
        Thread t = new Thread(task, "aether-note-save");
        t.setDaemon(true);
        t.start();
    }

    /** Feedback assíncrono: "✦ AETHER está a perceber esta nota..." → resultado. */
    private void triggerUnderstanding(Note saved) {
        String modelId = UserSession.getInstance().getAppSettings().getActiveModelId();
        if (modelId == null || modelId.isBlank()) {
            // Sem modelo: não mostra banner. A nota está guardada na mesma.
            return;
        }
        showAiBanner(I18n.tr("notes.understanding.start"), false);

        Task<List<ResolvedAction>> analyze = new Task<>() {
            @Override protected List<ResolvedAction> call() {
                NoteAnalysisService service = new NoteAnalysisService(modelId, List::of);
                return service.analyze(saved.getContent(), I18n.tr("notes.source.note"));
            }
        };
        analyze.setOnSucceeded(e -> Platform.runLater(() -> {
            int count = analyze.getValue() != null ? analyze.getValue().size() : 0;
            if (count > 0) {
                showAiBanner(I18n.tr("notes.understanding.done", count), true);
            } else {
                showAiBanner(I18n.tr("notes.understanding.none"), true);
            }
        }));
        analyze.setOnFailed(e -> Platform.runLater(() -> showAiBanner(I18n.tr("notes.understanding.none"), true)));
        Thread t = new Thread(analyze, "aether-note-understanding");
        t.setDaemon(true);
        t.start();
    }

    private void showAiBanner(String text, boolean done) {
        aiBanner.setVisible(true);
        aiBanner.setManaged(true);
        aiBannerText.setText(text);
        aiBannerText.getStyleClass().removeAll("ai-understanding-done");
        if (done) {
            aiBannerText.getStyleClass().add("ai-understanding-done");
        }
    }

    // ------------------------------------------------------------------
    // FXML handlers
    // ------------------------------------------------------------------

    @FXML private void handleSave() {
        doSave(true);
    }

    /** Atalho global Ctrl+S do shell: guarda o editor ativo, se houver. */
    public static void saveActive() {
        NoteEditorController active = ACTIVE.get();
        if (active != null) {
            active.doSave(true);
        }
    }

    @FXML private void handleBack() {
        // Garante que nada se perde: se sujo, guarda antes de sair.
        if (dirty) {
            doSave(true);
        }
        DashboardController shell = DashboardController.getActive();
        if (shell != null) {
            shell.showNotesView();
        }
    }

    @FXML public void showEdit() {
        selectMode(true);
    }

    @FXML public void showPreview() {
        refreshPreview();
        selectMode(false);
    }

    private void selectMode(boolean edit) {
        this.previewMode = !edit;
        bodyArea.setVisible(edit);
        bodyArea.setManaged(edit);
        previewFlow.setVisible(!edit);
        previewFlow.setManaged(!edit);
        editModeBtn.setSelected(edit);
        previewModeBtn.setSelected(!edit);
        // A toolbar só faz sentido em modo edição.
        javafx.scene.Node toolbar = root.lookup(".note-toolbar");
        if (toolbar != null) {
            toolbar.setVisible(edit);
            toolbar.setManaged(edit);
        }
        if (!edit) {
            refreshPreview();
        }
    }

    private void refreshPreview() {
        NoteMarkdownRenderer.renderInto(previewFlow, bodyArea.getText());
    }

    // ------------------------------------------------------------------
    // Toolbar — insere sintaxe markdown na posição do cursor
    // ------------------------------------------------------------------

    @FXML private void insertBold() { wrapSelection("**", "**"); }
    @FXML private void insertItalic() { wrapSelection("*", "*"); }
    @FXML private void insertH1() { prefixLine("# "); }
    @FXML private void insertH2() { prefixLine("## "); }
    @FXML private void insertBullet() { prefixLine("- "); }
    @FXML private void insertNumbered() { prefixLine("1. "); }
    @FXML private void insertChecklist() { prefixLine("- [ ] "); }
    @FXML private void insertQuote() { prefixLine("> "); }
    @FXML private void insertCode() { wrapSelection("`", "`"); }
    @FXML private void insertLink() {
        int caret = bodyArea.getCaretPosition();
        String insert = "[text](https://)";
        bodyArea.insertText(caret, insert);
        bodyArea.positionCaret(caret + 1);
        bodyArea.requestFocus();
    }
    @FXML private void insertDivider() {
        int caret = bodyArea.getCaretPosition();
        String insert = (caret > 0 && bodyArea.getText().charAt(caret - 1) != '\n' ? "\n" : "") + "---\n";
        bodyArea.insertText(caret, insert);
        bodyArea.requestFocus();
    }

    private void wrapSelection(String before, String after) {
        String selected = bodyArea.getSelectedText();
        int start = bodyArea.getSelection().getStart();
        int end = bodyArea.getSelection().getEnd();
        if (selected == null || selected.isEmpty()) {
            String placeholder = "text";
            bodyArea.insertText(bodyArea.getCaretPosition(), before + placeholder + after);
            bodyArea.selectRange(start + before.length(), start + before.length() + placeholder.length());
        } else {
            bodyArea.replaceText(start, end, before + selected + after);
        }
        bodyArea.requestFocus();
    }

    private void prefixLine(String prefix) {
        int caret = bodyArea.getCaretPosition();
        String text = bodyArea.getText();
        int lineStart = 0;
        for (int i = caret - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                lineStart = i + 1;
                break;
            }
        }
        bodyArea.insertText(lineStart, prefix);
        bodyArea.positionCaret(caret + prefix.length());
        bodyArea.requestFocus();
    }
}
