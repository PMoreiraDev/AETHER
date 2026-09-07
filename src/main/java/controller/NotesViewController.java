package controller;

import ai.ActionExecutor;
import ai.AiActionProposal;
import ai.NoteAnalysisService;
import ai.ResolvedAction;
import domain.entities.Note;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import persistence.VaultManager;
import session.UserSession;
import util.AetherDialogs;
import util.EmptyState;
import util.I18n;
import util.NoteService;
import util.ProposalCardBuilder;
import util.VaultRefreshBus;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * Controlador da vista de Notas ({@code notes_view.fxml}).
 * <p>
 * Apresenta a lista de notas do vault e permite adicionar novas notas em
 * linguagem natural. Cada nota pode ser <b>analisada com IA</b>: o AETHER
 * compreende a nota e produz <i>propostas</i> (entidades, relações) que o
 * utilizador aprova ou rejeita — a IA nunca escreve no vault diretamente.
 * </p>
 * <p>
 * Fluxo: NOTE → NoteService.persistOnly (guarda a nota) → NoteAnalysisService
 * (compreende + propõe) → ProposalCardBuilder (UI) → USER DECIDES →
 * ActionExecutor → Vault. Nunca NOTE → AI → VAULT.
 * </p>
 *
 * @author Paulo Moreira
 * @version 2.0
 */
public class NotesViewController implements Initializable {

    private static final Logger LOGGER = Logger.getLogger(NotesViewController.class.getName());
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private VBox notesContainer;

    /** Contentor das propostas de IA (acima das notas). */
    private VBox proposalsSection;
    /** Contentor dos cartões de proposta. */
    private VBox proposalsCards;
    /** Header da secção de propostas (com Accept All / Reject All). */
    private HBox proposalsHeader;
    /** Linha de progresso da análise (barra + estado). */
    private HBox analyzeProgressRow;
    private ProgressBar analyzeProgressBar;
    private Label analyzeStatusLabel;
    /** Botão de análise atualmente ativo (desativado durante a análise). */
    private Button activeAnalyzeButton;
    /** Thread da análise em curso (para cancelamento). */
    private volatile Thread analyzeThread;
    /** Bandeira de cancelamento: distingue "cancelada" de "sem propostas". */
    private volatile boolean analysisCancelled;
    /** Botão de cancelar análise. */
    private Button cancelAnalyzeButton;

    /** Propostas atualmente apresentadas (para Accept All / Reject All). */
    private final List<ResolvedAction> currentProposals = new ArrayList<>();

    private void buildAnalyzeProgressRow() {
        analyzeProgressBar = new ProgressBar();
        analyzeProgressBar.setPrefWidth(150);
        analyzeProgressBar.setMaxWidth(150);
        analyzeProgressBar.setPrefHeight(6);
        analyzeStatusLabel = new Label(I18n.tr("notes.analyzing"));
        analyzeStatusLabel.getStyleClass().add("ai-analyze-status");
        cancelAnalyzeButton = new Button(I18n.tr("notes.analyze.cancel"));
        cancelAnalyzeButton.getStyleClass().addAll("proposal-btn", "proposal-btn-tertiary");
        cancelAnalyzeButton.setOnAction(e -> cancelAnalysis());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        analyzeProgressRow = new HBox(10, analyzeProgressBar, analyzeStatusLabel, gap, cancelAnalyzeButton);
        analyzeProgressRow.setAlignment(Pos.CENTER_LEFT);
        analyzeProgressRow.getStyleClass().add("ai-analyze-progress");
        analyzeProgressRow.setVisible(false);
        analyzeProgressRow.setManaged(false);
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        buildProposalsSection();
        loadNotes();
    }

    // ------------------------------------------------------------------
    // Secção de propostas de IA
    // ------------------------------------------------------------------

    private void buildProposalsSection() {
        proposalsSection = new VBox(8);
        proposalsSection.getStyleClass().add("ai-proposals-section");
        proposalsSection.setVisible(false);
        proposalsSection.setManaged(false);

        proposalsHeader = new HBox(10);
        proposalsHeader.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(I18n.tr("notes.proposals.title"));
        title.getStyleClass().add("ai-proposals-section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button acceptAll = new Button(I18n.tr("ai.proposal.acceptAll"));
        acceptAll.getStyleClass().addAll("proposal-btn", "proposal-accept-btn");
        acceptAll.setOnAction(e -> acceptAllProposals());
        Button rejectAll = new Button(I18n.tr("ai.proposal.rejectAll"));
        rejectAll.getStyleClass().addAll("proposal-btn", "proposal-reject-btn");
        rejectAll.setOnAction(e -> rejectAllProposals());
        proposalsHeader.getChildren().addAll(title, spacer, acceptAll, rejectAll);

        proposalsCards = new VBox(10);
        buildAnalyzeProgressRow();
        proposalsSection.getChildren().addAll(proposalsHeader, analyzeProgressRow, proposalsCards);

        // Insere a secção no topo do contentor de notas (acima das notas).
        if (!notesContainer.getChildren().contains(proposalsSection)) {
            notesContainer.getChildren().add(0, proposalsSection);
        }
    }

    private void showProposals(List<ResolvedAction> proposals) {
        Platform.runLater(() -> {
            proposalsCards.getChildren().clear();
            currentProposals.clear();
            if (proposals == null || proposals.isEmpty()) {
                Label none = new Label(I18n.tr("notes.proposals.none"));
                none.getStyleClass().add("dash-empty-state");
                none.setWrapText(true);
                proposalsCards.getChildren().add(none);
                proposalsSection.setVisible(true);
                proposalsSection.setManaged(true);
                return;
            }
            for (ResolvedAction ra : proposals) {
                VBox card = ProposalCardBuilder.build(ra, new ProposalCardBuilder.Callbacks() {
                    @Override
                    public void onAccept(Runnable lock) {
                        acceptProposal(ra, lock);
                    }
                    @Override
                    public void onReject(Runnable lock) {
                        rejectProposal(ra);
                    }
                    @Override
                    public void onEdit(Runnable lock) {
                        // Edição inline não suportada nesta versão — dica ao utilizador.
                        Label hint = new Label(I18n.tr("ai.proposal.editHint"));
                        hint.getStyleClass().add("ai-proposal-subtitle");
                        hint.setWrapText(true);
                        proposalsCards.getChildren().add(hint);
                    }
                });
                proposalsCards.getChildren().add(card);
                currentProposals.add(ra);
            }
            proposalsSection.setVisible(true);
            proposalsSection.setManaged(true);
        });
    }

    /** Aceita uma proposta: executa no vault e marca o estado. */
    private void acceptProposal(ResolvedAction ra, Runnable markError) {
        new Thread(() -> {
            boolean ok = ai.ApprovalFlow.executeApproved(ra.proposal).success();
            Platform.runLater(() -> {
                if (ok) {
                    markAcceptedInStore(ra);
                    VaultRefreshBus.publish(VaultRefreshBus.ChangeType.UPDATED,
                            ra.proposal.getEntityType().name());
                } else {
                    markFailedInStore(ra);
                    if (markError != null) markError.run();
                }
            });
        }, "aether-proposal-execute").start();
    }

    private void rejectProposal(ResolvedAction ra) {
        markRejectedInStore(ra);
    }

    private void acceptAllProposals() {
        List<ResolvedAction> snapshot = new ArrayList<>(currentProposals);
        snapshot.forEach(ra -> acceptProposal(ra, () -> {}));
    }

    private void rejectAllProposals() {
        List<ResolvedAction> snapshot = new ArrayList<>(currentProposals);
        snapshot.forEach(this::rejectProposal);
        Platform.runLater(() -> {
            proposalsCards.getChildren().clear();
            currentProposals.clear();
            proposalsSection.setVisible(false);
            proposalsSection.setManaged(false);
        });
    }

    // ------------------------------------------------------------------
    // Notas
    // ------------------------------------------------------------------

    private void loadNotes() {
        // Mantém a secção de propostas no topo; limpa o resto.
        notesContainer.getChildren().removeIf(n -> n != proposalsSection);
        if (!notesContainer.getChildren().contains(proposalsSection)) {
            notesContainer.getChildren().add(0, proposalsSection);
        }

        var notes = VaultManager.listNotes();
        if (notes.isEmpty()) {
            notesContainer.getChildren().add(
                    EmptyState.of("empty.notes.title", "empty.notes.hint")
                            .cta("empty.notes.cta", () -> handleAddNote())
                            .build());
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

        String rawContent = note.getContent();
        String firstLine = note.displayTitle();
        if (firstLine == null || firstLine.isBlank()) {
            firstLine = I18n.tr("notes.untitled");
        }
        Label titleLabel = new Label(firstLine);
        titleLabel.getStyleClass().add("dash-card-title-sm");
        titleLabel.setMouseTransparent(false);
        titleLabel.setStyle("-fx-cursor: hand;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button analyzeBtn = new Button(I18n.tr("notes.analyze"));
        analyzeBtn.getStyleClass().addAll("proposal-btn", "proposal-btn-tertiary");
        analyzeBtn.setOnAction(e -> analyzeNote(note, analyzeBtn));

        HBox header = new HBox(10, titleLabel, spacer, analyzeBtn,
                AetherDialogs.deleteButton(firstLine, () -> {
                    VaultManager.deleteNote(note);
                    loadNotes();
                }));
        header.setAlignment(Pos.CENTER_LEFT);
        // Clicar no cartão abre o editor com a nota existente.
        card.setOnMouseClicked(e -> openNoteInEditor(note));
        card.setStyle("-fx-cursor: hand;");
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
        DashboardController shell = DashboardController.getActive();
        if (shell != null) {
            shell.openNoteEditor(new domain.entities.Note());
        } else {
            AetherDialogs.showAddNoteDialog().ifPresent(this::processAndSaveNote);
        }
    }

    /** Abre uma nota existente no editor dedicado. */
    private void openNoteInEditor(Note note) {
        DashboardController shell = DashboardController.getActive();
        if (shell != null) {
            shell.openNoteEditor(note);
        }
    }

    /**
     * Guarda a nota (sem extração automática) e lança a análise que produz
     * <i>propostas</i> — não entidades confirmadas. A nota existe no vault
     * imediatamente; as propostas só se concretizam se o utilizador aprovar.
     */
    private void processAndSaveNote(String text) {
        Label processingLabel = new Label(I18n.tr("notes.processing"));
        processingLabel.getStyleClass().add("dash-empty-state");
        notesContainer.getChildren().add(processingLabel);

        Task<String> saveTask = new Task<>() {
            @Override
            protected String call() {
                Note saved = NoteService.persistOnly(text);
                return saved == null ? "" : saved.getContent();
            }
        };
        saveTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                notesContainer.getChildren().remove(processingLabel);
                loadNotes();
                analyzeNoteText(text, I18n.tr("notes.source.new"));
            });
        });
        saveTask.setOnFailed(e -> Platform.runLater(() -> {
            notesContainer.getChildren().remove(processingLabel);
            loadNotes();
        }));
        Thread t = new Thread(saveTask, "aether-note-save");
        t.setDaemon(true);
        t.start();
    }

    private void setAnalyzing(boolean analyzing, String status) {
        Platform.runLater(() -> {
            analyzeStatusLabel.setText(status);
            analyzeProgressRow.setVisible(analyzing);
            analyzeProgressRow.setManaged(analyzing);
            if (analyzing) {
                proposalsSection.setVisible(true);
                proposalsSection.setManaged(true);
            }
            if (activeAnalyzeButton != null) {
                activeAnalyzeButton.setDisable(analyzing);
            }
        });
    }

    /** Analisa uma nota existente (botão "Analyze with AI"). */
    private void analyzeNote(Note note, Button sourceBtn) {
        activeAnalyzeButton = sourceBtn;
        analyzeNoteText(note.getContent(), I18n.tr("notes.source.note"));
    }

    private void analyzeNoteText(String noteText, String sourceLabel) {
        String modelId = UserSession.getInstance().getAppSettings().getActiveModelId();
        if (modelId == null || modelId.isBlank()) {
            setAnalyzing(false, I18n.tr("notes.noModel"));
            Platform.runLater(() -> {
                showProposals(List.of());
                Label hint = new Label(I18n.tr("notes.noModel"));
                hint.getStyleClass().add("dash-empty-state");
                hint.setWrapText(true);
                proposalsCards.getChildren().add(hint);
            });
            return;
        }
        NoteAnalysisService service = new NoteAnalysisService(modelId, List::of);

        // Mostra feedback imediato: a análise corre em segundo plano no Ollama.
        analysisCancelled = false;
        setAnalyzing(true, I18n.tr("notes.analyzing"));

        Task<List<ResolvedAction>> analyzeTask = new Task<>() {
            @Override
            protected List<ResolvedAction> call() {
                return service.analyze(noteText, sourceLabel);
            }
        };
        analyzeTask.setOnSucceeded(e -> {
            if (analysisCancelled) {
                // O utilizador cancelou — não mostrar "sem propostas".
                setAnalyzing(false, I18n.tr("notes.analyze.cancelled"));
                return;
            }
            List<ResolvedAction> result = analyzeTask.getValue();
            int count = result == null ? 0 : result.size();
            setAnalyzing(false, I18n.tr(count > 0 ? "notes.analyze.found" : "notes.analyze.empty", count));
            showProposals(result);
        });
        analyzeTask.setOnFailed(e -> {
            LOGGER.warning("Análise de nota falhou: "
                    + (analyzeTask.getException() != null ? analyzeTask.getException().getMessage() : "unknown"));
            if (analysisCancelled) {
                setAnalyzing(false, I18n.tr("notes.analyze.cancelled"));
            } else {
                setAnalyzing(false, I18n.tr("notes.analyze.failed"));
            }
            showProposals(List.of());
        });
        Thread t = new Thread(analyzeTask, "aether-note-analyze");
        t.setDaemon(true);
        analyzeThread = t;
        t.start();
    }

    /** Cancela a análise em curso: interrompe o pedido ao Ollama e restaura a UI. */
    private void cancelAnalysis() {
        analysisCancelled = true;
        Thread t = analyzeThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
        setAnalyzing(false, I18n.tr("notes.analyze.cancelled"));
        Platform.runLater(() -> showProposals(List.of()));
    }

    // ------------------------------------------------------------------
    // ProposalStore (idempotência + persistência)
    // ------------------------------------------------------------------

    private static void markAcceptedInStore(ResolvedAction ra) {
        ai.ProposalStore.Snapshot snap = toSnapshot(ra, "ACCEPTED");
        ai.ProposalStore store = ai.ProposalStore.getInstance();
        store.propose(snap);
        store.markAccepted(snap.id);
    }

    private static void markRejectedInStore(ResolvedAction ra) {
        ai.ProposalStore.Snapshot snap = toSnapshot(ra, "REJECTED");
        ai.ProposalStore store = ai.ProposalStore.getInstance();
        store.propose(snap);
        store.markRejected(snap.id);
    }

    private static void markFailedInStore(ResolvedAction ra) {
        ai.ProposalStore.Snapshot snap = toSnapshot(ra, "FAILED");
        ai.ProposalStore store = ai.ProposalStore.getInstance();
        store.propose(snap);
        store.markFailed(snap.id);
    }

    private static ai.ProposalStore.Snapshot toSnapshot(ResolvedAction ra, String status) {
        AiActionProposal p = ra.proposal;
        String identifier = p.getFields().getOrDefault("name",
                p.getFields().getOrDefault("title", p.getFields().getOrDefault("content", "")));
        String id = ai.ProposalStore.idFor(p.getActionType().name(), p.getEntityType().name(),
                identifier, p.getFields());
        return new ai.ProposalStore.Snapshot(id, status, p.getActionType().name(),
                p.getEntityType().name(), identifier, p.getFields(), p.getRelationships(),
                p.getReason(), p.getTrustLevel().name(), p.getConfidence(),
                p.getSourceContext(), System.currentTimeMillis());
    }
}
