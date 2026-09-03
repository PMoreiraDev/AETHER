package controller;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import domain.entities.Event;
import persistence.VaultManager;
import util.AetherDialogs;

/**
 * Controlador da vista de Eventos ({@code events_view.fxml}).
 * <p>
 * Apresenta a lista de eventos do vault e permite adicionar novos eventos
 * através de um formulário modal.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class EventsViewController implements Initializable {

    public EventsViewController() {
        // Construtor por omissão.
    }

    private static final Logger LOGGER = Logger.getLogger(EventsViewController.class.getName());

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private VBox eventsContainer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadEvents();
    }

    private void loadEvents() {
        eventsContainer.getChildren().clear();
        var events = VaultManager.listEvents();

        if (events.isEmpty()) {
            Label empty = new Label("No events yet.");
            empty.getStyleClass().add("dash-empty-state");
            eventsContainer.getChildren().add(empty);
            return;
        }

        for (Event event : events) {
            eventsContainer.getChildren().add(createEventCard(event));
        }
    }

    private VBox createEventCard(Event event) {
        VBox card = new VBox(6);
        card.getStyleClass().addAll("dash-card", "dash-summary-card");
        card.setPadding(new Insets(14));
        card.setCursor(javafx.scene.Cursor.HAND);

        Label title = new Label(event.getTitle());
        title.getStyleClass().add("dash-card-title-sm");

        // Cabeçalho com título à esquerda e botão Delete à direita.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer,
                AetherDialogs.deleteButton(event.getTitle(), () -> {
                    VaultManager.deleteEvent(event);
                    loadEvents();
                }));
        header.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(header);

        if (event.getStartDateTime() != null) {
            Label date = new Label(event.getStartDateTime().format(DT_FMT));
            date.getStyleClass().add("dash-list-item-sub");
            card.getChildren().add(date);
        }

        if (!event.getLocation().isBlank()) {
            Label loc = new Label("📍 " + event.getLocation());
            loc.getStyleClass().add("dash-list-item-sub");
            card.getChildren().add(loc);
        }

        if (!event.getDescription().isBlank()) {
            String preview = event.getDescription();
            if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
            Label desc = new Label(preview);
            desc.getStyleClass().add("dash-empty-state");
            desc.setWrapText(true);
            card.getChildren().add(desc);
        }

        card.setOnMouseClicked(e -> showEditEventDialog(event));
        return card;
    }

    @FXML
    private void handleAddEvent() {
        showAddEventDialog();
    }

    private void showAddEventDialog() {
        Dialog<Event> dialog = createEventDialog(null);
        Optional<Event> result = dialog.showAndWait();
        result.ifPresent(event -> {
            VaultManager.saveEvent(event);
            loadEvents();
        });
    }

    private void showEditEventDialog(Event existing) {
        Dialog<Event> dialog = createEventDialog(existing);
        Optional<Event> result = dialog.showAndWait();
        result.ifPresent(event -> {
            VaultManager.saveEvent(event);
            loadEvents();
        });
    }

    private Dialog<Event> createEventDialog(Event existing) {
        Dialog<Event> dialog = new Dialog<>();
        DialogPane dialogPane = dialog.getDialogPane();
        AetherDialogs.style(dialog);

        Label titleLabel = new Label(existing == null ? "Add Event" : "Edit Event");
        titleLabel.getStyleClass().add("subtitulo-perfil");

        Label subtitleLabel = new Label("Enter the details for this event.");
        subtitleLabel.getStyleClass().add("descricao-perfil");

        TextField titleField = new TextField();
        titleField.setPromptText("Event title");
        titleField.getStyleClass().add("campo-texto");
        titleField.setPrefWidth(320);

        DatePicker dateField = AetherDialogs.datePicker();

        TextField timeField = new TextField();
        timeField.setPromptText("Time (HH:mm)");
        timeField.getStyleClass().add("campo-texto");

        TextField locationField = new TextField();
        locationField.setPromptText("Location");
        locationField.getStyleClass().add("campo-texto");

        TextArea descArea = new TextArea();
        descArea.setPromptText("Description...");
        descArea.getStyleClass().add("area-texto");
        descArea.setPrefRowCount(3);
        descArea.setPrefWidth(320);

        if (existing != null) {
            titleField.setText(existing.getTitle());
            if (existing.getStartDateTime() != null) {
                dateField.setValue(existing.getStartDateTime().toLocalDate());
                timeField.setText(existing.getStartDateTime().format(DateTimeFormatter.ofPattern("HH:mm")));
            }
            locationField.setText(existing.getLocation());
            descArea.setText(existing.getDescription());
        }

        VBox content = new VBox(12);
        content.setPadding(new Insets(10, 4, 10, 4));
        content.getChildren().addAll(
                titleLabel, subtitleLabel,
                AetherDialogs.fieldLabel("Title"), titleField,
                AetherDialogs.fieldLabel("Date"), dateField,
                AetherDialogs.fieldLabel("Time"), timeField,
                AetherDialogs.fieldLabel("Location"), locationField,
                AetherDialogs.fieldLabel("Description"), descArea
        );

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(cancelType, saveType);
        dialogPane.setContent(content);

        Platform.runLater(titleField::requestFocus);

        dialog.setResultConverter(button -> {
            if (button != saveType) return null;
            String title = titleField.getText().trim();
            if (title.isBlank()) return null;

            LocalDateTime start = parseDateTime(
                    dateField.getValue() != null ? dateField.getValue().toString() : "",
                    timeField.getText().trim());
            Event event = existing != null ? existing : new Event();
            event.setTitle(title);
            event.setStartDateTime(start);
            event.setLocation(locationField.getText().trim());
            event.setDescription(descArea.getText().trim());
            event.touch();
            return event;
        });

        return dialog;
    }

    private LocalDateTime parseDateTime(String dateStr, String timeStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            String dt = dateStr.trim();
            if (timeStr != null && !timeStr.isBlank()) {
                dt += "T" + timeStr.trim();
            } else {
                dt += "T00:00";
            }
            return LocalDateTime.parse(dt);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
