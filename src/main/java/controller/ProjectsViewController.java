package controller;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import domain.entities.Project;
import domain.entities.ProjectStatus;
import persistence.VaultManager;
import util.AetherDialogs;

/**
 * Controlador da vista de Projetos ({@code projects_view.fxml}).
 * <p>
 * Apresenta a lista de projetos do vault e permite criar, ver, editar e
 * eliminar projetos através de um formulário modal — seguindo exatamente o
 * mesmo padrão de {@link TasksViewController} (cartões, diálogo modal,
 * {@link AetherDialogs}), para que a arquitetura e o visual fiquem coerentes
 * com o resto da aplicação.
 * </p>
 * <p>
 * <b>Relações com outras entidades:</b> o AETHER já regista relações
 * inteiramente através de wikilinks {@code [[Nome]]} no corpo markdown de
 * cada entidade — é assim que o {@code ContextManager} (contexto da IA) e o
 * {@code GraphRenderer} (Context Graph) descobrem ligações, para qualquer
 * tipo de entidade, sem qualquer esquema de relações à parte. Este
 * controlador reutiliza esse mesmo mecanismo: o diálogo de projeto permite
 * escolher uma Pessoa ou outro Projeto existente e "Link" insere um
 * wikilink na descrição via {@link VaultManager#addWikilink}, exatamente
 * como o {@code NoteService} já faz para as notas. Não introduz nenhuma
 * estrutura de dados nova.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class ProjectsViewController implements Initializable {

    public ProjectsViewController() {
        // Construtor por omissão.
    }

    private static final Logger LOGGER = Logger.getLogger(ProjectsViewController.class.getName());

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private VBox projectsContainer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadProjects();
    }

    private void loadProjects() {
        projectsContainer.getChildren().clear();
        var projects = VaultManager.listProjects();

        if (projects.isEmpty()) {
            Label empty = new Label("No projects yet.");
            empty.getStyleClass().add("dash-empty-state");
            projectsContainer.getChildren().add(empty);
            return;
        }

        for (Project project : projects) {
            projectsContainer.getChildren().add(createProjectCard(project));
        }
    }

    private VBox createProjectCard(Project project) {
        VBox card = new VBox(6);
        card.getStyleClass().addAll("dash-card", "dash-summary-card");
        card.setPadding(new Insets(14));
        card.setCursor(javafx.scene.Cursor.HAND);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(project.getName());
        title.getStyleClass().add("dash-card-title-sm");
        header.getChildren().add(title);

        Label statusBadge = new Label(project.getStatus().getDisplayName());
        statusBadge.getStyleClass().add("dash-count-badge");
        header.getChildren().add(statusBadge);

        // Espaçador que empurra o botão Delete para a direita do cartão.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);
        header.getChildren().add(AetherDialogs.deleteButton(project.getName(), () -> {
            VaultManager.deleteProject(project);
            loadProjects();
        }));

        card.getChildren().add(header);

        if (project.getDeadline() != null) {
            Label deadline = new Label("⏰ " + project.getDeadline().format(DT_FMT));
            deadline.getStyleClass().add("dash-list-item-sub");
            card.getChildren().add(deadline);
        }

        if (!project.getDescription().isBlank()) {
            String preview = project.getDescription();
            if (preview.length() > 140) preview = preview.substring(0, 140) + "...";
            Label desc = new Label(preview);
            desc.getStyleClass().add("dash-empty-state");
            desc.setWrapText(true);
            card.getChildren().add(desc);
        }

        // Relações (wikilinks) já presentes na descrição — mesma leitura que
        // o ContextManager e o GraphRenderer usam para o resto do vault.
        List<String> links = VaultManager.extractWikilinks(project.getDescription());
        if (!links.isEmpty()) {
            Label related = new Label("🔗 Related: " + String.join(", ", links));
            related.getStyleClass().add("dash-list-item-sub");
            related.setWrapText(true);
            card.getChildren().add(related);
        }

        card.setOnMouseClicked(e -> showEditProjectDialog(project));
        return card;
    }

    @FXML
    private void handleAddProject() {
        showAddProjectDialog();
    }

    private void showAddProjectDialog() {
        Dialog<Project> dialog = createProjectDialog(null);
        Optional<Project> result = dialog.showAndWait();
        result.ifPresent(project -> {
            VaultManager.saveProject(project);
            loadProjects();
        });
    }

    private void showEditProjectDialog(Project existing) {
        Dialog<Project> dialog = createProjectDialog(existing);
        Optional<Project> result = dialog.showAndWait();
        result.ifPresent(project -> {
            VaultManager.saveProject(project);
            loadProjects();
        });
    }

    private Dialog<Project> createProjectDialog(Project existing) {
        Dialog<Project> dialog = new Dialog<>();
        DialogPane dialogPane = dialog.getDialogPane();
        AetherDialogs.style(dialog);

        Label titleLabel = new Label(existing == null ? "Add Project" : "Edit Project");
        titleLabel.getStyleClass().add("subtitulo-perfil");

        Label subtitleLabel = new Label("Enter the details for this project.");
        subtitleLabel.getStyleClass().add("descricao-perfil");

        TextField nameField = new TextField();
        nameField.setPromptText("Project name");
        nameField.getStyleClass().add("campo-texto");
        nameField.setPrefWidth(320);

        DatePicker deadlineDateField = AetherDialogs.datePicker();

        ComboBox<ProjectStatus> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll(ProjectStatus.values());
        statusCombo.setValue(ProjectStatus.PLANNED);
        statusCombo.getStyleClass().add("combo-box-glass");

        TextArea descArea = new TextArea();
        descArea.setPromptText("Description... (use the picker below to link people or other projects)");
        descArea.getStyleClass().add("area-texto");
        descArea.setPrefRowCount(4);
        descArea.setPrefWidth(320);
        descArea.setWrapText(true);

        // --- Related entities -------------------------------------------
        // Reutiliza o mecanismo de wikilinks já existente (VaultManager
        // .addWikilink / .extractWikilinks) em vez de criar um novo tipo de
        // relação: escolher uma entidade e clicar "Link" insere "[[Nome]]"
        // na descrição, tal como o NoteService já faz automaticamente para
        // as notas. O ContextManager e o GraphRenderer já leem estes
        // wikilinks de qualquer entidade, pelo que a relação fica
        // imediatamente disponível como contexto para a AETHER AI e no
        // Context Graph, sem qualquer trabalho extra.
        ComboBox<String> relatedCombo = new ComboBox<>();
        relatedCombo.setPromptText("Link a person or project...");
        relatedCombo.getStyleClass().add("combo-box-glass");
        relatedCombo.setPrefWidth(230);
        String selfName = existing != null ? existing.getName() : null;
        VaultManager.listPeople().forEach(p -> relatedCombo.getItems().add(p.getName()));
        VaultManager.listProjects().stream()
                .map(Project::getName)
                .filter(n -> selfName == null || !n.equalsIgnoreCase(selfName))
                .forEach(n -> relatedCombo.getItems().add(n));

        FlowPane relatedChips = new FlowPane(6, 6);

        // Guardado num array de 1 elemento para a lambda se poder referenciar
        // a si própria (recalcula os chips sempre que a descrição muda).
        final Runnable[] refreshHolder = new Runnable[1];
        refreshHolder[0] = () -> {
            relatedChips.getChildren().clear();
            for (String link : VaultManager.extractWikilinks(descArea.getText())) {
                Label chip = new Label(link + "  ✕");
                chip.getStyleClass().add("dash-count-badge");
                chip.setCursor(javafx.scene.Cursor.HAND);
                chip.setOnMouseClicked(ev -> {
                    String updated = removeWikilink(descArea.getText(), link);
                    descArea.setText(updated);
                    refreshHolder[0].run();
                });
                relatedChips.getChildren().add(chip);
            }
        };
        refreshHolder[0].run();

        Button linkButton = new Button("Link");
        linkButton.getStyleClass().addAll("botao-secundario", "button-small");
        Runnable finalRefreshChips = refreshHolder[0];
        linkButton.setOnAction(ev -> {
            String choice = relatedCombo.getValue();
            if (choice != null && !choice.isBlank()) {
                descArea.setText(VaultManager.addWikilink(descArea.getText(), choice));
                finalRefreshChips.run();
                relatedCombo.setValue(null);
            }
        });

        HBox relatedPicker = new HBox(10, relatedCombo, linkButton);
        relatedPicker.setAlignment(Pos.CENTER_LEFT);

        if (existing != null) {
            nameField.setText(existing.getName());
            if (existing.getDeadline() != null) {
                deadlineDateField.setValue(existing.getDeadline().toLocalDate());
            }
            statusCombo.setValue(existing.getStatus());
            descArea.setText(existing.getDescription());
            refreshHolder[0].run();
        }

        VBox content = new VBox(12);
        content.setPadding(new Insets(10, 4, 10, 4));
        content.getChildren().addAll(
                titleLabel, subtitleLabel,
                AetherDialogs.fieldLabel("Name"), nameField,
                AetherDialogs.fieldLabel("Deadline (optional)"), deadlineDateField,
                AetherDialogs.fieldLabel("Status"), statusCombo,
                AetherDialogs.fieldLabel("Description"), descArea,
                AetherDialogs.fieldLabel("Related People / Projects"), relatedPicker, relatedChips
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

            LocalDateTime deadline = deadlineDateField.getValue() != null
                    ? deadlineDateField.getValue().atStartOfDay()
                    : null;

            Project project = existing != null ? existing : new Project();
            project.setName(name);
            project.setDeadline(deadline);
            project.setStatus(statusCombo.getValue());
            project.setDescription(descArea.getText().trim());
            project.touch();
            return project;
        });

        return dialog;
    }

    /**
     * Remove a linha de um wikilink {@code [[Nome]]} específico do texto
     * (a linha inteira adicionada por {@link VaultManager#addWikilink}),
     * usada quando o utilizador clica num chip de relação para o retirar.
     *
     * @param text o texto atual da descrição
     * @param name o nome ligado a remover
     * @return o texto sem a linha desse wikilink
     */
    private static String removeWikilink(String text, String name) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String target = "[[" + name + "]]";
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.contains(target)) {
                continue;
            }
            result.append(line).append("\n");
        }
        // Remove um cabeçalho "## Related" que tenha ficado vazio.
        return result.toString()
                .replaceAll("(?m)^## Related\\s*\\n(?=\\s*(## |$))", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
