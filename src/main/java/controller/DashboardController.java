package controller;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import session.UserSession;
import util.OllamaService;
import util.StyleUtils;

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

    /** Caminho da vista do dashboard no classpath. */
    private static final String DASHBOARD_VIEW = "/FXML/dashboard_view.fxml";

    /** Caminho da vista do chat AETHER AI no classpath. */
    private static final String AETHER_AI_VIEW = "/FXML/aether_ai.fxml";

    // ------------------------------------------------------------------
    // FXML — Shell
    // ------------------------------------------------------------------

    @FXML private StackPane rootPane;
    @FXML private ImageView backgroundImageView;
    @FXML private StackPane contentArea;
    @FXML private StackPane rightArea;
    @FXML private Label userInitialsLabel;
    @FXML private Label userNameLabel;
    @FXML private HBox userBadgesBox;
    @FXML private Label modelLabel;
    @FXML private Circle modelStatusDot;

    /** Botões de navegação da sidebar, para gestão do estado ativo. */
    @FXML private Button navDashboard;
    @FXML private Button navAetherAI;
    @FXML private Button navCalendar;
    @FXML private Button navPeople;
    @FXML private Button navEvents;
    @FXML private Button navTasks;
    @FXML private Button navNotes;
    @FXML private Button navSettings;

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
        bindBackgroundSize();
        loadUserProfile();
        loadModelInfo();
        showDashboardView();
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

        userBadgesBox.getChildren().clear();
        var occupations = profile.getOccupations();
        if (occupations != null) {
            for (String occ : occupations) {
                if (occ != null && !occ.isBlank()) {
                    Label badge = new Label(occ);
                    badge.getStyleClass().add("dash-badge-pill");
                    userBadgesBox.getChildren().add(badge);
                }
            }
        }

        String finalName = displayName;
        LOGGER.info(() -> "Dashboard initialized for user: " + finalName);
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
            modelLabel.setText("Model: " + modelId);
            modelStatusDot.getStyleClass().setAll("indicador-pendente");
            warmUpEngine();
        } else {
            modelLabel.setText("No model");
            modelStatusDot.getStyleClass().setAll("indicador-pendente");
        }
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
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(fxmlUrl);
            var view = (javafx.scene.Node) loader.load();
            contentArea.getChildren().setAll(view);
            setActiveNav(navButton);
        } catch (Exception e) {
            LOGGER.severe(() -> "Erro ao carregar vista " + fxmlPath + ": " + e.getMessage());
        }
    }

    /**
     * Marca o botão de navegação ativo e limpa os restantes.
     *
     * @param active o botão a marcar como ativo
     */
    private void setActiveNav(Button active) {
        String activeClass = "dash-nav-item-active";
        var buttons = java.util.List.of(navDashboard, navAetherAI, navCalendar,
                navPeople, navEvents, navTasks, navNotes, navSettings);

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
        placeholder.setStyle("-fx-background-color: transparent;");

        var icon = new Label("🚧");
        icon.setStyle("-fx-font-size: 48px;");

        var title = new Label(name + " is coming soon");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #e2e8f0;");

        var hint = new Label("This section is still being built. The dashboard is your base.");
        hint.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");

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
     * Handler do botão Calendar na sidebar.
     */
    @FXML private void handleNavCalendar() {
        showComingSoon("Calendar");
        setActiveNav(navCalendar);
    }

    /**
     * Handler do botão People na sidebar.
     */
    @FXML private void handleNavPeople() {
        showComingSoon("People");
        setActiveNav(navPeople);
    }

    /**
     * Handler do botão Events na sidebar.
     */
    @FXML private void handleNavEvents() {
        showComingSoon("Events");
        setActiveNav(navEvents);
    }

    /**
     * Handler do botão Tasks na sidebar.
     */
    @FXML private void handleNavTasks() {
        showComingSoon("Tasks");
        setActiveNav(navTasks);
    }

    /**
     * Handler do botão Notes na sidebar.
     */
    @FXML private void handleNavNotes() {
        showComingSoon("Notes");
        setActiveNav(navNotes);
    }

    /**
     * Handler do botão Settings na sidebar.
     */
    @FXML private void handleNavSettings() {
        showComingSoon("Settings");
        setActiveNav(navSettings);
    }

    // ------------------------------------------------------------------
    // Handlers — header
    // ------------------------------------------------------------------

    /**
     * Handler do botão "+ Add note" no header.
     */
    @FXML private void handleAddNote() {
        LOGGER.info("Action: Add note");
    }

    /**
     * Handler do botão de pesquisa no header.
     */
    @FXML private void handleSearch() {
        LOGGER.info("Action: Search");
    }

    /**
     * Handler do botão de notificações no header.
     */
    @FXML private void handleNotifications() {
        LOGGER.info("Action: Notifications");
    }
}
