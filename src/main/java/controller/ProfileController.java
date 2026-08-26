package controller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.DialogPane;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import domain.Occupation;
import domain.UserProfile;
import session.UserSession;

/**
 * Controlador do Passo 1 do onboarding — recolha de perfil ({@code profile.fxml}).
 */
public class ProfileController {

    private static final DateTimeFormatter BIRTHDAY_FORMAT = DateTimeFormatter.ofPattern("d/M/yyyy");

    @FXML private StackPane rootPane;
    @FXML private ImageView backgroundImageView;
    @FXML private TextField fullNameInput;
    @FXML private TextField preferredNameInput;
    @FXML private TextField birthdayInput;
    @FXML private TextArea aboutYouInput;

    @FXML private FlowPane occupationTagsBox;
    private Button addTagButton;

    /** Guarda a referência do único botão de ocupação atualmente selecionado. */
    private Button selectedTagButton = null;

    @FXML
    public void initialize() {
        backgroundImageView.fitWidthProperty().bind(rootPane.widthProperty());
        backgroundImageView.fitHeightProperty().bind(rootPane.heightProperty());

        setupOccupationTags();
        prefillFromSession();
    }

    /**
     * Gera dinamicamente as tags predefinidas com base no Enum Occupation
     * e adiciona o botão "+" no final.
     */
    private void setupOccupationTags() {
        occupationTagsBox.getChildren().clear();

        for (Occupation occ : Occupation.values()) {
            // Ignora o OTHER na listagem inicial pois o botão "+" trata de opções personalizadas
            if (occ == Occupation.OTHER) {
                continue;
            }

            Button tag = new Button(occ.getDisplayName());
            tag.getStyleClass().add("pill-tag");
            tag.setOnAction(this::handleTagToggle);
            occupationTagsBox.getChildren().add(tag);
        }

        // Botão para adicionar ocupação customizada
        addTagButton = new Button("+");
        addTagButton.getStyleClass().add("pill-tag-adicionar");
        addTagButton.setOnAction(e -> handleAddTag());
        occupationTagsBox.getChildren().add(addTagButton);
    }

    @FXML
    private void handleNext() {
        saveProfileToSession();

        UserProfile profile = UserSession.getInstance().getUserProfile();
        System.out.println("[AETHER] Perfil guardado na sessão: " + profile);
    }

    @FXML
    private void handleSkip() {
        UserSession.getInstance().resetUserProfile();
        System.out.println("[AETHER] Passo de perfil ignorado pelo utilizador.");
    }

    @FXML
    private void handleTagToggle(ActionEvent event) {
        toggleTagSelection((Button) event.getSource());
    }

    @FXML
    private void handleAddTag() {
        Dialog<String> dialog = new Dialog<>();

        DialogPane dialogPane = dialog.getDialogPane();

        // Remove a barra nativa do sistema e torna a janela transparente
        Stage stage = (Stage) dialogPane.getScene().getWindow();
        stage.initStyle(StageStyle.TRANSPARENT);
        dialogPane.getScene().setFill(Color.TRANSPARENT);

        // Limpa o ícone e o cabeçalho predefinidos
        dialogPane.setGraphic(null);
        dialogPane.setHeaderText(null);

        // Aplica o tema visual AETHER
        if (rootPane != null && !rootPane.getStylesheets().isEmpty()) {
            dialogPane.getStylesheets().addAll(rootPane.getStylesheets());
        }
        dialogPane.getStyleClass().add("custom-dialog-pane");

        // Botões
        ButtonType addButtonType = new ButtonType("Add Role", ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, addButtonType);

        // Conteúdo
        VBox content = new VBox(16);
        content.setPadding(new Insets(10, 4, 10, 4));

        Label titleLabel = new Label("Add Custom Occupation");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");

        Label subtitleLabel = new Label("Specify your role to personalize your AETHER experience.");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");

        VBox headerBox = new VBox(4, titleLabel, subtitleLabel);

        Label inputLabel = new Label("Occupation / Role");
        inputLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #cbd5e1;");

        TextField inputField = new TextField();
        inputField.setPromptText("e.g. Data Scientist, UX Architect...");
        inputField.getStyleClass().add("campo-texto");
        inputField.setPrefWidth(320);

        VBox inputGroup = new VBox(6, inputLabel, inputField);

        content.getChildren().addAll(headerBox, inputGroup);
        dialogPane.setContent(content);

        Platform.runLater(inputField::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == addButtonType) {
                return inputField.getText().trim();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        result.filter(text -> !text.isEmpty())
                .ifPresent(this::addCustomOccupationTag);
    }

    private void addCustomOccupationTag(String label) {
        // Se a tag já existir entre as opções visíveis, apenas seleciona-a
        Button existing = findTagButtonByLabel(label);
        if (existing != null) {
            toggleTagSelection(existing);
            return;
        }

        Button tag = new Button(label);
        tag.getStyleClass().add("pill-tag");
        tag.setOnAction(this::handleTagToggle);

        int addButtonIndex = occupationTagsBox.getChildren().indexOf(addTagButton);
        occupationTagsBox.getChildren().add(addButtonIndex, tag);

        toggleTagSelection(tag);
    }

    private void toggleTagSelection(Button tag) {
        if (selectedTagButton != null) {
            selectedTagButton.getStyleClass().remove("pill-tag-selected");
        }

        if (selectedTagButton == tag) {
            selectedTagButton = null;
        } else {
            selectedTagButton = tag;
            if (!selectedTagButton.getStyleClass().contains("pill-tag-selected")) {
                selectedTagButton.getStyleClass().add("pill-tag-selected");
            }
        }
    }

    private void saveProfileToSession() {
        UserProfile profile = UserSession.getInstance().getUserProfile();
        profile.setFullName(fullNameInput.getText());
        profile.setPreferredName(preferredNameInput.getText());
        profile.setBirthDate(parseBirthDate(birthdayInput.getText()));

        List<String> occupations = new ArrayList<>();
        if (selectedTagButton != null) {
            occupations.add(selectedTagButton.getText());
        }
        profile.setOccupations(occupations);

        profile.setAboutYou(aboutYouInput.getText());
    }

    private void prefillFromSession() {
        UserProfile profile = UserSession.getInstance().getUserProfile();
        if (profile.isEmpty()) {
            return;
        }

        fullNameInput.setText(profile.getFullName());
        preferredNameInput.setText(profile.getPreferredName());
        if (profile.getBirthDate() != null) {
            birthdayInput.setText(BIRTHDAY_FORMAT.format(profile.getBirthDate()));
        }
        aboutYouInput.setText(profile.getAboutYou());

        if (!profile.getOccupations().isEmpty()) {
            String label = profile.getOccupations().get(0);
            Button existing = findTagButtonByLabel(label);
            if (existing != null) {
                toggleTagSelection(existing);
            } else {
                addCustomOccupationTag(label);
            }
        }
    }

    private Button findTagButtonByLabel(String label) {
        return occupationTagsBox.getChildren().stream()
                .filter(node -> node instanceof Button)
                .map(node -> (Button) node)
                .filter(button -> button != addTagButton && button.getText().equalsIgnoreCase(label))
                .findFirst()
                .orElse(null);
    }

    private LocalDate parseBirthDate(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String normalized = rawText.trim().replaceAll("\\s*/\\s*", "/");
        try {
            return LocalDate.parse(normalized, BIRTHDAY_FORMAT);
        } catch (DateTimeParseException e) {
            System.err.println("[AETHER] Data de nascimento inválida ('" + rawText + "'); campo ignorado.");
            return null;
        }
    }
}