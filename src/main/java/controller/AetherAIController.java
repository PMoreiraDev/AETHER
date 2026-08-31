package controller;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import session.UserSession;
import util.ContextManager;
import util.OllamaService;

/**
 * Controlador da vista de chat AETHER AI ({@code aether_ai.fxml}).
 * <p>
 * Apresenta uma interface de conversa moderna onde o utilizador pode falar
 * diretamente com o modelo de IA local (Ollama) que selecionou durante o
 * setup. As mensagens são apresentadas em bubbles alinhadas à direita
 * (utilizador) e à esquerda (AETHER).
 * </p>
 * <p>
 * A comunicação com o Ollama é feita através da API REST em
 * {@code http://localhost:11434/api/generate}, numa tarefa de fundo para
 * não bloquear a interface.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class AetherAIController implements Initializable {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public AetherAIController() {
        // Construtor por omissão explícito.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(AetherAIController.class.getName());

    // ------------------------------------------------------------------
    // FXML
    // ------------------------------------------------------------------

    /** Container onde as mensagens (bubbles) são adicionadas. */
    @FXML private VBox messagesContainer;

    /** ScrollPane que envolve as mensagens. */
    @FXML private ScrollPane chatScroll;

    /** Campo de input para o utilizador escrever. */
    @FXML private TextField inputField;

    /** Botão de envio da mensagem. */
    @FXML private Button sendButton;

    /** Label que mostra o modelo ativo. */
    @FXML private Label modelLabel;

    /** Label que mostra o estado (Ready, Thinking...). */
    @FXML private Label statusLabel;

    /** Ponto de estado (verde = pronto, amarelo = a pensar). */
    @FXML private Circle statusDot;

    /** Indica se está à espera de resposta do modelo. */
    private boolean awaitingResponse = false;

    /**
     * Modelo ativo de facto, resolvido em {@link #warmUpEngine} contra o que
     * está realmente instalado — pode diferir do valor guardado em
     * {@link domain.AppSettings#getActiveModelId()} se esse modelo não tiver
     * sido transferido para este dispositivo.
     */
    private String activeModelId;

    // ------------------------------------------------------------------
    // Inicialização
    // ------------------------------------------------------------------

    /**
     * Inicializa a vista do chat.
     * <p>
     * Verifica se há modelo configurado e mostra uma mensagem de boas-vindas.
     * </p>
     *
     * @param location o URL do FXML carregado
     * @param resources o pacote de recursos de localização
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        UserSession session = UserSession.getInstance();
        String modelId = session.getAppSettings().getActiveModelId();

        if (modelId != null && !modelId.isBlank()) {
            activeModelId = modelId;
            modelLabel.setText("Local model: " + modelId);
            addWelcomeMessage();
            warmUpEngine(modelId);
        } else {
            modelLabel.setText("No model configured");
            statusLabel.setText("Offline");
            statusDot.getStyleClass().setAll("indicador-erro");
            inputField.setDisable(true);
            sendButton.setDisable(true);
            inputField.setPromptText("Complete the setup to enable AETHER AI");

            addMessage("AETHER AI is not configured yet.",
                    "Complete the setup to start chatting with your local AI model.",
                    false);
        }
    }

    /**
     * Garante, em segundo plano, que o motor do Ollama está pronto e que o
     * modelo configurado está mesmo instalado neste dispositivo.
     * <p>
     * O shell do dashboard já tenta arrancar o motor mais cedo (ver
     * {@link DashboardController}), mas se o utilizador chegar aqui antes
     * disso terminar, esta vista repete a verificação por si própria — não é
     * suposto o utilizador ter de fazer mais nada depois do setup.
     * </p>
     * <p>
     * Se o modelo guardado nas definições não estiver transferido (por
     * exemplo, porque o utilizador tinha outro modelo já instalado e nunca
     * chegou a transferir o recomendado), esta vista muda automaticamente
     * para o primeiro modelo encontrado no disco, em vez de falhar — e
     * regista essa mudança nas definições, para não repetir o mesmo diagnóstico
     * sempre que a app abrir.
     * </p>
     *
     * @param configuredModelId o modelo guardado nas definições da aplicação
     */
    private void warmUpEngine(String configuredModelId) {
        inputField.setDisable(true);
        sendButton.setDisable(true);
        statusLabel.setText("Starting local AI engine...");
        statusDot.getStyleClass().setAll("indicador-pendente");
        inputField.setPromptText("Starting AETHER AI...");

        Task<Boolean> prepareTask = new Task<>() {
            @Override
            protected Boolean call() {
                if (!OllamaService.ensureServerRunning()) {
                    throw new IllegalStateException(OllamaService.diagnose(configuredModelId));
                }
                if (!OllamaService.isModelDownloaded(configuredModelId)) {
                    throw new IllegalStateException(
                            "The configured model '" + configuredModelId + "' is not downloaded. Open Settings to download or choose another model.");
                }
                return true;
            }
        };

        prepareTask.setOnSucceeded(e -> {
            activeModelId = configuredModelId;
            modelLabel.setText("Local model: " + configuredModelId);
            setAwaiting(false);
        });

        prepareTask.setOnFailed(e -> {
            Throwable error = prepareTask.getException();
            String detail = error != null && error.getMessage() != null
                    ? error.getMessage() : "Something went wrong preparing the local AI engine.";
            reportEngineUnavailable(detail);
        });

        Thread thread = new Thread(prepareTask, "aether-ai-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The chat never silently switches models. The persisted active model is authoritative.
     */
    private void applyResolvedModel(String configuredModelId, String resolvedModel) {
        activeModelId = configuredModelId;
        modelLabel.setText("Local model: " + configuredModelId);
    }

    /**
     * Reflete na interface que o motor não ficou disponível, mas volta a
     * permitir o envio de mensagens: se o utilizador tentar mesmo assim, o
     * {@link #handleSend} faz uma nova tentativa por si só.
     *
     * @param detail a mensagem de diagnóstico a apresentar
     */
    private void reportEngineUnavailable(String detail) {
        statusLabel.setText("Engine offline");
        statusDot.getStyleClass().setAll("indicador-erro");
        inputField.setPromptText("Message AETHER...");
        inputField.setDisable(false);
        sendButton.setDisable(false);
        addMessage("AETHER", detail, false);
    }

    // ------------------------------------------------------------------
    // Mensagens
    // ------------------------------------------------------------------

    /**
     * Adiciona a mensagem de boas-vindas do AETHER.
     */
    private void addWelcomeMessage() {
        UserSession session = UserSession.getInstance();
        String name = session.getUserProfile().getPreferredName();
        if (name == null || name.isBlank()) {
            name = "there";
        }

        addMessage("Hello, " + name + ".",
                "I'm AETHER, your local AI assistant. I run entirely on your device — nothing leaves this machine.\n\nWhat can I help you with today?",
                false);
    }

    /**
     * Adiciona uma mensagem ao container de conversa.
     * <p>
     * As mensagens do utilizador ficam alinhadas à direita (verde/ciano).
     * As mensagens do AETHER ficam alinhadas à esquerda (vidro escuro).
     * </p>
     *
     * @param title o título da mensagem (ex.: "You" ou "AETHER")
     * @param content o texto da mensagem
     * @param isUser {@code true} se for uma mensagem do utilizador
     */
    private void addMessage(String title, String content, boolean isUser) {
        addMessageReturningLabel(title, content, isUser);
    }

    /**
     * Adiciona uma mensagem ao container de conversa e devolve o
     * {@link Label} do conteúdo, para que o chamador possa continuar a
     * atualizar o texto à medida que chegam mais fragmentos — é isso que
     * {@link #handleSend} usa para ir mostrando a resposta do AETHER em
     * tempo real, em vez de a apresentar de uma só vez no fim.
     *
     * @param title o título da mensagem (ex.: "You" ou "AETHER")
     * @param content o texto inicial da mensagem
     * @param isUser {@code true} se for uma mensagem do utilizador
     * @return o {@link Label} de conteúdo da bubble criada
     */
    private Label addMessageReturningLabel(String title, String content, boolean isUser) {
        VBox bubble = new VBox();
        bubble.setSpacing(4);
        bubble.setMaxWidth(460);

        Label titleLabel = new Label(title);
        Label contentLabel = new Label(content);
        contentLabel.setWrapText(true);

        if (isUser) {
            bubble.getStyleClass().add("ai-bubble-user");
            titleLabel.getStyleClass().add("ai-bubble-user-title");
            contentLabel.getStyleClass().add("ai-bubble-user-text");

            HBox wrapper = new HBox(bubble);
            wrapper.setAlignment(Pos.TOP_RIGHT);
            wrapper.setPadding(new Insets(0, 0, 0, 60));
            messagesContainer.getChildren().add(wrapper);
        } else {
            bubble.getStyleClass().add("ai-bubble-aether");
            titleLabel.getStyleClass().add("ai-bubble-aether-title");
            contentLabel.getStyleClass().add("ai-bubble-aether-text");

            HBox wrapper = new HBox(bubble);
            wrapper.setAlignment(Pos.TOP_LEFT);
            wrapper.setPadding(new Insets(0, 60, 0, 0));
            messagesContainer.getChildren().add(wrapper);
        }

        bubble.getChildren().addAll(titleLabel, contentLabel);

        Platform.runLater(() -> {
            chatScroll.layout();
            chatScroll.setVvalue(1.0);
        });

        return contentLabel;
    }

    /**
     * Reencaminha o auto-scroll para o fundo da conversa, chamado a cada
     * fragmento de texto recebido em streaming para acompanhar a resposta
     * enquanto ela vai crescendo.
     */
    private void scrollToBottom() {
        Platform.runLater(() -> {
            chatScroll.layout();
            chatScroll.setVvalue(1.0);
        });
    }

    // ------------------------------------------------------------------
    // Envio de mensagens
    // ------------------------------------------------------------------

    /**
     * Handler do botão Send e do Enter no campo de input.
     * <p>
     * Envia a mensagem do utilizador ao modelo local do Ollama numa tarefa
     * de fundo, para não bloquear a interface.
     * </p>
     */
    @FXML
    private void handleSend() {
        if (awaitingResponse) {
            return;
        }

        String message = inputField.getText();
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        String modelId = activeModelId;

        if (modelId == null || modelId.isBlank()) {
            addMessage("AETHER", "No model configured. Complete the setup first.", false);
            return;
        }

        // Mostra a mensagem do utilizador
        addMessage("You", message.trim(), true);

        // Limpa o input e bloqueia
        inputField.clear();
        setAwaiting(true);

        // Bubble do AETHER criada já vazia: o streaming vai preenchendo o seu
        // texto fragmento a fragmento, para a resposta aparecer a ser escrita
        // em tempo real em vez de só surgir de uma vez no fim (que é o que
        // acontecia com "stream:false" e fazia o chat parecer muito mais
        // lento do que realmente é).
        Label replyLabel = addMessageReturningLabel("AETHER", "", false);
        StringBuilder fullReply = new StringBuilder();

        // Tarefa de fundo para chamar o Ollama. Se nada for recebido, o
        // próprio fio de fundo faz o diagnóstico (evita nova ronda de rede no
        // fio da interface) e o motivo passa a ser mostrado ao utilizador em
        // vez da mensagem genérica anterior.
        Task<Boolean> chatTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                // O ContextManager constrói o prompt de sistema completo:
                // system instructions + dynamic date/time + compact summary + relevant context.
                // O contexto é selecionado com base na mensagem do utilizador,
                // em vez de enviar indiscriminadamente toda a base de dados.
                String systemContext = ContextManager.buildSystemPrompt(message.trim());
                boolean received = OllamaService.chatStream(modelId, message.trim(), systemContext, token -> {
                    fullReply.append(token);
                    String snapshot = fullReply.toString();
                    Platform.runLater(() -> {
                        replyLabel.setText(snapshot);
                        scrollToBottom();
                    });
                });
                if (!received) {
                    throw new IllegalStateException(OllamaService.diagnose(modelId));
                }
                return true;
            }
        };

        chatTask.setOnSucceeded(e -> setAwaiting(false));

        chatTask.setOnFailed(e -> {
            Throwable error = chatTask.getException();
            String detail = (error != null && error.getMessage() != null)
                    ? error.getMessage()
                    : "Something went wrong communicating with the model.";
            // Nada chegou a ser escrito na bubble vazia: mostra o diagnóstico nela.
            replyLabel.setText(detail);
            setAwaiting(false);
        });

        Thread thread = new Thread(chatTask, "aether-ai-chat");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Bloqueia ou desbloqueia o chat enquanto se aguarda a resposta do modelo.
     *
     * @param awaiting {@code true} se estiver à espera de resposta
     */
    private void setAwaiting(boolean awaiting) {
        awaitingResponse = awaiting;
        sendButton.setDisable(awaiting);
        inputField.setDisable(awaiting);
        if (awaiting) {
            statusLabel.setText("Thinking...");
            statusDot.getStyleClass().setAll("indicador-pendente");
            inputField.setPromptText("AETHER is thinking...");
        } else {
            statusLabel.setText("Ready");
            statusDot.getStyleClass().setAll("indicador-sucesso");
            inputField.setPromptText("Message AETHER...");
        }
    }
}
