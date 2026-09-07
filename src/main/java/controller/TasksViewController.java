package controller;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import javafx.scene.control.ComboBox;
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
import domain.entities.Task;
import domain.entities.TaskStatus;
import domain.entities.TaskPriority;
import persistence.VaultManager;
import util.AetherDialogs;

/**
 * Controlador da vista de Tarefas ({@code tasks_view.fxml}).
 * <p>
 * Apresenta a lista de tarefas do vault e permite adicionar novas tarefas
 * através de um formulário modal.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class TasksViewController implements Initializable {

    public TasksViewController() {
        // Construtor por omissão.
    }

    private static final Logger LOGGER = Logger.getLogger(TasksViewController.class.getName());

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private VBox tasksContainer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadTasks();
    }

    private void loadTasks() {
        tasksContainer.getChildren().clear();
        var tasks = VaultManager.listTasks();

        if (tasks.isEmpty()) {
            tasksContainer.getChildren().add(
                    util.EmptyState.of("empty.tasks.title", "empty.tasks.hint")
                            .cta("empty.tasks.cta", () -> handleAddTask())
                            .build());
            return;
        }

        for (Task task : tasks) {
            tasksContainer.getChildren().add(createTaskCard(task));
        }
    }

    private VBox createTaskCard(Task task) {
        VBox card = new VBox(6);
        card.getStyleClass().addAll("dash-card", "dash-summary-card");
        card.setPadding(new Insets(14));
        card.setCursor(javafx.scene.Cursor.HAND);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(task.getTitle());
        title.getStyleClass().add("dash-card-title-sm");
        header.getChildren().add(title);

        Label statusBadge = new Label(task.getStatus().getDisplayName());
        statusBadge.getStyleClass().addAll("dash-count-badge");
        header.getChildren().add(statusBadge);

        if (task.getPriority() != TaskPriority.NONE) {
            Label prioBadge = new Label(task.getPriority().getDisplayName());
            prioBadge.getStyleClass().add("dash-count-badge");
            header.getChildren().add(prioBadge);
        }

        // Espaçador que empurra o botão Delete para a direita do cartão.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);
        header.getChildren().add(AetherDialogs.deleteButton(task.getTitle(), () -> {
            VaultManager.deleteTask(task);
            loadTasks();
        }));

        card.getChildren().add(header);

        if (task.getDeadline() != null) {
            Label deadline = new Label("⏰ " + task.getDeadline().format(DT_FMT));
            deadline.getStyleClass().add("dash-list-item-sub");
            card.getChildren().add(deadline);
        }

        if (!task.getDescription().isBlank()) {
            String preview = task.getDescription();
            if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
            Label desc = new Label(preview);
            desc.getStyleClass().add("dash-empty-state");
            desc.setWrapText(true);
            card.getChildren().add(desc);
        }

        card.setOnMouseClicked(e -> showEditTaskDialog(task));
        return card;
    }

    @FXML
    private void handleAddTask() {
        showAddTaskDialog();
    }

    private void showAddTaskDialog() {
        Dialog<Task> dialog = createTaskDialog(null);
        Optional<Task> result = dialog.showAndWait();
        result.ifPresent(task -> {
            VaultManager.saveTask(task);
            loadTasks();
        });
    }

    private void showEditTaskDialog(Task existing) {
        Dialog<Task> dialog = createTaskDialog(existing);
        Optional<Task> result = dialog.showAndWait();
        result.ifPresent(task -> {
            VaultManager.saveTask(task);
            loadTasks();
        });
    }

    private Dialog<Task> createTaskDialog(Task existing) {
        Dialog<Task> dialog = new Dialog<>();
        DialogPane dialogPane = dialog.getDialogPane();
        AetherDialogs.style(dialog);

        Label titleLabel = new Label(existing == null ? "Add Task" : "Edit Task");
        titleLabel.getStyleClass().add("subtitulo-perfil");

        Label subtitleLabel = new Label("Enter the details for this task.");
        subtitleLabel.getStyleClass().add("descricao-perfil");

        TextField titleField = new TextField();
        titleField.setPromptText("Task title");
        titleField.getStyleClass().add("campo-texto");
        titleField.setPrefWidth(320);

        TextField deadlineTimeField = new TextField();
        deadlineTimeField.setPromptText("Time (HH:mm, optional)");
        deadlineTimeField.getStyleClass().add("campo-texto");

        DatePicker deadlineDateField = AetherDialogs.datePicker();

        ComboBox<TaskStatus> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll(TaskStatus.values());
        statusCombo.setValue(TaskStatus.TODO);
        statusCombo.getStyleClass().add("combo-box-glass");

        ComboBox<TaskPriority> priorityCombo = new ComboBox<>();
        priorityCombo.getItems().addAll(TaskPriority.values());
        priorityCombo.setValue(TaskPriority.NONE);
        priorityCombo.getStyleClass().add("combo-box-glass");

        TextArea descArea = new TextArea();
        descArea.setPromptText("Description...");
        descArea.getStyleClass().add("area-texto");
        descArea.setPrefRowCount(3);
        descArea.setPrefWidth(320);

        if (existing != null) {
            titleField.setText(existing.getTitle());
            if (existing.getDeadline() != null) {
                deadlineDateField.setValue(existing.getDeadline().toLocalDate());
                deadlineTimeField.setText(existing.getDeadline().format(DateTimeFormatter.ofPattern("HH:mm")));
            }
            statusCombo.setValue(existing.getStatus());
            priorityCombo.setValue(existing.getPriority());
            descArea.setText(existing.getDescription());
        }

        VBox content = new VBox(12);
        content.setPadding(new Insets(10, 4, 10, 4));
        content.getChildren().addAll(
                titleLabel, subtitleLabel,
                AetherDialogs.fieldLabel("Title"), titleField,
                AetherDialogs.fieldLabel("Deadline Date (optional)"), deadlineDateField,
                AetherDialogs.fieldLabel("Deadline Time (optional)"), deadlineTimeField,
                AetherDialogs.fieldLabel("Status"), statusCombo,
                AetherDialogs.fieldLabel("Priority"), priorityCombo,
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

            LocalDateTime deadline = null;
            if (deadlineDateField.getValue() != null) {
                LocalTime time = LocalTime.MIDNIGHT;
                String timeText = deadlineTimeField.getText().trim();
                if (!timeText.isBlank()) {
                    try {
                        time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("HH:mm"));
                    } catch (DateTimeParseException e) {
                        time = LocalTime.MIDNIGHT;
                    }
                }
                deadline = LocalDateTime.of(deadlineDateField.getValue(), time);
            }

            Task task = existing != null ? existing : new Task();
            task.setTitle(title);
            task.setDeadline(deadline);
            task.setStatus(statusCombo.getValue());
            task.setPriority(priorityCombo.getValue());
            task.setDescription(descArea.getText().trim());
            task.touch();
            return task;
        });

        return dialog;
    }
}
