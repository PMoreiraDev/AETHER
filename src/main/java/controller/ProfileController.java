package controller;

import domain.Occupation;
import domain.UserProfile;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import session.UserSession;
import util.Navigator;
import util.StyleUtils;

/**
 * Controlador do passo 1 do onboarding — criação do perfil ({@code profile.fxml}).
 * <p>
 * Gere o formulário de dados pessoais (nome, nome preferido, data de nascimento,
 * ocupações e descrição livre), permite adicionar ocupações personalizadas e
 * guarda tudo na sessão antes de avançar para o passo seguinte.
 * </p>
 * <p>
 * O passo é obrigatório: não existe forma de o ignorar. O botão "Next" continua
 * clicável, mas só avança quando os campos assinalados com asterisco estiverem
 * preenchidos e a data de nascimento, se indicada, for válida; caso contrário
 * explica o que falta em vez de ficar inerte.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.1
 */
public class ProfileController implements Initializable {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public ProfileController() {
        // Construtor por omissão explícito, documentado para o Javadoc.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(ProfileController.class.getName());

    /** Formato aceite e apresentado para a data de nascimento (ex.: 4/9/1998). */
    private static final DateTimeFormatter BIRTHDAY_FORMAT = DateTimeFormatter.ofPattern("d/M/yyyy");

    /** Caminho do ecrã de configuração do Ollama (passo 2) no classpath. */
    private static final String OLLAMA_SETUP_VIEW = "/FXML/ollama_setup.fxml";

    /** Classe CSS que marca uma tag de ocupação como selecionada. */
    private static final String SELECTED_TAG_CLASS = "pill-tag-selected";

    /** Classe CSS que assinala um campo com conteúdo inválido ou em falta. */
    private static final String FIELD_ERROR_CLASS = "campo-texto-erro";

    /** Número mínimo de caracteres aceite num nome. */
    private static final int MIN_NAME_LENGTH = 2;

    /** Painel raiz do ecrã, usado como referência de dimensões e de janela. */
    @FXML
    private StackPane rootPane;

    /** Imagem de fundo, redimensionada dinamicamente. */
    @FXML
    private ImageView backgroundImageView;

    /** Campo do nome completo. */
    @FXML
    private TextField fullNameInput;

    /** Campo do nome preferido. */
    @FXML
    private TextField preferredNameInput;

    /** Campo da data de nascimento, em texto livre. */
    @FXML
    private TextField birthdayInput;

    /** Campo de descrição pessoal livre. */
    @FXML
    private TextArea aboutYouInput;

    /** Contentor das tags de ocupação, preenchido dinamicamente. */
    @FXML
    private FlowPane occupationTagsBox;

    /** Mensagem de validação apresentada acima do rodapé. */
    @FXML
    private Label validationLabel;

    /** Botão que avança para o passo 2, apenas com o perfil válido. */
    @FXML
    private Button nextStepButton;

    /** Botão "+" que abre o diálogo de ocupação personalizada. */
    private Button addTagButton;

    /** Tags de ocupação atualmente selecionadas, pela ordem de seleção. */
    private final Set<Button> selectedTags = new LinkedHashSet<>();

    /**
     * Campos que o utilizador já visitou ou que uma tentativa de avançar
     * assinalou.
     * <p>
     * Só estes recebem marcação vermelha: um formulário ainda em branco não deve
     * aparecer todo em erro no momento em que abre.
     * </p>
     */
    private final Set<TextField> touchedFields = new LinkedHashSet<>();

    /**
     * Prepara o ecrã: liga o fundo às dimensões da janela, cria as tags de
     * ocupação e repõe quaisquer dados já existentes na sessão.
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

        setupOccupationTags();
        prefillFromSession();
        setupRequiredFieldValidation();
    }

    /**
     * Liga a validação aos campos obrigatórios.
     * <p>
     * Cada alteração de texto e cada perda de foco reavaliam o formulário, pelo
     * que as marcas de erro desaparecem assim que o problema é corrigido. O botão
     * "Next" <em>não</em> é desativado de propósito: um botão inerte não explica
     * ao utilizador o que está a faltar, enquanto um clique bloqueado com
     * mensagem explica.
     * </p>
     */
    private void setupRequiredFieldValidation() {
        for (TextField field : List.of(fullNameInput, preferredNameInput, birthdayInput)) {
            field.textProperty().addListener((observable, oldValue, newValue) -> revalidate());
            field.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
                if (Boolean.FALSE.equals(isFocused)) {
                    touchedFields.add(field);
                    revalidate();
                }
            });
        }

        nextStepButton.setTooltip(new Tooltip("Fill in the required fields to continue."));
    }

    /**
     * Reavalia o formulário e limpa as marcas de erro já corrigidas.
     */
    private void revalidate() {
        markField(fullNameInput, !isFilled(fullNameInput));
        markField(preferredNameInput, !isFilled(preferredNameInput));
        markField(birthdayInput, !isBirthdayAcceptable());

        if (isProfileValid()) {
            hideValidationMessage();
        }
    }

    /**
     * Marca todos os campos obrigatórios como visitados, para que uma tentativa
     * de avançar revele de imediato tudo o que está em falta.
     */
    private void markAllFieldsAsTouched() {
        touchedFields.add(fullNameInput);
        touchedFields.add(preferredNameInput);
        touchedFields.add(birthdayInput);
    }

    /**
     * Indica se o formulário reúne as condições mínimas para avançar.
     *
     * @return {@code true} se os campos obrigatórios estiverem preenchidos e a
     *         data de nascimento, quando indicada, for válida
     */
    private boolean isProfileValid() {
        return isFilled(fullNameInput) && isFilled(preferredNameInput) && isBirthdayAcceptable();
    }

    /**
     * Verifica se um campo obrigatório tem conteúdo útil.
     * <p>
     * Espaços isolados não contam como preenchimento, e exige-se um mínimo de
     * {@link #MIN_NAME_LENGTH} caracteres para travar entradas como "a".
     * </p>
     *
     * @param field o campo a avaliar
     * @return {@code true} se o campo tiver um valor aceitável
     */
    private boolean isFilled(TextField field) {
        String value = field.getText();
        return value != null && value.trim().length() >= MIN_NAME_LENGTH;
    }

    /**
     * Verifica se a data de nascimento pode ser aceite.
     * <p>
     * O campo continua opcional: vazio é válido. Preenchido, tem de ser uma data
     * reconhecível e não futura — antes, uma data inválida era silenciosamente
     * descartada e o utilizador nunca sabia que a tinha perdido.
     * </p>
     *
     * @return {@code true} se o campo estiver vazio ou contiver uma data válida
     */
    private boolean isBirthdayAcceptable() {
        String value = birthdayInput.getText();
        if (value == null || value.isBlank()) {
            return true;
        }
        return parseBirthDate(value) != null;
    }

    /**
     * Acrescenta ou remove a marca visual de erro num campo.
     *
     * @param field o campo a marcar
     * @param invalid {@code true} para assinalar o campo como inválido
     */
    private void markField(TextField field, boolean invalid) {
        if (invalid && touchedFields.contains(field)) {
            if (!field.getStyleClass().contains(FIELD_ERROR_CLASS)) {
                field.getStyleClass().add(FIELD_ERROR_CLASS);
            }
        } else {
            field.getStyleClass().remove(FIELD_ERROR_CLASS);
        }
    }

    /**
     * Apresenta uma mensagem de validação acima do rodapé.
     *
     * @param message o texto a mostrar ao utilizador
     */
    private void showValidationMessage(String message) {
        validationLabel.setText(message);
        validationLabel.setVisible(true);
    }

    /**
     * Esconde a mensagem de validação, mantendo o espaço que ocupa.
     */
    private void hideValidationMessage() {
        validationLabel.setText("");
        validationLabel.setVisible(false);
    }

    /**
     * Cria os botões de ocupação predefinidos e o botão de adicionar tag.
     * <p>
     * A constante {@link Occupation#OTHER} é excluída da lista visível: serve
     * apenas como categoria de recurso para ocupações escritas pelo utilizador.
     * </p>
     */
    private void setupOccupationTags() {
        occupationTagsBox.getChildren().clear();
        selectedTags.clear();

        for (Occupation occupation : Occupation.values()) {
            if (occupation == Occupation.OTHER) {
                continue;
            }
            occupationTagsBox.getChildren().add(createTagButton(occupation.getDisplayName()));
        }

        addTagButton = new Button("+");
        addTagButton.getStyleClass().add("pill-tag-adicionar");
        addTagButton.setAccessibleText("Add a custom occupation");
        addTagButton.setOnAction(event -> handleAddTag());
        occupationTagsBox.getChildren().add(addTagButton);
    }

    /**
     * Cria um botão de tag de ocupação já ligado ao seu tratador de eventos.
     *
     * @param label o texto a apresentar na tag
     * @return o botão pronto a adicionar ao contentor
     */
    private Button createTagButton(String label) {
        Button tag = new Button(label);
        tag.getStyleClass().add("pill-tag");
        tag.setOnAction(this::handleTagToggle);
        return tag;
    }

    /**
     * Valida o formulário e, se estiver correto, guarda-o na sessão e avança para
     * o passo seguinte.
     * <p>
     * Este é o único ponto de saída do passo 1: sem um perfil válido, não há
     * navegação possível para o passo 2.
     * </p>
     */
    @FXML
    private void handleNext() {
        if (!validateBeforeAdvancing()) {
            return;
        }

        saveProfileToSession();
        UserSession session = UserSession.getInstance();
        if (!session.saveUserProfile()) {
            showValidationMessage("Couldn't save your profile. Check disk permissions and try again.");
            return;
        }

        UserProfile profile = session.getUserProfile();
        LOGGER.info(() -> "Perfil guardado: " + profile);
        Navigator.navigate(rootPane, OLLAMA_SETUP_VIEW);
    }

    /**
     * Valida o formulário, assinala o primeiro problema encontrado e coloca o
     * foco no campo respetivo.
     *
     * @return {@code true} se o perfil puder ser guardado
     */
    private boolean validateBeforeAdvancing() {
        markAllFieldsAsTouched();
        revalidate();

        if (!isFilled(fullNameInput)) {
            showValidationMessage("Please enter your name to continue.");
            fullNameInput.requestFocus();
            return false;
        }

        if (!isFilled(preferredNameInput)) {
            showValidationMessage("Please enter the name AETHER should call you.");
            preferredNameInput.requestFocus();
            return false;
        }

        if (!isBirthdayAcceptable()) {
            showValidationMessage("Birthday must be a real past date in the format D/M/YYYY.");
            birthdayInput.requestFocus();
            return false;
        }

        hideValidationMessage();
        return true;
    }

    /**
     * Alterna a seleção da tag de ocupação que originou o evento.
     *
     * @param event o evento de clique no botão da tag
     */
    @FXML
    private void handleTagToggle(ActionEvent event) {
        if (event.getSource() instanceof Button tag) {
            toggleTagSelection(tag);
        }
    }

    /**
     * Abre um diálogo para o utilizador introduzir uma ocupação personalizada.
     */
    @FXML
    private void handleAddTag() {
        Dialog<String> dialog = new Dialog<>();
        DialogPane dialogPane = dialog.getDialogPane();

        dialogPane.setGraphic(null);
        dialogPane.setHeaderText(null);
        StyleUtils.applyTo(dialogPane);
        dialogPane.getStyleClass().add("custom-dialog-pane");

        makeDialogTransparent(dialogPane);

        ButtonType addButtonType = new ButtonType("Add Role", ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, addButtonType);

        TextField inputField = new TextField();
        inputField.setPromptText("e.g. Data Scientist, UX Architect...");
        inputField.getStyleClass().add("campo-texto");
        inputField.setPrefWidth(320);

        dialogPane.setContent(buildAddTagContent(inputField));
        Platform.runLater(inputField::requestFocus);

        dialog.setResultConverter(button -> button == addButtonType ? inputField.getText().trim() : null);

        Optional<String> result = dialog.showAndWait();
        result.filter(text -> !text.isBlank()).ifPresent(this::addCustomOccupationTag);
    }

    /**
     * Constrói o conteúdo visual do diálogo de ocupação personalizada.
     *
     * @param inputField o campo de texto onde a ocupação é escrita
     * @return o contentor com o cabeçalho e o campo de introdução
     */
    private VBox buildAddTagContent(TextField inputField) {
        Label titleLabel = new Label("Add Custom Occupation");
        titleLabel.getStyleClass().add("subtitulo-perfil");

        Label subtitleLabel = new Label("Specify your role to personalize your AETHER experience.");
        subtitleLabel.getStyleClass().add("descricao-perfil");
        subtitleLabel.setWrapText(true);

        VBox headerBox = new VBox(4, titleLabel, subtitleLabel);

        Label inputLabel = new Label("Occupation / Role");
        inputLabel.getStyleClass().add("label-campo");

        VBox inputGroup = new VBox(6, inputLabel, inputField);

        VBox content = new VBox(16, headerBox, inputGroup);
        content.setPadding(new Insets(10, 4, 10, 4));
        return content;
    }

    /**
     * Aplica fundo transparente ao diálogo, para que os cantos arredondados
     * definidos no CSS não fiquem sobre um retângulo opaco.
     *
     * @param dialogPane o painel do diálogo a configurar
     */
    private void makeDialogTransparent(DialogPane dialogPane) {
        if (dialogPane.getScene() == null) {
            return;
        }
        dialogPane.getScene().setFill(Color.TRANSPARENT);
        if (dialogPane.getScene().getWindow() instanceof Stage dialogStage && !dialogStage.isShowing()) {
            try {
                dialogStage.initStyle(StageStyle.TRANSPARENT);
            } catch (IllegalStateException ignored) {
                // O diálogo já foi mostrado: mantém o estilo atual.
            }
        }
    }

    /**
     * Adiciona uma tag de ocupação personalizada, reaproveitando-a se já existir.
     *
     * @param label o texto da ocupação personalizada
     */
    private void addCustomOccupationTag(String label) {
        Button existing = findTagButtonByLabel(label);
        if (existing != null) {
            if (!selectedTags.contains(existing)) {
                toggleTagSelection(existing);
            }
            return;
        }

        Button tag = createTagButton(label);
        int addButtonIndex = occupationTagsBox.getChildren().indexOf(addTagButton);
        occupationTagsBox.getChildren().add(Math.max(addButtonIndex, 0), tag);

        toggleTagSelection(tag);
    }

    /**
     * Alterna o estado de seleção de uma tag de ocupação.
     * <p>
     * A seleção é múltipla, em coerência com {@link UserProfile#getOccupations()},
     * que guarda uma lista de ocupações.
     * </p>
     *
     * @param tag o botão da tag a alternar
     */
    private void toggleTagSelection(Button tag) {
        if (selectedTags.remove(tag)) {
            tag.getStyleClass().remove(SELECTED_TAG_CLASS);
            return;
        }

        selectedTags.add(tag);
        if (!tag.getStyleClass().contains(SELECTED_TAG_CLASS)) {
            tag.getStyleClass().add(SELECTED_TAG_CLASS);
        }
    }

    /**
     * Lê os campos do formulário e guarda-os no perfil da sessão.
     */
    private void saveProfileToSession() {
        UserProfile profile = UserSession.getInstance().getUserProfile();
        profile.setFullName(fullNameInput.getText());
        profile.setPreferredName(preferredNameInput.getText());
        profile.setBirthDate(parseBirthDate(birthdayInput.getText()));
        profile.setAboutYou(aboutYouInput.getText());

        List<String> occupations = new ArrayList<>();
        for (Button tag : selectedTags) {
            occupations.add(tag.getText());
        }
        profile.setOccupations(occupations);
    }

    /**
     * Preenche o formulário com os dados já presentes na sessão, permitindo ao
     * utilizador voltar atrás sem perder o que escreveu.
     */
    private void prefillFromSession() {
        UserProfile profile = UserSession.getInstance().getUserProfile();
        if (profile.isEmpty()) {
            return;
        }

        fullNameInput.setText(profile.getFullName());
        preferredNameInput.setText(profile.getPreferredName());
        aboutYouInput.setText(profile.getAboutYou());

        if (profile.getBirthDate() != null) {
            birthdayInput.setText(BIRTHDAY_FORMAT.format(profile.getBirthDate()));
        }

        for (String occupation : profile.getOccupations()) {
            Button existing = findTagButtonByLabel(occupation);
            if (existing != null) {
                toggleTagSelection(existing);
            } else {
                addCustomOccupationTag(occupation);
            }
        }
    }

    /**
     * Procura um botão de tag cujo texto corresponda ao rótulo indicado.
     *
     * @param label o texto a procurar; a comparação ignora maiúsculas/minúsculas
     * @return o botão correspondente, ou {@code null} se não existir
     */
    private Button findTagButtonByLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        for (Node node : occupationTagsBox.getChildren()) {
            if (node instanceof Button button
                    && button != addTagButton
                    && button.getText().equalsIgnoreCase(label.trim())) {
                return button;
            }
        }
        return null;
    }

    /**
     * Converte o texto introduzido numa data de nascimento válida.
     * <p>
     * Aceita o formato {@code d/M/yyyy} com espaços opcionais em torno das
     * barras (ex.: {@code 4 / 9 / 1998}). Datas inválidas ou no futuro são
     * descartadas, por serem impossíveis como data de nascimento.
     * </p>
     *
     * @param rawText o texto bruto introduzido pelo utilizador
     * @return a data convertida, ou {@code null} se estiver vazia ou for inválida
     */
    private LocalDate parseBirthDate(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }

        String normalized = rawText.trim().replaceAll("\\s*/\\s*", "/");
        try {
            LocalDate parsed = LocalDate.parse(normalized, BIRTHDAY_FORMAT);
            if (parsed.isAfter(LocalDate.now())) {
                LOGGER.warning(() -> "Data de nascimento no futuro ('" + rawText + "'); campo ignorado.");
                return null;
            }
            return parsed;
        } catch (DateTimeParseException e) {
            LOGGER.warning(() -> "Data de nascimento inválida ('" + rawText + "'); campo ignorado.");
            return null;
        }
    }
}