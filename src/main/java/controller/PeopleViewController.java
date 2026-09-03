package controller;

import java.net.URL;
import java.time.LocalDate;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import domain.entities.Person;
import persistence.VaultManager;
import util.AetherDialogs;

/**
 * Controlador da vista de Pessoas ({@code people_view.fxml}).
 * <p>
 * Apresenta a lista de pessoas do vault e permite adicionar novas pessoas
 * através de um formulário modal. Todas as pessoas são lidas do VaultManager.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class PeopleViewController implements Initializable {

    public PeopleViewController() {
        // Construtor por omissão.
    }

    private static final Logger LOGGER = Logger.getLogger(PeopleViewController.class.getName());

    @FXML private VBox peopleContainer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadPeople();
    }

    /**
     * Carrega a lista de pessoas do vault.
     */
    private void loadPeople() {
        peopleContainer.getChildren().clear();
        var people = VaultManager.listPeople();

        if (people.isEmpty()) {
            Label empty = new Label("No people yet. Add someone to get started.");
            empty.getStyleClass().add("dash-empty-state");
            peopleContainer.getChildren().add(empty);
            return;
        }

        for (Person person : people) {
            peopleContainer.getChildren().add(createPersonCard(person));
        }
    }

    /**
     * Cria um cartão visual para uma pessoa.
     */
    private VBox createPersonCard(Person person) {
        VBox card = new VBox(6);
        card.getStyleClass().addAll("dash-card", "dash-summary-card");
        card.setPadding(new Insets(14));
        card.setCursor(javafx.scene.Cursor.HAND);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(person.getName());
        name.getStyleClass().add("dash-card-title-sm");
        header.getChildren().add(name);

        if (!person.getOccupation().isBlank()) {
            Label occ = new Label(" — " + person.getOccupation());
            occ.getStyleClass().add("dash-list-item-sub");
            header.getChildren().add(occ);
        }

        // Espaçador que empurra o botão Delete para a direita do cartão.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);
        header.getChildren().add(AetherDialogs.deleteButton(person.getName(), () -> {
            VaultManager.deletePerson(person);
            loadPeople();
        }));

        if (!person.getAbout().isBlank()) {
            String preview = person.getAbout();
            if (preview.length() > 80) {
                preview = preview.substring(0, 80) + "...";
            }
            Label about = new Label(preview);
            about.getStyleClass().add("dash-empty-state");
            about.setWrapText(true);
            card.getChildren().addAll(header, about);
        } else {
            card.getChildren().add(header);
        }

        card.setOnMouseClicked(e -> showEditPersonDialog(person));
        return card;
    }

    /**
     * Handler do botão de adicionar pessoa.
     */
    @FXML
    private void handleAddPerson() {
        showAddPersonDialog();
    }

    /**
     * Mostra o diálogo de adicionar pessoa.
     */
    private void showAddPersonDialog() {
        Dialog<Person> dialog = createPersonDialog(null);
        Optional<Person> result = dialog.showAndWait();
        result.ifPresent(person -> {
            VaultManager.savePerson(person);
            loadPeople();
        });
    }

    /**
     * Mostra o diálogo de editar pessoa.
     */
    private void showEditPersonDialog(Person existing) {
        Dialog<Person> dialog = createPersonDialog(existing);
        Optional<Person> result = dialog.showAndWait();
        result.ifPresent(person -> {
            VaultManager.savePerson(person);
            loadPeople();
        });
    }

    /**
     * Cria o diálogo de adicionar/editar pessoa.
     */
    private Dialog<Person> createPersonDialog(Person existing) {
        Dialog<Person> dialog = new Dialog<>();
        DialogPane dialogPane = dialog.getDialogPane();
        AetherDialogs.style(dialog);

        Label titleLabel = new Label(existing == null ? "Add Person" : "Edit Person");
        titleLabel.getStyleClass().add("subtitulo-perfil");

        Label subtitleLabel = new Label("Enter the details for this person.");
        subtitleLabel.getStyleClass().add("descricao-perfil");

        TextField nameField = new TextField();
        nameField.setPromptText("Full name");
        nameField.getStyleClass().add("campo-texto");
        nameField.setPrefWidth(320);

        TextField occupationField = new TextField();
        occupationField.setPromptText("Occupation / role");
        occupationField.getStyleClass().add("campo-texto");

        DatePicker birthdayField = AetherDialogs.pastDatePicker();

        TextArea aboutArea = new TextArea();
        aboutArea.setPromptText("Notes about this person...");
        aboutArea.getStyleClass().add("area-texto");
        aboutArea.setPrefRowCount(3);
        aboutArea.setPrefWidth(320);

        if (existing != null) {
            nameField.setText(existing.getName());
            occupationField.setText(existing.getOccupation());
            if (existing.getBirthDate() != null) {
                birthdayField.setValue(existing.getBirthDate());
            }
            aboutArea.setText(existing.getAbout());
        }

        VBox content = new VBox(12);
        content.setPadding(new Insets(10, 4, 10, 4));
        content.getChildren().addAll(
                titleLabel, subtitleLabel,
                AetherDialogs.fieldLabel("Name"), nameField,
                AetherDialogs.fieldLabel("Occupation"), occupationField,
                AetherDialogs.fieldLabel("Birthday (optional)"), birthdayField,
                AetherDialogs.fieldLabel("About"), aboutArea
        );

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(cancelType, saveType);
        dialogPane.setContent(content);

        Platform.runLater(nameField::requestFocus);

        dialog.setResultConverter(button -> {
            if (button != saveType) return null;
            String name = nameField.getText().trim();
            if (name.isBlank()) return null;

            LocalDate birthday = birthdayField.getValue();
            Person person = existing != null ? existing : new Person();
            person.setName(name);
            person.setOccupation(occupationField.getText().trim());
            person.setBirthDate(birthday);
            person.setAbout(aboutArea.getText().trim());
            person.touch();
            return person;
        });

        return dialog;
    }
}
