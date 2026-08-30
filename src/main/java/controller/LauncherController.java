package controller;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import session.UserSession;
import util.Navigator;

/**
 * Controlador do ecrã de arranque do AETHER ({@code LauncherApp.fxml}).
 * <p>
 * Ajusta a imagem de fundo às dimensões da janela e, ao clicar em
 * "START AETHER", decide o destino:
 * </p>
 * <ul>
 *   <li>Se o onboarding já estiver concluído, abre o dashboard.</li>
 *   <li>Caso contrário, abre o passo 1 do setup (perfil).</li>
 * </ul>
 *
 * @author Paulo Moreira
 * @version 1.1
 */
public class LauncherController implements Initializable {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public LauncherController() {
        // Construtor por omissão explícito, documentado para o Javadoc.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(LauncherController.class.getName());

    /** Caminho do ecrã de perfil (passo 1 do setup) no classpath. */
    private static final String PROFILE_VIEW = "/FXML/profile.fxml";

    /** Caminho do dashboard no classpath. */
    private static final String DASHBOARD_VIEW = "/FXML/dashboard.fxml";

    /** Painel raiz do ecrã, usado como referência de dimensões e de janela. */
    @FXML
    private StackPane rootPane;

    /** Imagem de fundo, redimensionada dinamicamente. */
    @FXML
    private ImageView backgroundImageView;

    /** Botão que inicia o fluxo do AETHER. */
    @FXML
    private Button startButton;

    /**
     * Liga as dimensões da imagem de fundo às da janela, garantindo que o fundo
     * cobre todo o ecrã em qualquer redimensionamento.
     *
     * @param location o URL do FXML carregado, ou {@code null} se não conhecido
     * @param resources o pacote de recursos de localização, ou {@code null} se não usado
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (backgroundImageView != null && rootPane != null) {
            backgroundImageView.fitWidthProperty().bind(rootPane.widthProperty());
            backgroundImageView.fitHeightProperty().bind(rootPane.heightProperty());
        }
    }

    /**
     * Reage ao clique em "START AETHER".
     * <p>
     * Se o onboarding já estiver concluído, navega para o dashboard. Caso
     * contrário, inicia o setup no passo 1 (perfil).
     * </p>
     *
     * @param event o evento de ação gerado pelo botão
     */
    @FXML
    private void handleStartAether(ActionEvent event) {
        if (startButton != null) {
            startButton.setDisable(true);
        }

        boolean developmentReset = Boolean.parseBoolean(
                System.getProperty("aether.dev.reset", "false")
        );

        UserSession session = UserSession.getInstance();

        // The reset flag is authoritative for this launch. This prevents an
        // already cached/persisted onboarding=true value from bypassing the
        // setup when the developer explicitly requested a reset. The profile
        // itself is intentionally preserved, so the user can complete setup
        // again and then return to the dashboard with the same profile data.
        boolean onboardingDone = !developmentReset
                && session.getAppSettings().isOnboardingCompleted();

        String target = onboardingDone ? DASHBOARD_VIEW : PROFILE_VIEW;

        LOGGER.info(() -> "Start clicado. dev.reset=" + developmentReset
                + ", onboarding concluído=" + onboardingDone
                + " → a navegar para " + target);

        boolean navigated = Navigator.navigate(rootPane, target);

        if (!navigated && startButton != null) {
            startButton.setDisable(false);
        }
    }
}
