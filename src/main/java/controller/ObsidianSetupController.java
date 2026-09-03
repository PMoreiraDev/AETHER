package controller;

import java.awt.Desktop;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import persistence.VaultManager;
import session.UserSession;
import util.Navigator;

/**
 * Controlador do passo 3 do setup — configuração do Obsidian
 * ({@code obsidian_setup.fxml}).
 * <p>
 * Deteta se o Obsidian está instalado, permite descarregar, cria o vault
 * numa pasta conhecida e guarda o caminho nas definições da aplicação.
 * </p>
 * <p>
 * Este passo é opcional: o utilizador pode saltar se já tiver Ollama
 * configurado ou se não quiser usar o Obsidian. O AETHER funciona sem
 * Obsidian — apenas não tem a visualização do grafo.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.1
 */
public class ObsidianSetupController implements Initializable {

    public ObsidianSetupController() {
        // Construtor por omissão.
    }

    private static final Logger LOGGER = Logger.getLogger(ObsidianSetupController.class.getName());

    /** URL para download do Obsidian. */
    private static final String OBSIDIAN_DOWNLOAD_URL = "https://obsidian.md/download";

    /** Caminho do dashboard no classpath. */
    private static final String DASHBOARD_VIEW = "/FXML/dashboard.fxml";

    @FXML private Button downloadButton;
    @FXML private Button createVaultButton;
    @FXML private Button finishButton;
    @FXML private Label statusLabel;
    @FXML private Label vaultPathLabel;
    @FXML private Circle statusDot;
    @FXML private StackPane rootPane;
    @FXML private ImageView backgroundImageView;

    /** Indica se o vault foi criado com sucesso. */
    private boolean vaultCreated = false;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Tal como nos passos 1 e 2, o fundo é pintado duas vezes: uma vez
        // pelo CSS de ".root-pane" (-fx-background-image) e outra vez por
        // este ImageView, colocado por cima para permitir efeitos futuros
        // (ex. desfoque). Sem esta ligação ao tamanho do ecrã, o ImageView
        // fica com o tamanho nativo da imagem em vez de cobrir o ecrã todo,
        // o que faz aparecer duas camadas de fundo desalinhadas e sobrepostas.
        // Ligar fitWidth/fitHeight ao rootPane garante que o ImageView cobre
        // exatamente a mesma área do fundo do CSS, tal como nos outros passos.
        if (backgroundImageView != null && rootPane != null) {
            backgroundImageView.fitWidthProperty().bind(rootPane.widthProperty());
            backgroundImageView.fitHeightProperty().bind(rootPane.heightProperty());
        }

        updateStatus("Checking Obsidian installation...", "pending");
        checkObsidianInstallation();
        // Cria o vault automaticamente e mostra logo o caminho, para o
        // utilizador saber onde o AETHER guarda os dados — mesmo que não
        // clique em "Create Vault". A criação é idempotente.
        ensureVaultAndShowPath();
    }

    /**
     * Verifica se o Obsidian está instalado no sistema.
     */
    private void checkObsidianInstallation() {
        Task<Boolean> checkTask = new Task<>() {
            @Override
            protected Boolean call() {
                return isObsidianInstalled();
            }
        };

        checkTask.setOnSucceeded(e -> {
            boolean installed = checkTask.getValue();
            if (installed) {
                updateStatus("Obsidian detected on your system.", "success");
                createVaultButton.setDisable(false);
            } else {
                updateStatus("Obsidian not found. Download it to enable the graph view.", "pending");
                downloadButton.setVisible(true);
                createVaultButton.setDisable(false); // Still allow vault creation
            }
        });

        checkTask.setOnFailed(e -> {
            updateStatus("Could not check Obsidian installation.", "error");
            createVaultButton.setDisable(false);
        });

        Thread thread = new Thread(checkTask, "aether-obsidian-check");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Verifica se o Obsidian está instalado procurando o executável.
     *
     * @return true se encontrado
     */
    private boolean isObsidianInstalled() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                Path path1 = Path.of(localAppData, "Programs", "Obsidian", "Obsidian.exe");
                Path path2 = Path.of(localAppData, "Obsidian", "Obsidian.exe");
                if (java.nio.file.Files.exists(path1) || java.nio.file.Files.exists(path2)) {
                    return true;
                }
            }
            return java.nio.file.Files.exists(Path.of("C:\\Program Files\\Obsidian\\Obsidian.exe"));
        }

        if (os.contains("mac")) {
            return java.nio.file.Files.exists(Path.of("/Applications/Obsidian.app"));
        }

        // Linux
        String home = System.getProperty("user.home", "");
        String[] candidates = {
                "/usr/bin/obsidian",
                "/usr/local/bin/obsidian",
                "/opt/Obsidian/obsidian",
                home + "/.local/share/Obsidian/obsidian",
                home + "/snap/obsidian/current/Obsidian",
                home + "/.local/bin/obsidian"
        };
        for (String candidate : candidates) {
            if (java.nio.file.Files.exists(Path.of(candidate))) {
                return true;
            }
        }
        // Verificar flatpak
        try {
            Process p = new ProcessBuilder("flatpak", "info", "md.obsidian.Obsidian")
                    .redirectErrorStream(true).start();
            if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {
            // Flatpak não disponível.
        }
        return false;
    }

    /**
     * Handler do botão de download do Obsidian.
     */
    @FXML
    private void handleDownloadObsidian() {
        try {
            Desktop.getDesktop().browse(URI.create(OBSIDIAN_DOWNLOAD_URL));
        } catch (Exception e) {
            LOGGER.warning("Não foi possível abrir o browser: " + e.getMessage());
        }
    }

    /**
     * Cria o vault (idempotente) e mostra o caminho no ecrã.
     * <p>
     * Corre em segundo plano para não bloquear a interface.
     * </p>
     */
    private void ensureVaultAndShowPath() {
        Task<Boolean> createTask = new Task<>() {
            @Override
            protected Boolean call() {
                return VaultManager.initializeVault();
            }
        };

        createTask.setOnSucceeded(e -> {
            if (Boolean.TRUE.equals(createTask.getValue())) {
                vaultCreated = true;
                showVaultPath();
                if (finishButton != null) {
                    finishButton.setDisable(false);
                }
            }
        });

        Thread thread = new Thread(createTask, "aether-vault-init");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Mostra o caminho do vault no label próprio.
     * <p>
     * Importante: além de tornar o label visível, também o coloca como
     * {@code managed = true}, caso contrário o JavaFX não lhe reserva espaço
     * no layout e o caminho não aparece — este era o bug que fazia o caminho
     * do vault nunca ser visível.
     * </p>
     */
    private void showVaultPath() {
        Path vaultPath = VaultManager.getVaultPath();
        vaultPathLabel.setText("Vault created at: " + vaultPath.toString());
        vaultPathLabel.setVisible(true);
        vaultPathLabel.setManaged(true);
    }

    /**
     * Handler do botão de criação do vault.
     */
    @FXML
    private void handleCreateVault() {
        createVaultButton.setDisable(true);
        updateStatus("Creating AETHER vault...", "pending");

        Task<Boolean> createTask = new Task<>() {
            @Override
            protected Boolean call() {
                return VaultManager.initializeVault();
            }
        };

        createTask.setOnSucceeded(e -> {
            boolean success = createTask.getValue();
            if (success) {
                vaultCreated = true;
                showVaultPath();
                updateStatus("AETHER vault created successfully!", "success");
                finishButton.setDisable(false);
            } else {
                updateStatus("Failed to create vault. Check disk permissions.", "error");
                createVaultButton.setDisable(false);
            }
        });

        createTask.setOnFailed(e -> {
            updateStatus("Failed to create vault.", "error");
            createVaultButton.setDisable(false);
        });

        Thread thread = new Thread(createTask, "aether-vault-create");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Handler do botão de saltar.
     */
    @FXML
    private void handleSkip() {
        finishSetup();
    }

    /**
     * Handler do botão de terminar.
     */
    @FXML
    private void handleFinish() {
        finishSetup();
    }

    /**
     * Termina o setup e vai para o dashboard.
     * <p>
     * Garante que o vault existe (caso o utilizador tenha saltado a criação)
     * e navega para o dashboard usando o nó raiz do ecrã — anteriormente usava
     * {@code skipButton}, que nunca era injetado pelo FXML (o botão Skip não
     * tem {@code fx:id}), pelo que a navegação falhava silenciosamente e o
     * dashboard nunca abria. Usar {@code rootPane} (sempre presente) é o mesmo
     * padrão dos passos 1 e 2.
     * </p>
     */
    private void finishSetup() {
        // Garante o vault antes de abrir o dashboard (idempotente).
        if (!vaultCreated) {
            VaultManager.initializeVault();
        }

        UserSession session = UserSession.getInstance();
        session.getAppSettings().setOnboardingCompleted(true);
        session.saveAppSettings();

        if (finishButton != null) {
            finishButton.setDisable(true);
        }

        Navigator.navigate(rootPane, DASHBOARD_VIEW);
    }

    /**
     * Atualiza o estado visual do indicador e da mensagem.
     */
    private void updateStatus(String message, String state) {
        statusLabel.setText(message);
        if (statusDot != null) {
            statusDot.getStyleClass().setAll(
                    "success".equals(state) ? "indicador-sucesso" :
                    "error".equals(state) ? "indicador-erro" :
                    "indicador-pendente"
            );
        }
    }
}
