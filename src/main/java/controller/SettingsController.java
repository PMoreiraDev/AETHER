package controller;

import java.util.List;
import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import persistence.VaultManager;
import session.UserSession;
import util.OllamaService;
import util.I18n;
import util.AetherPreferences;
import util.BackupService;
import java.time.format.DateTimeFormatter;
import java.time.Instant;

/** Settings view for local AETHER AI model management. */
public class SettingsController {
    @FXML private VBox modelsContainer;
    @FXML private Label activeModelLabel;
    @FXML private Label statusLabel;
    @FXML private TextField vaultPathField;
    @FXML private TextField ollamaPathField;
    @FXML private Label ollamaPathStatus;
    @FXML private HBox languageSegmented;
    @FXML private Label lastBackupLabel;

    private static final String[] MODEL_IDS = {
            "qwen2.5:14b",
            "llama3.2:3b",
            "mistral:7b"
    };

    @FXML
    private void initialize() {
        refresh();
        refreshFolderPaths();
        populateAppearanceControls();
    }

    /** Preenche o controlo segmentado de idioma + info do último backup. */
    private void populateAppearanceControls() {
        buildLanguageSegmented();
        refreshLastBackupLabel();
    }

    /** Controlo segmentado de idioma: Português / English. */
    private void buildLanguageSegmented() {
        if (languageSegmented == null) return;
        languageSegmented.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        String lang = AetherPreferences.getLanguage();
        boolean pt = "pt".equalsIgnoreCase(lang) || lang.isBlank();
        ToggleButton ptBtn = segmentedButton(I18n.tr("settings.language.option.pt"), group, pt);
        ToggleButton enBtn = segmentedButton(I18n.tr("settings.language.option.en"), group, !pt);
        ptBtn.setOnAction(e -> {
            I18n.setLocale(new java.util.Locale("pt", "PT"));
            statusLabel.setText(I18n.tr("settings.language.changed"));
        });
        enBtn.setOnAction(e -> {
            I18n.setLocale(new java.util.Locale("en"));
            statusLabel.setText(I18n.tr("settings.language.changed"));
        });
        languageSegmented.getChildren().addAll(ptBtn, enBtn);
    }

    private ToggleButton segmentedButton(String text, ToggleGroup group, boolean selected) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.getStyleClass().add("aether-segmented-btn");
        btn.setSelected(selected);
        return btn;
    }

    private void refreshLastBackupLabel() {
        if (lastBackupLabel == null) return;
        try {
            var backups = java.nio.file.Files.list(BackupService.backupDirectory())
                    .filter(f -> f.toString().endsWith(".zip"))
                    .sorted((a, b) -> {
                        try {
                            return Long.compare(
                                    java.nio.file.Files.getLastModifiedTime(b).toMillis(),
                                    java.nio.file.Files.getLastModifiedTime(a).toMillis());
                        } catch (Exception e) { return 0; }
                    })
                    .toList();
            if (backups.isEmpty()) {
                lastBackupLabel.setText(I18n.tr("settings.backup.lastNone"));
            } else {
                var ts = java.nio.file.Files.getLastModifiedTime(backups.get(0)).toInstant();
                String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())
                        .format(Instant.ofEpochSecond(ts.getEpochSecond()));
                lastBackupLabel.setText(I18n.tr("settings.backup.lastDone", when));
            }
        } catch (Exception e) {
            lastBackupLabel.setText(I18n.tr("settings.backup.lastNone"));
        }
    }

    /** Idioma — aplicado imediatamente pelo controlo segmentado. */
    @FXML private void handleChangeLanguage() {
        statusLabel.setText(I18n.tr("settings.language.changed"));
    }

    /** Cria uma cópia de segurança zip do vault (em segundo plano). */
    @FXML private void handleCreateBackup() {
        statusLabel.setText(I18n.tr("settings.backup.creating"));
        Task<Path> task = new Task<>() {
            @Override protected Path call() throws Exception {
                return BackupService.createBackup();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.setText(I18n.tr("settings.backup.created", task.getValue().getFileName().toString()));
            refreshLastBackupLabel();
        });
        task.setOnFailed(e -> {
            Throwable err = task.getException();
            statusLabel.setText(I18n.tr("settings.backup.failed",
                    err != null && err.getMessage() != null ? err.getMessage() : "erro"));
        });
        Thread t = new Thread(task, "aether-backup");
        t.setDaemon(true);
        t.start();
    }

    /** Abre a pasta das cópias de segurança no gestor de ficheiros. */
    @FXML private void handleOpenBackupsFolder() {
        openInFileExplorer(BackupService.backupDirectory(), false);
    }

    /** Reloads model state from Ollama and the persisted application settings. */
    @FXML
    private void handleRefresh() {
        refresh();
    }

    private void refresh() {
        String active = UserSession.getInstance().getAppSettings().getActiveModelId();
        activeModelLabel.setText(active == null || active.isBlank() ? I18n.tr("settings.activeModel.none") : active);
        modelsContainer.getChildren().clear();

        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() {
                return OllamaService.getInstalledModels();
            }
        };
        task.setOnSucceeded(e -> render(task.getValue(), active));
        task.setOnFailed(e -> render(List.of(), active));
        Thread t = new Thread(task, "aether-settings-models");
        t.setDaemon(true);
        t.start();
    }

    private void render(List<String> installed, String active) {
        for (String modelId : MODEL_IDS) {
            boolean downloaded = installed.stream()
                    .map(OllamaService::normalizeModelId)
                    .anyMatch(OllamaService.normalizeModelId(modelId)::equals);
            boolean selected = active != null
                    && OllamaService.normalizeModelId(active).equals(OllamaService.normalizeModelId(modelId));
            modelsContainer.getChildren().add(createModelCard(modelId, downloaded, selected));
        }
    }

    private VBox createModelCard(String modelId, boolean downloaded, boolean selected) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(16));
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("settings-model-card");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox names = new VBox(3);
        Label name = new Label(displayName(modelId));
        name.getStyleClass().add("settings-model-name");
        Label id = new Label(modelId);
        id.getStyleClass().add("settings-model-id");
        names.getChildren().addAll(name, id);
        HBox.setHgrow(names, javafx.scene.layout.Priority.ALWAYS);

        // Estado humanizado: não mostrar "Download" quando já está instalado;
        // o utilizador nunca vê "ACTIVE/DOWNLOADED" em maiúsculas cru.
        String stateKey = selected ? "settings.model.state.selected"
                : downloaded ? "settings.model.state.installed"
                : "settings.model.state.available";
        Label badge = new Label(I18n.tr(stateKey));
        badge.getStyleClass().addAll("model-state",
                selected ? "model-state-selected"
                        : downloaded ? "model-state-installed" : "model-state-available");
        top.getChildren().addAll(names, badge);

        Label description = new Label(description(modelId));
        description.setWrapText(true);
        description.getStyleClass().add("settings-model-description");

        Button action = new Button(selected ? I18n.tr("settings.model.state.selected")
                : downloaded ? I18n.tr("settings.model.action.use") : I18n.tr("settings.model.action.download"));
        // [CORRIGIDO] "getStyleClass().add("a b")" adiciona UMA classe com espaço,
        // que o JavaFX não separa — os seletores .aether-btn / .aether-btn-primary
        // não aplicavam e o botão ficava com o visual Modena (desformatado).
        // Tem de ser addAll com classes separadas.
        if (selected) {
            action.getStyleClass().addAll("aether-btn", "aether-btn-secondary");
        } else {
            action.getStyleClass().addAll("aether-btn", "aether-btn-primary");
        }
        action.setDisable(selected);

        // Barra de progresso e label de percentagem, escondidas por omissão e
        // só mostradas (no lugar do botão) enquanto este modelo em concreto
        // está a ser transferido. Usa o mesmo "custom-progress-bar" do resto
        // da app (ecrã de setup), para o download ficar visualmente coerente.
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.getStyleClass().add("custom-progress-bar");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setManaged(false);
        progressBar.setVisible(false);
        HBox.setHgrow(progressBar, javafx.scene.layout.Priority.ALWAYS);

        Label percentLabel = new Label();
        percentLabel.getStyleClass().add("settings-download-percent");
        percentLabel.setManaged(false);
        percentLabel.setVisible(false);
        percentLabel.setMinWidth(40);
        percentLabel.setAlignment(Pos.CENTER_RIGHT);

        HBox progressRow = new HBox(10, progressBar, percentLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressRow, javafx.scene.layout.Priority.ALWAYS);

        action.setOnAction(e -> {
            if (downloaded) {
                selectModel(modelId);
            } else {
                downloadModel(modelId, action, progressBar, percentLabel);
            }
        });

        HBox bottom = new HBox(10, progressRow, action);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(progressRow, javafx.scene.layout.Priority.ALWAYS);
        card.getChildren().addAll(top, description, bottom);
        return card;
    }

    // ------------------------------------------------------------------
    // Folders (vault + Ollama)
    // ------------------------------------------------------------------

    /**
     * Preenche os campos de caminho com o vault atual e o executável Ollama
     * detetado (ou o override guardado).
     */
    private void refreshFolderPaths() {
        if (vaultPathField != null) {
            vaultPathField.setText(VaultManager.getVaultPath().toString());
        }
        if (ollamaPathField != null) {
            UserSession session = UserSession.getInstance();
            String override = session.getAppSettings().getOllamaPathOverride();
            if (override != null && !override.isBlank()) {
                ollamaPathField.setText(override);
            } else {
                String exe = OllamaService.findOllamaExecutable();
                ollamaPathField.setText(exe != null ? exe : "");
            }
            updateOllamaPathStatus();
        }
    }

    /** Atualiza o pequeno label de estado por baixo do campo do Ollama. */
    private void updateOllamaPathStatus() {
        if (ollamaPathStatus == null) {
            return;
        }
        String exe = OllamaService.findOllamaExecutable();
        ollamaPathStatus.setText(
                exe != null ? "Ollama found: " + exe
                        : "Ollama not found. Install it or pick the path above.");
    }

    /** Abre um seletor de pasta para escolher a nova localização do vault. */
    @FXML
    private void handleBrowseVaultFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose AETHER Vault Folder");
        File current = new File(vaultPathField.getText().trim());
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File selected = chooser.showDialog(vaultPathField.getScene().getWindow());
        if (selected != null) {
            vaultPathField.setText(selected.getAbsolutePath());
        }
    }

    /** Guarda o caminho do vault nas definições e cria a estrutura de pastas. */
    @FXML
    private void handleSaveVaultPath() {
        String path = vaultPathField.getText().trim();
        UserSession session = UserSession.getInstance();
        session.getAppSettings().setVaultPathOverride(path);
        if (!session.saveAppSettings()) {
            statusLabel.setText("Could not save the vault path.");
            return;
        }
        // Cria as subpastas na nova localização para o vault ficar pronto.
        VaultManager.initializeVault();
        statusLabel.setText("Vault folder set to " + VaultManager.getVaultPath() + ".");
    }

    /** Abre a pasta do vault no gestor de ficheiros do sistema. */
    @FXML
    private void handleOpenVaultFolder() {
        openInFileExplorer(VaultManager.getVaultPath(), true);
    }

    /** Abre um seletor de pasta para escolher a pasta do Ollama. */
    @FXML
    private void handleBrowseOllamaPath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose Ollama Folder");
        File current = new File(ollamaPathField.getText().trim());
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        } else if (current.isFile() && current.getParentFile() != null) {
            chooser.setInitialDirectory(current.getParentFile());
        }
        File selected = chooser.showDialog(ollamaPathField.getScene().getWindow());
        if (selected != null) {
            ollamaPathField.setText(selected.getAbsolutePath());
        }
    }

    /** Guarda o caminho do Ollama nas definições, validando antes. */
    @FXML
    private void handleSaveOllamaPath() {
        String path = ollamaPathField.getText().trim();
        if (!path.isBlank() && !OllamaService.isValidOllamaPath(path)) {
            ollamaPathStatus.setText(
                    "Could not find an executable Ollama binary at that path. "
                            + "Pick the ollama executable or its containing folder.");
            return;
        }
        UserSession session = UserSession.getInstance();
        session.getAppSettings().setOllamaPathOverride(path);
        if (!session.saveAppSettings()) {
            statusLabel.setText("Could not save the Ollama path.");
            return;
        }
        statusLabel.setText(path.isBlank() ? "Ollama path reset to auto-detect." : "Ollama path saved.");
        updateOllamaPathStatus();
    }

    /** Abre a pasta do executável do Ollama no gestor de ficheiros. */
    @FXML
    private void handleOpenOllamaFolder() {
        String exe = OllamaService.findOllamaExecutable();
        if (exe != null) {
            openInFileExplorer(Paths.get(exe), true);
        } else {
            statusLabel.setText("Ollama executable not found.");
        }
    }

    /**
     * Abre um caminho no gestor de ficheiros do sistema operativo.
     *
     * @param path          o caminho a abrir
     * @param openParentFolder se {@code true}, abre a pasta pai quando o
     *                          caminho aponta para um ficheiro
     */
    private void openInFileExplorer(Path path, boolean openParentFolder) {
        File target = path.toFile();
        if (!target.exists()) {
            statusLabel.setText("Path does not exist: " + path);
            return;
        }
        if (target.isFile() && openParentFolder) {
            target = target.getParentFile();
        }
        try {
            Desktop.getDesktop().open(target);
        } catch (Exception e) {
            statusLabel.setText("Could not open folder: " + e.getMessage());
        }
    }

    private void selectModel(String modelId) {
        UserSession session = UserSession.getInstance();
        session.getAppSettings().setActiveModelId(modelId);
        if (session.saveAppSettings()) {
            activeModelLabel.setText(modelId);
            statusLabel.setText("Active model changed to " + modelId + ".");
            refresh();
        } else {
            statusLabel.setText("Could not save the selected model.");
        }
    }

    /**
     * Transfere um modelo, mostrando o progresso real na própria card em vez
     * de apenas um texto de estado.
     * <p>
     * Enquanto a transferência decorre, o botão "Download" desta card é
     * escondido e substituído por uma barra de progresso com percentagem,
     * ligada ao progresso do {@link Task} através de
     * {@code progressProperty()} — é assim que {@link OllamaService} entrega
     * as atualizações de progresso ao fio da interface de forma segura.
     * </p>
     */
    private void downloadModel(String modelId, Button action, ProgressBar progressBar, Label percentLabel) {
        statusLabel.setText("Downloading " + modelId + "...");

        action.setVisible(false);
        action.setManaged(false);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        percentLabel.setVisible(true);
        percentLabel.setManaged(true);
        percentLabel.setText("");

        Task<Boolean> task = new Task<>() {
            @Override protected Boolean call() {
                return OllamaService.downloadModel(modelId, fraction -> updateProgress(fraction, 1.0));
            }
        };

        percentLabel.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    double p = task.getProgress();
                    return p >= 0 ? Math.round(p * 100) + "%" : "";
                },
                task.progressProperty()));
        progressBar.progressProperty().bind(task.progressProperty());

        task.setOnSucceeded(e -> {
            percentLabel.textProperty().unbind();
            progressBar.progressProperty().unbind();
            if (Boolean.TRUE.equals(task.getValue())) {
                UserSession session = UserSession.getInstance();
                session.getAppSettings().setActiveModelId(modelId);
                boolean saved = session.saveAppSettings();
                statusLabel.setText(saved ? modelId + " downloaded and selected." : modelId + " downloaded, but could not save selection.");
            } else {
                statusLabel.setText("Could not download " + modelId + ".");
            }
            refresh();
        });
        task.setOnFailed(e -> {
            percentLabel.textProperty().unbind();
            progressBar.progressProperty().unbind();
            statusLabel.setText("Could not download " + modelId + ".");
            refresh();
        });

        Thread t = new Thread(task, "aether-model-download");
        t.setDaemon(true);
        t.start();
    }

    private String displayName(String id) {
        return switch (id) {
            case "qwen2.5:14b" -> "Qwen 2.5 · 14B";
            case "llama3.2:3b" -> "Llama 3.2 · 3B";
            case "mistral:7b" -> "Mistral · 7B";
            default -> id;
        };
    }

    private String description(String id) {
        return switch (id) {
            case "qwen2.5:14b" -> "High-capability local model for reasoning, writing and general assistance.";
            case "llama3.2:3b" -> "Lightweight and fast model for everyday local conversations.";
            case "mistral:7b" -> "Balanced local model with strong general-purpose performance.";
            default -> "Local Ollama model.";
        };
    }
}
