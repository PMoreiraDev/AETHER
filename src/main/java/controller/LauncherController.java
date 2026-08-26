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
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Controlador da vista do ecrã de arranque do AETHER ({@code launcher.fxml}).
 *
 * @author BitMasters
 * @version 1.1
 */
public class LauncherController implements Initializable {

    @FXML
    private StackPane rootPane;

    @FXML
    private Button startButton;

    private double dragAnchorX;
    private double dragAnchorY;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        rootPane.setOnMousePressed(this::handleDragPressed);
        rootPane.setOnMouseDragged(this::handleDragDragged);
    }

    private void handleDragPressed(MouseEvent event) {
        dragAnchorX = event.getScreenX() - getStage().getX();
        dragAnchorY = event.getScreenY() - getStage().getY();
    }

    private void handleDragDragged(MouseEvent event) {
        getStage().setX(event.getScreenX() - dragAnchorX);
        getStage().setY(event.getScreenY() - dragAnchorY);
    }

    /**
     * Reage ao clique no botão "START AETHER" e navega para a tela de perfil.
     *
     * @param event evento de ação do botão
     */
    @FXML
    private void handleStartAether(ActionEvent event) {
        try {
            // Carrega o FXML do ecrã de perfil
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/profile.fxml"));
            Parent profileRoot = loader.load();

            // Obtém a cena atual e substitui o nó raiz (suaviza a transição de ecrã)
            Stage stage = getStage();
            Scene currentScene = stage.getScene();

            if (currentScene != null) {
                currentScene.setRoot(profileRoot);
            } else {
                stage.setScene(new Scene(profileRoot));
            }

        } catch (IOException e) {
            System.err.println("[AETHER] Erro ao carregar a vista de perfil (profile.fxml): " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Stage getStage() {
        return (Stage) rootPane.getScene().getWindow();
    }
}