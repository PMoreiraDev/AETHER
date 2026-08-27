package controller;

import java.net.URL;
import java.util.ResourceBundle;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import util.Navigator;

/**
 * Controlador do ecrã de arranque do AETHER ({@code LauncherApp.fxml}).
 * <p>
 * Ajusta a imagem de fundo às dimensões da janela e navega para o ecrã de
 * perfil quando o utilizador clica em "Start AETHER".
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class LauncherController implements Initializable {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public LauncherController() {
        // Construtor por omissão explícito, documentado para o Javadoc.
    }

    /** Caminho do ecrã de perfil (passo 1) no classpath. */
    private static final String PROFILE_VIEW = "/FXML/profile.fxml";

    /** Painel raiz do ecrã, usado como referência de dimensões e de janela. */
    @FXML
    private StackPane rootPane;

    /** Imagem de fundo, redimensionada dinamicamente. */
    @FXML
    private ImageView backgroundImageView;

    /** Botão que inicia o fluxo de onboarding. */
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
     * Reage ao clique em "START AETHER" e avança para o ecrã de perfil,
     * mantendo a moldura Glassmorphism da janela atual.
     *
     * @param event o evento de ação gerado pelo botão
     */
    @FXML
    private void handleStartAether(ActionEvent event) {
        if (startButton != null) {
            startButton.setDisable(true);
        }

        boolean navigated = Navigator.navigate(rootPane, PROFILE_VIEW);

        if (!navigated && startButton != null) {
            startButton.setDisable(false);
        }
    }
}
