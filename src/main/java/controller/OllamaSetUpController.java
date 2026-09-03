package controller;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import session.UserSession;
import util.Navigator;
import util.OllamaService;
import util.StyleUtils;
import util.SystemInfo;
/**
 * Controlador do passo 2 do setup — configuração do Ollama
 * ({@code ollama_setup.fxml}).
 * <p>
 * Este é o último passo do setup de 2 passos:
 * </p>
 * <ol>
 *   <li>Perfil do utilizador ({@code profile.fxml})</li>
 *   <li>Configuração do Ollama ({@code ollama_setup.fxml}) — este controlador</li>
 * </ol>
 * <p>
 * Deteta o hardware disponível, recomenda modelos compatíveis, instala o motor
 * local do Ollama e gere a transferência e remoção de modelos. Todas as
 * operações lentas correm em tarefas de fundo ({@link Task}), para que a
 * interface nunca bloqueie.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.3
 */
public class OllamaSetUpController {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public OllamaSetUpController() {
        // Construtor por omissão explícito, documentado para o Javadoc.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(OllamaSetUpController.class.getName());

    /** Classe CSS base do badge de estado, sempre presente. */
    private static final String BADGE_BASE_CLASS = "status-badge";

    /** Classe CSS do badge no estado de sucesso. */
    private static final String BADGE_SUCCESS_CLASS = "status-badge-sucesso";

    /** Classe CSS do badge no estado de erro. */
    private static final String BADGE_ERROR_CLASS = "status-badge-erro";

    /** Classe CSS do indicador circular em espera. */
    private static final String DOT_PENDING_CLASS = "indicador-pendente";

    /** Classe CSS do indicador circular de sucesso. */
    private static final String DOT_SUCCESS_CLASS = "indicador-sucesso";

    /** Classe CSS do indicador circular de erro. */
    private static final String DOT_ERROR_CLASS = "indicador-erro";

    /** Texto apresentado na percentagem quando o progresso é indeterminado. */
    private static final String PROGRESS_BUSY_TEXT = "···";

    /** Caminho do ecrã do passo 1, alcançável através do botão "Previous". */
    private static final String PROFILE_VIEW = "/FXML/profile.fxml";

    /**
     * Caminho do ecrã de dashboard, apresentado após conclusão do onboarding.
     * <p>
     * Quando o utilizador conclui o passo 2 (instalação do Ollama e seleção do
     * modelo), o botão "Next" navega para o dashboard principal do AETHER.
     * </p>
     */
    private static final String NEXT_STEP_VIEW = "/FXML/obsidian_setup.fxml";

    /** Painel de raiz do ecrã, usado como origem da navegação. */
    @FXML
    private StackPane rootPane;

    /** Imagem de fundo, dimensionada para acompanhar a janela. */
    @FXML
    private ImageView backgroundImageView;

    /** Botão que avança para o passo seguinte do onboarding. */
    @FXML
    private Button nextStepButton;

    /** Botão que instala o motor do Ollama. */
    @FXML
    private Button installOllamaButton;

    /** Botão que transfere o modelo selecionado. */
    @FXML
    private Button downloadModelButton;

    /** Botão que recarrega a lista de modelos instalados. */
    @FXML
    private Button refreshModelsButton;

    /** Botão que remove o modelo selecionado na lista. */
    @FXML
    private Button uninstallModelButton;

    /** Seletor de modelos recomendados para o hardware detetado. */
    @FXML
    private ComboBox<String> modelComboBox;

    /** Lista dos modelos já presentes no disco. */
    @FXML
    private ListView<String> installedModelsListView;

    /** Mensagem de estado apresentada no rodapé. */
    @FXML
    private Label statusLabel;

    /** Badge textual com o estado do motor do Ollama. */
    @FXML
    private Label serviceStatusBadge;

    /** Badge informativo com a memória detetada no sistema. */
    @FXML
    private Label ramHardwareBadge;

    /** Percentagem apresentada junto à barra de progresso. */
    @FXML
    private Label progressPercentLabel;

    /** Barra de progresso das operações longas. */
    @FXML
    private ProgressBar progressBar;

    /** Indicador circular de atividade no rodapé. */
    @FXML
    private Circle statusIndicatorDot;

    /** Indicador circular do estado do motor do Ollama. */
    @FXML
    private Circle engineStatusDot;

    /** Sufixo acrescentado à descrição de um modelo já presente no disco. */
    private static final String INSTALLED_SUFFIX = "  • installed";

    /** Modelos recomendados: identificador do modelo para descrição legível. */
    private Map<String, String> recommendedModels = Map.of();

    /**
     * Identificadores normalizados dos modelos presentes no disco.
     * <p>
     * Serve apenas para <em>marcar</em> os modelos já instalados na lista de
     * recomendações. A lista de recomendações em si nunca é filtrada: um modelo
     * transferido não desaparece nem faz a sugestão mudar por baixo do
     * utilizador.
     * </p>
     */
    private final Set<String> installedModelIds = new LinkedHashSet<>();

    /**
     * Prepara o ecrã: deteta o hardware, carrega as recomendações de modelos e
     * verifica se o motor do Ollama já está instalado.
     */
    @FXML
    public void initialize() {
        if (backgroundImageView != null && rootPane != null) {
            backgroundImageView.fitWidthProperty().bind(rootPane.widthProperty());
            backgroundImageView.fitHeightProperty().bind(rootPane.heightProperty());
        }

        prepareNextStepButton();
        setBusy(false);
        uninstallModelButton.setDisable(true);

        installedModelsListView.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> uninstallModelButton.setDisable(newValue == null));
        installedModelsListView.setPlaceholder(new Label("No models installed yet."));

        showDetectedRam();
        loadRecommendedModels();
        detectEngine();
    }

    /**
     * Prepares the "Next" button for the final onboarding step.
     * <p>
     * The dashboard screen is now implemented, so the button navigates directly
     * to the main AETHER dashboard after saving the selected model.
     * </p>
     */
    private void prepareNextStepButton() {
        nextStepButton.setTooltip(new Tooltip("Open the AETHER dashboard"));
    }

    /**
     * Volta ao passo 1 do onboarding, permitindo rever ou corrigir o perfil.
     * <p>
     * Os dados do perfil ficam guardados em {@link session.UserSession}, pelo que
     * o ecrã anterior reaparece já preenchido.
     * </p>
     */
    @FXML
    private void handlePrevious() {
        Navigator.navigate(rootPane, PROFILE_VIEW);
    }

    /**
     * Saves the selected Ollama model, marks onboarding as complete, and opens
     * the AETHER dashboard.
     */
    @FXML
    private void handleNext() {
        String selectedModel = modelComboBox.getValue();

        if (selectedModel == null || selectedModel.isBlank()) {
            setStatus(
                    "Select a model before continuing.",
                    DOT_ERROR_CLASS
            );
            return;
        }

        UserSession session = UserSession.getInstance();

        session.getAppSettings().setActiveModelId(selectedModel);
        session.getAppSettings().setOnboardingCompleted(true);

        if (!session.saveAppSettings()) {
            setStatus(
                    "Couldn't save the selected model.",
                    DOT_ERROR_CLASS
            );
            return;
        }

        LOGGER.info(() -> "Onboarding complete. Model: " + selectedModel);

        Navigator.navigate(rootPane, NEXT_STEP_VIEW);
    }

    /**
     * Preenche o badge de hardware com a memória física detetada.
     */
    private void showDetectedRam() {
        long ramGb = SystemInfo.getTotalRamGb();
        ramHardwareBadge.setText(ramGb == SystemInfo.UNKNOWN_RAM ? "RAM: n/a" : "RAM: " + ramGb + " GB");
    }

    /**
     * Carrega no seletor os modelos recomendados para o hardware atual e mostra
     * a descrição legível de cada um em vez do identificador técnico.
     */
    private void loadRecommendedModels() {
        recommendedModels = OllamaService.getRecommendedModels();
        modelComboBox.setItems(FXCollections.observableArrayList(recommendedModels.keySet()));
        modelComboBox.setButtonCell(createModelCell());
        modelComboBox.setCellFactory(listView -> createModelCell());

        modelComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateDownloadButton());

        selectSuggestedModel();
    }

    /**
     * Cria uma célula que apresenta a descrição amigável de um modelo e assinala
     * os que já estão presentes no disco.
     *
     * @return a célula configurada para o seletor de modelos
     */
    private ListCell<String> createModelCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String modelId, boolean empty) {
                super.updateItem(modelId, empty);
                if (empty || modelId == null) {
                    setText(null);
                    return;
                }
                String description = recommendedModels.getOrDefault(modelId, modelId);
                setText(isInstalled(modelId) ? description + INSTALLED_SUFFIX : description);
            }
        };
    }

    /**
     * Indica se um modelo recomendado já se encontra no disco.
     *
     * @param modelId o identificador do modelo a testar
     * @return {@code true} se o modelo estiver instalado
     */
    private boolean isInstalled(String modelId) {
        return installedModelIds.contains(OllamaService.normalizeModelId(modelId));
    }

    /**
     * Seleciona a sugestão por omissão: o primeiro modelo recomendado que ainda
     * não esteja instalado.
     * <p>
     * A seleção só muda enquanto o utilizador não tiver escolhido nada. Depois
     * de haver uma escolha, esta é respeitada — a lista nunca se reorganiza
     * sozinha durante uma transferência.
     * </p>
     */
    private void selectSuggestedModel() {
        if (modelComboBox.getItems().isEmpty() || modelComboBox.getValue() != null) {
            updateDownloadButton();
            return;
        }

        String savedModel = UserSession.getInstance().getAppSettings().getActiveModelId();
        if (savedModel != null && !savedModel.isBlank() && modelComboBox.getItems().contains(savedModel)) {
            modelComboBox.getSelectionModel().select(savedModel);
            updateDownloadButton();
            return;
        }

        String suggestion = modelComboBox.getItems().stream()
                .filter(modelId -> !isInstalled(modelId))
                .findFirst()
                .orElse(modelComboBox.getItems().get(0));

        modelComboBox.getSelectionModel().select(suggestion);
        updateDownloadButton();
    }

    /**
     * Ajusta o botão de transferência ao modelo selecionado.
     * <p>
     * Se o modelo já estiver no disco, o botão passa a "Installed" e fica
     * desativado, para evitar transferências repetidas do mesmo modelo.
     * </p>
     */
    private void updateDownloadButton() {
        String selected = modelComboBox.getValue();
        boolean engineMissing = installOllamaButton != null && !installOllamaButton.isDisabled();
        boolean alreadyInstalled = selected != null && isInstalled(selected);

        downloadModelButton.setText(alreadyInstalled ? "Installed" : "Download");
        downloadModelButton.setDisable(alreadyInstalled || engineMissing || selected == null);
    }

    /**
     * Verifica se o executável do Ollama existe e ajusta a interface em função disso.
     */
    private void detectEngine() {
        if (OllamaService.findOllamaExecutable() != null) {
            setEngineStatus(true, "Installed");
            setStatus("Ollama engine detected on this system.", DOT_SUCCESS_CLASS);
            installOllamaButton.setDisable(true);
            updateDownloadButton();
            loadInstalledModels();
        } else {
            setEngineStatus(false, "Not installed");
            setStatus("The Ollama engine must be installed first.", DOT_ERROR_CLASS);
            installOllamaButton.setDisable(false);
            updateDownloadButton();
        }
    }

    /**
     * Atualiza o badge e o indicador circular do estado do motor do Ollama.
     * <p>
     * A classe base do badge é sempre mantida: apenas a variante de cor é
     * substituída, para que o badge nunca perca a sua forma e tipografia.
     * </p>
     *
     * @param installed {@code true} se o motor estiver disponível
     * @param text o texto a apresentar no badge
     */
    private void setEngineStatus(boolean installed, String text) {
        serviceStatusBadge.setText(text);

        serviceStatusBadge.getStyleClass().removeAll(BADGE_SUCCESS_CLASS, BADGE_ERROR_CLASS);
        if (!serviceStatusBadge.getStyleClass().contains(BADGE_BASE_CLASS)) {
            serviceStatusBadge.getStyleClass().add(BADGE_BASE_CLASS);
        }
        serviceStatusBadge.getStyleClass().add(installed ? BADGE_SUCCESS_CLASS : BADGE_ERROR_CLASS);

        setDotState(engineStatusDot, installed ? DOT_SUCCESS_CLASS : DOT_ERROR_CLASS);
    }

    /**
     * Define a mensagem de estado do rodapé e a cor do indicador de atividade.
     *
     * @param message a mensagem a apresentar
     * @param dotStyleClass a classe CSS do indicador circular
     */
    private void setStatus(String message, String dotStyleClass) {
        statusLabel.setText(message);
        setDotState(statusIndicatorDot, dotStyleClass);
    }

    /**
     * Substitui a variante de cor de um indicador circular.
     *
     * @param dot o indicador a atualizar; ignorado se for {@code null}
     * @param styleClass a classe CSS da variante a aplicar
     */
    private void setDotState(Circle dot, String styleClass) {
        if (dot == null) {
            return;
        }
        dot.getStyleClass().removeAll(DOT_PENDING_CLASS, DOT_SUCCESS_CLASS, DOT_ERROR_CLASS);
        dot.getStyleClass().add(styleClass);
    }

    /**
     * Mostra ou esconde o indicador de progresso indeterminado e bloqueia as
     * ações que não podem correr em paralelo.
     *
     * @param busy {@code true} para entrar em modo ocupado
     */
    private void setBusy(boolean busy) {
        progressBar.setVisible(busy);
        progressBar.setProgress(busy ? ProgressIndicator.INDETERMINATE_PROGRESS : 0);

        // A percentagem acompanha a barra: escondida em repouso, mas mantendo o
        // espaco reservado para o rodape nao "saltar" ao iniciar uma tarefa.
        progressPercentLabel.setVisible(busy);
        progressPercentLabel.setText(busy ? PROGRESS_BUSY_TEXT : "0%");
        refreshModelsButton.setDisable(busy);
        modelComboBox.setDisable(busy);
    }

    /**
     * Instala o motor do Ollama numa tarefa de fundo.
     */
    @FXML
    private void handleInstallOllama() {
        setStatus("Installing the Ollama engine...", DOT_PENDING_CLASS);
        installOllamaButton.setDisable(true);
        setBusy(true);

        runTask(OllamaService::installOllama,
                success -> {
                    if (success) {
                        setEngineStatus(true, "Installed");
                        setStatus("Ollama installed successfully.", DOT_SUCCESS_CLASS);
                        updateDownloadButton();
                        loadInstalledModels();
                    } else {
                        setStatus("Installation failed. Check your system permissions.", DOT_ERROR_CLASS);
                        installOllamaButton.setDisable(false);
                    }
                },
                () -> {
                    setStatus("Unexpected error during installation.", DOT_ERROR_CLASS);
                    installOllamaButton.setDisable(false);
                });
    }

    /**
     * Transfere o modelo selecionado numa tarefa de fundo.
     */
    @FXML
    private void handleDownloadModel() {
        String selectedModel = modelComboBox.getValue();
        if (selectedModel == null) {
            setStatus("Select a model before downloading.", DOT_ERROR_CLASS);
            return;
        }

        setStatus("Downloading model " + selectedModel + "...", DOT_PENDING_CLASS);
        downloadModelButton.setDisable(true);
        setBusy(true);

        runTask(() -> OllamaService.downloadModel(selectedModel),
                success -> {
                    if (success) {
                        UserSession.getInstance().getAppSettings().setActiveModelId(selectedModel);
                        if (!UserSession.getInstance().saveAppSettings()) {
                            setStatus("Model installed, but its selection could not be saved.", DOT_ERROR_CLASS);
                            updateDownloadButton();
                            return;
                        }
                        setStatus("Model " + selectedModel + " is ready to use.", DOT_SUCCESS_CLASS);
                        // A lista de instalados é recarregada: o modelo passa a
                        // aparecer marcado como "installed" na recomendação, sem
                        // sair da lista nem alterar a seleção do utilizador.
                        loadInstalledModels();
                    } else {
                        setStatus("Failed to download " + selectedModel + ".", DOT_ERROR_CLASS);
                        updateDownloadButton();
                    }
                },
                () -> {
                    setStatus("Unexpected error while downloading the model.", DOT_ERROR_CLASS);
                    updateDownloadButton();
                });
    }

    /**
     * Recarrega, a pedido do utilizador, a lista de modelos presentes no disco.
     * <p>
     * Entra explicitamente em modo ocupado, para que a barra de progresso mostre
     * que a verificação está a decorrer. O estado final é sempre escrito por
     * {@link #loadInstalledModels()}, com sucesso, com falha ou por tempo limite.
     * </p>
     */
    @FXML
    private void handleRefreshModels() {
        setStatus("Checking installed models...", DOT_PENDING_CLASS);
        setBusy(true);
        loadInstalledModels(true);
    }

    /**
     * Remove o modelo selecionado na lista, após confirmação do utilizador.
     */
    @FXML
    private void handleUninstallModel() {
        String selectedModel = installedModelsListView.getSelectionModel().getSelectedItem();
        if (selectedModel == null) {
            return;
        }

        if (!confirmRemoval(selectedModel)) {
            return;
        }

        setStatus("Removing model " + selectedModel + "...", DOT_PENDING_CLASS);
        uninstallModelButton.setDisable(true);
        setBusy(true);

        runTask(() -> OllamaService.uninstallModel(selectedModel),
                success -> {
                    if (success) {
                        setStatus("Model " + selectedModel + " removed.", DOT_SUCCESS_CLASS);
                    } else {
                        setStatus("Failed to remove " + selectedModel + ".", DOT_ERROR_CLASS);
                    }
                    loadInstalledModels();
                },
                () -> setStatus("Unexpected error while removing the model.", DOT_ERROR_CLASS));
    }

    /**
     * Pede confirmação antes de remover um modelo do disco.
     *
     * @param modelId o identificador do modelo a remover
     * @return {@code true} se o utilizador confirmou a remoção
     */
    private boolean confirmRemoval(String modelId) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove model");
        confirm.setHeaderText(null);
        confirm.setContentText("Remove \"" + modelId + "\" from disk? This action cannot be undone.");

        StyleUtils.applyTo(confirm.getDialogPane());
        confirm.getDialogPane().getStyleClass().add("custom-dialog-pane");

        Optional<ButtonType> response = confirm.showAndWait();
        return response.isPresent() && response.get() == ButtonType.OK;
    }

    /**
     * Recarrega a lista de modelos instalados sem alterar a mensagem de estado.
     * <p>
     * Usado depois de uma instalação, transferência ou remoção, para que a
     * confirmação dessa operação continue visível.
     * </p>
     */
    private void loadInstalledModels() {
        loadInstalledModels(false);
    }

    /**
     * Recarrega a lista de modelos instalados numa tarefa de fundo.
     * <p>
     * Uma verificação que exceda o tempo limite devolve
     * {@link Optional#empty()} e é comunicada como erro, em vez de ser
     * confundida com "nenhum modelo instalado".
     * </p>
     *
     * @param announceResult {@code true} para escrever no rodapé o resultado da
     *        verificação; {@code false} para preservar a mensagem atual
     */
    private void loadInstalledModels(boolean announceResult) {
        Task<Optional<List<String>>> loadModelsTask = new Task<>() {
            @Override
            protected Optional<List<String>> call() {
                return OllamaService.tryGetInstalledModels();
            }
        };

        loadModelsTask.setOnSucceeded(event -> {
            setBusy(false);
            Optional<List<String>> result = loadModelsTask.getValue();

            if (result.isEmpty()) {
                LOGGER.warning("Verificação dos modelos sem resultado: erro ou tempo limite.");
                setStatus("Could not check the installed models. Is the Ollama service running?",
                        DOT_ERROR_CLASS);
                return;
            }

            applyInstalledModels(result.get());

            if (announceResult) {
                setStatus(describeInstalledModels(result.get().size()),
                        result.get().isEmpty() ? DOT_PENDING_CLASS : DOT_SUCCESS_CLASS);
            }
        });

        loadModelsTask.setOnFailed(event -> {
            setBusy(false);
            LOGGER.warning(() -> "Não foi possível listar os modelos instalados: "
                    + loadModelsTask.getException());
            setStatus("Could not check the installed models. Is the Ollama service running?",
                    DOT_ERROR_CLASS);
        });

        startDaemon(loadModelsTask);
    }

    /**
     * Reflete na interface a lista de modelos encontrada no disco.
     *
     * @param models os nomes dos modelos instalados
     */
    private void applyInstalledModels(List<String> models) {
        installedModelsListView.setItems(FXCollections.observableArrayList(models));
        uninstallModelButton.setDisable(true);

        installedModelIds.clear();
        models.stream().map(OllamaService::normalizeModelId).forEach(installedModelIds::add);

        // Redesenha as células para que as marcas "installed" fiquem atuais.
        modelComboBox.setButtonCell(createModelCell());
        modelComboBox.setCellFactory(listView -> createModelCell());
        selectSuggestedModel();
        updateDownloadButton();
    }

    /**
     * Constrói a mensagem de estado que descreve o resultado da verificação.
     * <p>
     * Existe para garantir que a mensagem "Checking installed models..." é
     * <em>sempre</em> substituída quando a verificação termina. Antes, o estado
     * apenas era reescrito quando a lista vinha vazia, pelo que com modelos
     * instalados o ecrã ficava indefinidamente a dizer "Checking..." apesar de a
     * operação já ter terminado.
     * </p>
     *
     * @param count o número de modelos encontrados no disco
     * @return a mensagem a apresentar ao utilizador
     */
    private String describeInstalledModels(int count) {
        if (count == 0) {
            return "No models installed yet.";
        }
        return count == 1 ? "1 model installed on this device." : count + " models installed on this device.";
    }

    /**
     * Executa uma operação booleana em segundo plano e trata o resultado no fio
     * da interface, garantindo que o modo ocupado é sempre desligado.
     *
     * @param work a operação a executar fora do fio da interface
     * @param onResult ação a executar com o resultado da operação
     * @param onError ação a executar se a operação lançar uma exceção
     */
    private void runTask(BooleanWork work, java.util.function.Consumer<Boolean> onResult, Runnable onError) {
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return work.run();
            }
        };

        task.setOnSucceeded(event -> {
            setBusy(false);
            onResult.accept(Boolean.TRUE.equals(task.getValue()));
        });

        task.setOnFailed(event -> {
            setBusy(false);
            LOGGER.warning(() -> "Tarefa de fundo falhou: " + task.getException());
            onError.run();
        });

        startDaemon(task);
    }

    /**
     * Arranca uma tarefa num fio daemon, para que nunca impeça o encerramento
     * da aplicação.
     *
     * @param task a tarefa a executar
     */
    private void startDaemon(Task<?> task) {
        Thread thread = new Thread(task, "aether-ollama-worker");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Operação de fundo que devolve um resultado de sucesso ou falha.
     */
    @FunctionalInterface
    private interface BooleanWork {

        /**
         * Executa a operação.
         *
         * @return {@code true} se a operação teve sucesso
         */
        boolean run();
    }
}