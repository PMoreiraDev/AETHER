package controller;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.ArrayList;
import javafx.geometry.Insets;
import javafx.scene.control.TextField;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.shape.Circle;
import session.UserSession;
import util.AetherDialogs;
import util.AvatarImages;
import util.NoteService;
import util.OllamaService;
import util.StyleUtils;
import util.VaultRefreshBus;
import util.I18n;

/**
 * Controlador do shell do AETHER ({@code dashboard.fxml}).
 * <p>
 * O shell persiste entre vistas: o header e a sidebar esquerda permanecem
 * sempre visíveis. O conteúdo central ({@code contentArea}) e o painel direito
 * ({@code rightArea}) são trocados pelo controlador conforme a navegação.
 * </p>
 * <p>
 * O perfil do utilizador é carregado a partir de {@link UserSession}, que por
 * sua vez lê da base de dados SQLite local. Não existem dados hardcoded.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.3
 */
public class DashboardController implements Initializable {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public DashboardController() {
        // Construtor por omissão explícito.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(DashboardController.class.getName());

    /**
     * Instância ativa do shell do dashboard.
     * <p>
     * Permite que vistas carregadas dentro de {@code contentArea} (por exemplo
     * {@link DashboardViewController}, ao clicar no nó central do Context
     * Graph) peçam navegação para a Profile View sem duplicar a lógica de
     * troca de vistas já existente neste controlador.
     * </p>
     */
    private static DashboardController activeInstance;

    /**
     * Devolve a instância ativa do shell do dashboard, ou {@code null} se
     * ainda não tiver sido inicializada.
     *
     * @return a instância ativa, ou {@code null}
     */
    public static DashboardController getActive() {
        return activeInstance;
    }

    /** Caminho da vista do dashboard no classpath. */
    private static final String DASHBOARD_VIEW = "/FXML/dashboard_view.fxml";

    /** Caminho da vista do chat AETHER AI no classpath. */
    private static final String AETHER_AI_VIEW = "/FXML/aether_ai.fxml";
    private static final String SETTINGS_VIEW = "/FXML/settings.fxml";
    private static final String PROFILE_VIEW = "/FXML/profile_view.fxml";
    private static final String PEOPLE_VIEW = "/FXML/people_view.fxml";
    private static final String PROJECTS_VIEW = "/FXML/projects_view.fxml";
    private static final String EVENTS_VIEW = "/FXML/events_view.fxml";
    private static final String TASKS_VIEW = "/FXML/tasks_view.fxml";
    private static final String NOTES_VIEW = "/FXML/notes_view.fxml";
    private static final String NOTE_EDITOR_VIEW = "/FXML/note_editor.fxml";

    // ------------------------------------------------------------------
    // FXML — Shell
    // ------------------------------------------------------------------

    @FXML private StackPane rootPane;
    @FXML private ImageView backgroundImageView;
    @FXML private StackPane contentArea;
    @FXML private StackPane rightArea;
    @FXML private Label userInitialsLabel;
    @FXML private ImageView userAvatarImageView;
    @FXML private Label userNameLabel;
    @FXML private Label modelLabel;
    @FXML private Circle modelStatusDot;

    /** Botões de navegação da sidebar, para gestão do estado ativo. */
    @FXML private Button navDashboard;
    @FXML private Button navAetherAI;
    @FXML private Button navPeople;
    @FXML private Button navProjects;
    @FXML private Button navEvents;
    @FXML private Button navTasks;
    @FXML private Button navNotes;
    @FXML private Button navSettings;
    @FXML private TextField searchField;
    @FXML private VBox userProfileCard;
    @FXML private Label proposalBadgeLabel;

    /** Última vista carregada, para poder refrescá-la após criar uma nota. */
    private String lastViewPath;
    private Button lastNavButton;

    // ------------------------------------------------------------------
    // Inicialização
    // ------------------------------------------------------------------

    /**
     * Inicializa o shell: liga o fundo, carrega o perfil real do utilizador,
     * mostra o modelo Ollama ativo e carrega a vista do dashboard.
     *
     * @param location o URL do FXML carregado
     * @param resources o pacote de recursos de localização
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        activeInstance = this;
        bindBackgroundSize();
        loadUserProfile();
        loadModelInfo();
        registerKeyboardShortcuts();
        showDashboardView();
        refreshProposalBadge();
        VaultRefreshBus.subscribe(event -> Platform.runLater(this::refreshProposalBadge));
        // Sincronização inicial da pasta User/ do vault (spec #12): garante
        // que o vault reflete o perfil carregado do SQLite logo no arranque.
        // Thread daemon — não bloqueia o arranque da UI.
        Thread userVaultSync = new Thread(() -> {
            try {
                persistence.VaultManager.initializeVault();
                persistence.UserVaultSync.syncProfile(
                        session.UserSession.getInstance().getUserProfile(), "APP_START");
            } catch (RuntimeException ex) {
                LOGGER.warning("Sincronização inicial do vault do utilizador (best-effort): "
                        + (ex.getMessage() != null ? ex.getMessage() : "erro desconhecido"));
            }
        }, "aether-user-vault-sync");
        userVaultSync.setDaemon(true);
        userVaultSync.start();
    }

    /**
     * Atalhos de teclado globais do shell: Ctrl+N nova nota, Ctrl+S guardar
     * (reencaminhado para o editor ativo, se houver), Ctrl+K pesquisa, Esc
     * fecha diálogos/popups de pesquisa.
     */
    private void registerKeyboardShortcuts() {
        if (rootPane == null || rootPane.getScene() == null) {
            // A cena ainda não está pronta; tenta novamente quando a raiz for mostrada.
            Platform.runLater(this::registerKeyboardShortcuts);
            return;
        }
        javafx.scene.Scene scene = rootPane.getScene();
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.N,
                        javafx.scene.input.KeyCombination.CONTROL_DOWN),
                () -> handleAddNote());
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.K,
                        javafx.scene.input.KeyCombination.CONTROL_DOWN),
                () -> handleSearch());
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.S,
                        javafx.scene.input.KeyCombination.CONTROL_DOWN),
                () -> controller.NoteEditorController.saveActive());
    }

    // ------------------------------------------------------------------
    // Background
    // ------------------------------------------------------------------

    /**
     * Liga as dimensões da imagem de fundo às da janela.
     */
    private void bindBackgroundSize() {
        if (backgroundImageView != null && rootPane != null) {
            backgroundImageView.fitWidthProperty().bind(rootPane.widthProperty());
            backgroundImageView.fitHeightProperty().bind(rootPane.heightProperty());
        }
    }

    // ------------------------------------------------------------------
    // Perfil do utilizador
    // ------------------------------------------------------------------

    /**
     * Preenche o cartão de perfil com os dados reais do utilizador.
     * <p>
     * Todos os dados vêm de {@link UserSession#getUserProfile()} — nome e
     * ocupações. Não há valores hardcoded.
     * </p>
     */
    private void loadUserProfile() {
        UserSession session = UserSession.getInstance();
        var profile = session.getUserProfile();

        String fullName = profile.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = "AETHER User";
        }

        String displayName = profile.getPreferredName();
        if (displayName == null || displayName.isBlank()) {
            displayName = fullName;
        }

        userNameLabel.setText(displayName);
        userInitialsLabel.setText(extractInitials(fullName));

        boolean hasPhoto = AvatarImages.applyCenteredCircularImage(
                userAvatarImageView, profile.getProfilePhotoPath(), 56);
        if (hasPhoto) {
            userAvatarImageView.setClip(new Circle(28, 28, 28));
        } else {
            userAvatarImageView.setClip(null);
        }
        userInitialsLabel.setVisible(!hasPhoto);

        String finalName = displayName;
        LOGGER.info(() -> "Dashboard initialized for user: " + finalName);
    }

    /**
     * Volta a carregar o cartão de perfil da sidebar (nome, iniciais e foto).
     * <p>
     * Chamado pela Profile View depois de guardar alterações com sucesso,
     * para que a sidebar reflita de imediato o novo nome ou foto, sem ser
     * preciso reiniciar a aplicação.
     * </p>
     */
    public void refreshUserProfileCard() {
        loadUserProfile();
    }

    /**
     * Extrai as iniciais de um nome completo.
     *
     * @param fullName o nome completo do utilizador
     * @return as iniciais (1 a 2 caracteres), maiúsculas
     */
    private String extractInitials(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "AU";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    // ------------------------------------------------------------------
    // Modelo Ollama
    // ------------------------------------------------------------------

    /**
     * Mostra o modelo ativo do Ollama na barra inferior da sidebar.
     * <p>
     * O identificador do modelo vem de
     * {@link session.UserSession#getAppSettings()}.
     * {@link domain.AppSettings#getActiveModelId()}.
     * </p>
     */
    private void loadModelInfo() {
        UserSession session = UserSession.getInstance();
        String modelId = session.getAppSettings().getActiveModelId();

        if (modelId != null && !modelId.isBlank()) {
            modelLabel.setText(modelId);
            modelStatusDot.getStyleClass().setAll("indicador-ok");
            warmUpEngine();
        } else {
            modelLabel.setText(I18n.tr("shell.model.none"));
            modelStatusDot.getStyleClass().setAll("indicador-pendente");
        }
    }

    /**
     * Atualiza apenas o texto do modelo ativo, sem repetir o warm-up do
     * motor. Chamado a cada navegação para refletir de imediato uma troca de
     * modelo feita em Settings (evitando o texto antigo até reabrir a app).
     */
    private void refreshModelLabel() {
        String modelId = UserSession.getInstance().getAppSettings().getActiveModelId();
        modelLabel.setText(modelId != null && !modelId.isBlank() ? modelId : I18n.tr("shell.model.none"));
    }

    /**
     * Arranca o motor do Ollama em segundo plano assim que o shell abre.
     * <p>
     * Depois do setup, o utilizador não deve ter de fazer mais nada: em vez de
     * só arrancar o servidor quando o utilizador chega ao chat e escreve a
     * primeira mensagem, o shell já o faz assim que a sessão começa, para que
     * esteja pronto (ou o mais próximo disso possível) quando o AETHER AI for
     * aberto. O ponto (verde/vermelho) na sidebar passa a refletir o estado
     * real do motor, não apenas se um modelo está configurado.
     * </p>
     */
    private void warmUpEngine() {
        Task<Boolean> warmupTask = new Task<>() {
            @Override
            protected Boolean call() {
                return OllamaService.ensureServerRunning();
            }
        };

        warmupTask.setOnSucceeded(e -> {
            boolean ready = Boolean.TRUE.equals(warmupTask.getValue());
            modelStatusDot.getStyleClass().setAll(ready ? "indicador-sucesso" : "indicador-erro");
        });

        warmupTask.setOnFailed(e -> modelStatusDot.getStyleClass().setAll("indicador-erro"));

        Thread thread = new Thread(warmupTask, "aether-ollama-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------
    // Navegação
    // ------------------------------------------------------------------

    /**
     * Carrega a vista do dashboard no {@code contentArea}.
     */
    private void showDashboardView() {
        loadView(DASHBOARD_VIEW, navDashboard);
    }

    /**
     * Carrega a vista do chat AETHER AI no {@code contentArea}.
     */
    private void showAetherAIView() {
        loadView(AETHER_AI_VIEW, navAetherAI);
        rightArea.getChildren().clear();
    }

    /**
     * Carrega um FXML no contentArea e marca o botão de navegação como ativo.
     *
     * @param fxmlPath o caminho do FXML no classpath
     * @param navButton o botão da sidebar a marcar como ativo
     */
    private void loadView(String fxmlPath, Button navButton) {
        try {
            var fxmlUrl = DashboardController.class.getResource(fxmlPath);
            if (fxmlUrl == null) {
                LOGGER.severe(() -> "FXML não encontrado: " + fxmlPath);
                return;
            }
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(fxmlUrl, util.I18n.getBundle());
            var view = (javafx.scene.Node) loader.load();
            contentArea.getChildren().setAll(view);
            this.lastViewPath = fxmlPath;
            this.lastNavButton = navButton;
            setActiveNav(navButton);
            refreshModelLabel(); // reflete imediatamente trocas de modelo feitas em Settings
        } catch (Exception e) {
            LOGGER.severe(() -> "Erro ao carregar vista " + fxmlPath + ": " + e.getMessage());
        }
    }

    /**
     * Recarrega a vista atualmente visível, para refletir dados novos
     * (ex.: após guardar uma nota). Não bloqueia o fio da interface.
     */
    private void reloadCurrentView() {
        if (lastViewPath != null) {
            loadView(lastViewPath, lastNavButton);
        }
    }

    /**
     * Marca o botão de navegação ativo e limpa os restantes.
     *
     * @param active o botão a marcar como ativo
     */
    private void setActiveNav(Button active) {
        String activeClass = "dash-nav-item-active";
        var buttons = java.util.List.of(navDashboard, navAetherAI,
                navPeople, navProjects, navEvents, navTasks, navNotes, navSettings);

        for (Button btn : buttons) {
            if (btn == null) continue;
            btn.getStyleClass().remove(activeClass);
        }
        if (active != null && !active.getStyleClass().contains(activeClass)) {
            active.getStyleClass().add(activeClass);
        }
    }

    /**
     * Mostra uma vista de "em breve" para secções ainda não implementadas.
     *
     * @param name o nome da secção
     */
    private void showComingSoon(String name) {
        var placeholder = new javafx.scene.layout.VBox();
        placeholder.setAlignment(javafx.geometry.Pos.CENTER);
        placeholder.getStyleClass().add("dash-coming-soon");

        var icon = new Label("🚧");
        icon.getStyleClass().add("dash-coming-soon-icon");

        var title = new Label(name + " is coming soon");
        title.getStyleClass().add("dash-coming-soon-title");

        var hint = new Label(I18n.tr("dashboard.section.comingSoon"));
        hint.getStyleClass().add("dash-coming-soon-hint");

        placeholder.getChildren().addAll(icon, title, hint);
        placeholder.setSpacing(12);

        contentArea.getChildren().setAll(placeholder);
        rightArea.getChildren().clear();
    }

    // ------------------------------------------------------------------
    // Handlers — navegação
    // ------------------------------------------------------------------

    /**
     * Handler do botão Dashboard na sidebar.
     */
    @FXML private void handleNavDashboard() { showDashboardView(); }

    /**
     * Handler do botão AETHER AI na sidebar.
     */
    @FXML private void handleNavAetherAI() { showAetherAIView(); }

    /**
     * Handler do botão People na sidebar.
     */
    @FXML private void handleNavPeople() {
        loadView(PEOPLE_VIEW, navPeople);
        rightArea.getChildren().clear();
    }

    /**
     * Handler do botão Projects na sidebar.
     */
    @FXML private void handleNavProjects() {
        loadView(PROJECTS_VIEW, navProjects);
        rightArea.getChildren().clear();
    }

    /**
     * Handler do botão Events na sidebar.
     */
    /** Navigates to the existing Events view from child controllers. */
    public void showEvents() { loadView(EVENTS_VIEW, navEvents); }
    /** Navigates to the existing Tasks view from child controllers. */
    public void showTasks() { loadView(TASKS_VIEW, navTasks); }

    @FXML private void handleNavEvents() {
        loadView(EVENTS_VIEW, navEvents);
        rightArea.getChildren().clear();
    }

    /**
     * Handler do botão Tasks na sidebar.
     */
    @FXML private void handleNavTasks() {
        loadView(TASKS_VIEW, navTasks);
        rightArea.getChildren().clear();
    }

    /**
     * Handler do botão Notes na sidebar.
     */
    @FXML private void handleNavNotes() {
        showNotesView();
    }

    /** Carrega a vista de Notas no contentArea (público para o editor voltar). */
    public void showNotesView() {
        loadView(NOTES_VIEW, navNotes);
        rightArea.getChildren().clear();
    }

    /**
     * Handler do botão Settings na sidebar.
     */
    @FXML private void handleNavSettings() {
        loadView(SETTINGS_VIEW, navSettings);
        rightArea.getChildren().clear();
    }

    /**
     * Handler do clique no cartão do utilizador na sidebar.
     * <p>
     * O cartão inteiro (avatar, nome, badges e estado) é clicável e navega
     * para a Profile View, substituindo o antigo item "Profile" da sidebar.
     * </p>
     */
    @FXML private void handleUserProfileCardClick() {
        showProfileView();
    }

    /**
     * Mostra a Profile View no {@code contentArea}.
     * <p>
     * Ponto de navegação único para a Profile View, usado tanto pelo cartão
     * do utilizador na sidebar como por vistas filhas (por exemplo o nó
     * central do Context Graph no Dashboard) através de {@link #getActive()}.
     * Como já não existe um item "Profile" na sidebar, nenhum botão de
     * navegação fica marcado como ativo.
     * </p>
     */
    public void showProfileView() {
        loadView(PROFILE_VIEW, null);
        rightArea.getChildren().clear();
    }

    // ------------------------------------------------------------------
    // Handlers — header
    // ------------------------------------------------------------------

    /**
     * Handler do botão "+ Add note" no header.
     * <p>
     * Abre o modal de nota partilhado, persiste a nota (sem extração automática)
     * e refresca a vista atual. A análise da nota — extração de entidades,
     * relações, etc. — é uma <i>proposta</i> que o utilizador aprova na vista de
     * Notas ("Analisar com IA"). A IA nunca escreve no vault como consequência
     * direta da análise de uma nota.
     * </p>
     */
    @FXML private void handleAddNote() {
        openNoteEditor(new domain.entities.Note());
    }

    /**
     * Abre o editor de notas dedicado no {@code contentArea} para criar ou
     * editar uma nota. Substitui o modal antigo por uma experiência de escrita
     * real: título, toolbar de markdown, pré-visualização, autosave e feedback
     * assíncrono da IA após guardar.
     *
     * @param note a nota a editar (nova se sem id)
     */
    public void openNoteEditor(domain.entities.Note note) {
        util.NoteEditorContext.setPending(note);
        loadView(NOTE_EDITOR_VIEW, navNotes);
    }

    /**
     * Handler do botão de pesquisa no header.
     */
    @FXML private void handleSearch() {
        String q = searchField == null ? "" : searchField.getText().trim();
        if (q.isBlank()) {
            LOGGER.info("Action: Search (vazio)");
            return;
        }
        LOGGER.info("Action: Search: " + q);
        showSearchLoading(q);
    }

    /** Shows an immediate loading state and performs the global search off the FX thread. */
    private void showSearchLoading(String query) {
        Stage popup = new Stage();
        popup.setTitle(I18n.tr("search.title", query));
        popup.initModality(javafx.stage.Modality.NONE);
        popup.initOwner(searchField.getScene().getWindow());

        VBox root = new VBox(10);
        root.setPadding(new Insets(14));
        root.getStyleClass().add("search-popup");
        root.setPrefWidth(520);
        Label title = new Label(I18n.tr("search.loading"));
        title.getStyleClass().add("search-popup-title");
        root.getChildren().add(title);
        popup.setScene(new Scene(root));
        popup.getScene().getStylesheets().add(getClass().getResource("/Style.css").toExternalForm());
        popup.show();

        Task<util.GlobalSearchService.SearchResult> searchTask = new Task<>() {
            @Override protected util.GlobalSearchService.SearchResult call() {
                return util.GlobalSearchService.search(query);
            }
        };
        searchTask.setOnSucceeded(e -> {
            if (popup.isShowing()) {
                popup.close();
                showSearchResults(query, searchTask.getValue());
            }
        });
        searchTask.setOnFailed(e -> {
            if (popup.isShowing()) popup.close();
            AetherDialogs.info(I18n.tr("search.error"));
        });
        Thread thread = new Thread(searchTask, "aether-global-search");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Mostra os resultados da pesquisa numa janela popup sobreposta. O clique
     * num resultado abre a secção correspondente (Pessoas, Projetos, etc.).
     * A pesquisa cobre todo o vault via o índice normalizado
     * (insensível a diacríticos e capitalização).
     */
    private void showSearchResults(String query, util.GlobalSearchService.SearchResult result) {
        Stage popup = new Stage();
        popup.setTitle(I18n.tr("search.title", query));
        popup.initModality(javafx.stage.Modality.NONE);
        popup.initOwner(searchField.getScene().getWindow());

        VBox root = new VBox(8);
        root.setPadding(new Insets(14));
        root.getStyleClass().add("search-popup");
        root.setPrefWidth(520);

        Label title = new Label(result.isEmpty()
                ? I18n.tr("search.noResults")
                : I18n.tr("search.results", result.total()));
        title.getStyleClass().add("search-popup-title");
        root.getChildren().add(title);

        // Grouped results keep the existing navigation while making the search state
        // explicit for People, Projects, Tasks, Events, Notes and Documents.
        VBox resultsBox = new VBox(8);
        addSearchGroup(resultsBox, I18n.tr("search.people"), result.people().stream().map(p -> "👤  " + p.getName()).toList(), PEOPLE_VIEW, popup);
        addSearchGroup(resultsBox, I18n.tr("search.projects"), result.projects().stream().map(p -> "▣  " + p.getName()).toList(), PROJECTS_VIEW, popup);
        addSearchGroup(resultsBox, I18n.tr("search.tasks"), result.tasks().stream().map(t -> "✓  " + t.getTitle()).toList(), TASKS_VIEW, popup);
        addSearchGroup(resultsBox, I18n.tr("search.events"), result.events().stream().map(e -> "📅  " + e.getTitle()).toList(), EVENTS_VIEW, popup);
        addSearchGroup(resultsBox, I18n.tr("search.notes"), result.notes().stream().map(n -> "📝  " + n.getContent().lines().findFirst().orElse(I18n.tr("notes.untitled"))).toList(), NOTES_VIEW, popup);
        addSearchGroup(resultsBox, I18n.tr("search.documents"), result.documents().stream().map(d -> "📄  " + d.getOriginalFilename()).toList(), PROJECTS_VIEW, popup);
        javafx.scene.control.ScrollPane resultsScroll = new javafx.scene.control.ScrollPane(resultsBox);
        resultsScroll.setFitToWidth(true);
        resultsScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        resultsScroll.setPrefHeight(360);
        resultsScroll.getStyleClass().add("search-results-scroll");
        root.getChildren().add(resultsScroll);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/Style.css").toExternalForm());
        popup.setScene(scene);
        popup.show();
    }

    private void addSearchGroup(VBox parent, String title, List<String> labels, String viewPath, Stage popup) {
        if (labels == null || labels.isEmpty()) return;
        Label heading = new Label(title);
        heading.getStyleClass().add("search-group-title");
        parent.getChildren().add(heading);
        for (String text : labels) {
            Button item = new Button(text);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            item.getStyleClass().add("search-result-cell");
            item.setOnAction(ev -> { loadView(viewPath, null); popup.close(); });
            parent.getChildren().add(item);
        }
    }

    /** Item de resultado de pesquisa (texto de apresentação + vista de destino). */
    private record ResultItem(String display, String viewPath) {}

    /** Updates the notification badge from real persisted pending AI proposals. */
    public void refreshProposalBadge() {
        if (proposalBadgeLabel == null) return;
        int count = ai.ProposalStore.getInstance().pendingCount();
        proposalBadgeLabel.setText(count > 99 ? "99+" : String.valueOf(count));
        proposalBadgeLabel.setVisible(count > 0);
        proposalBadgeLabel.setManaged(count > 0);
    }

    /**
     * Handler do botão de notificações no header. Abre um painel com as
     * notificações pendentes (Suggested Updates de perfil detetadas pela IA
     * nas conversas). Cada item tem um botão "Rever" que leva ao Profile.
     * Reutiliza o ProposalStore existente — não cria um segundo sistema.
     */
    @FXML private void handleNotifications() {
        LOGGER.info("Action: Notifications");
        List<ai.ProposalStore.Snapshot> pending = ai.ProposalStore.getInstance().pending();
        if (pending == null || pending.isEmpty()) {
            AetherDialogs.info(I18n.tr("notifications.none"));
            refreshProposalBadge();
            return;
        }
        showNotificationsPanel(pending);
        refreshProposalBadge();
    }

    /** Painel de notificações (dark glass) com a lista de Suggested Updates. */
    private void showNotificationsPanel(List<ai.ProposalStore.Snapshot> pending) {
        javafx.stage.Stage stage = new Stage();
        stage.setTitle(I18n.tr("notifications.title"));
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);

        VBox root = new VBox(10);
        root.getStyleClass().add("notif-panel");
        root.setPadding(new Insets(16));

        Label header = new Label(I18n.tr("notifications.title"));
        header.getStyleClass().add("notif-panel-header");
        root.getChildren().add(header);

        // Lista com scroll — suporta muitas notificações sem truncar.
        VBox list = new VBox(10);
        for (ai.ProposalStore.Snapshot s : pending) {
            VBox card = new VBox(4);
            card.getStyleClass().add("notif-card");
            Label title = new Label(I18n.tr("notifications.userContext"));
            title.getStyleClass().add("notif-card-title");
            Label desc = new Label(I18n.tr("notifications.detectedInfo"));
            desc.getStyleClass().add("notif-card-desc");
            desc.setWrapText(true);
            // Resumo dos campos sugeridos com labels legíveis (não chaves técnicas).
            StringBuilder fields = new StringBuilder();
            if (s.fields != null) {
                s.fields.forEach((k, v) -> fields.append(util.ProfileFieldLabels.label(k))
                        .append(" → ").append(v).append("\n"));
            }
            Label detail = new Label(fields.toString().trim());
            detail.getStyleClass().add("notif-card-detail");
            detail.setWrapText(true);

            Button review = new Button(I18n.tr("notifications.review"));
            review.getStyleClass().addAll("proposal-btn", "proposal-accept-btn");
            review.setOnAction(e -> {
                stage.close();
                loadView(PROFILE_VIEW, null);
            });
            card.getChildren().addAll(title, desc, detail, review);
            list.getChildren().add(card);
        }

        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(list);
        scroll.getStyleClass().add("notif-scroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportHeight(400);
        root.getChildren().add(scroll);

        Scene scene = new Scene(root, 460, 520);
        scene.getStylesheets().add(getClass().getResource("/Style.css").toExternalForm());
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> refreshProposalBadge());
        stage.show();
    }
}
