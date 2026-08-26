package controller;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import util.WindowUtils;

public class LauncherController implements Initializable {

    @FXML
    private StackPane rootPane;

    @FXML
    private ImageView backgroundImageView;

    @FXML
    private Button startButton;

    private double dragAnchorX;
    private double dragAnchorY;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Liga a largura e altura da imagem às dimensões exatas da janela
        if (backgroundImageView != null && rootPane != null) {
            backgroundImageView.fitWidthProperty().bind(rootPane.widthProperty());
            backgroundImageView.fitHeightProperty().bind(rootPane.heightProperty());
        }

        // Suporte para arrastar a janela
        rootPane.setOnMousePressed(this::handleDragPressed);
        rootPane.setOnMouseDragged(this::handleDragDragged);
    }

    private void handleDragPressed(MouseEvent event) {
        Stage stage = getStage();
        if (stage != null) {
            dragAnchorX = event.getScreenX() - stage.getX();
            dragAnchorY = event.getScreenY() - stage.getY();
        }
    }

    private void handleDragDragged(MouseEvent event) {
        Stage stage = getStage();
        if (stage != null) {
            stage.setX(event.getScreenX() - dragAnchorX);
            stage.setY(event.getScreenY() - dragAnchorY);
        }
    }

    /**
     * Reage ao clique no botão "START AETHER" e navega para a tela de perfil mantendo a barra de vidro.
     */
    @FXML
    private void handleStartAether(ActionEvent event) {
        try {
            URL profileResource = getClass().getResource("/profile.fxml");

            if (profileResource == null) {
                System.err.println("[AETHER Erro] Ficheiro '/profile.fxml' não foi encontrado na pasta de recursos.");
                return;
            }

            FXMLLoader loader = new FXMLLoader(profileResource);
            Parent profileRoot = loader.load();

            Stage stage = getStage();
            if (stage != null) {
                Scene currentScene = stage.getScene();

                // Se a janela já tiver a estrutura de vidro, substitui apenas o conteúdo interior (Center)
                if (currentScene != null && currentScene.getRoot() instanceof BorderPane) {
                    BorderPane glassContainer = (BorderPane) currentScene.getRoot();
                    glassContainer.setCenter(profileRoot);
                } else {
                    // Caso contrário, recria a janela envolta na barra Glassmorphic
                    stage.setScene(WindowUtils.makeGlassWindow(stage, profileRoot, "AETHER"));
                }
            }

        } catch (IOException e) {
            System.err.println("[AETHER Erro] Falha ao carregar a vista de perfil (profile.fxml): " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Obtém a referência da Stage atual de forma segura.
     */
    private Stage getStage() {
        if (rootPane != null && rootPane.getScene() != null) {
            return (Stage) rootPane.getScene().getWindow();
        }
        return null;
    }
}