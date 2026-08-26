package controller;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

public class ProfileController {

    @FXML private StackPane rootPane;
    @FXML private ImageView backgroundImageView;
    @FXML private TextField fullNameInput;
    @FXML private TextField preferredNameInput;
    @FXML private TextField birthdayInput;
    @FXML private TextArea aboutYouInput;

    @FXML
    public void initialize() {
        // Liga o tamanho da imagem de fundo à janela
        backgroundImageView.fitWidthProperty().bind(rootPane.widthProperty());
        backgroundImageView.fitHeightProperty().bind(rootPane.heightProperty());
    }

    @FXML
    private void handleNext() {
        String name = preferredNameInput.getText().isEmpty() ? fullNameInput.getText() : preferredNameInput.getText();
        System.out.println("Perfil Guardado: " + name);
        // Lógica para avançar para o Passo 3 (ou Workspace principal)
    }

    @FXML
    private void handleSkip() {
        System.out.println("Passo ignorado pelo utilizador.");
        // Lógica para avançar sem recolher perfil
    }
}