package controller;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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
import session.ChatSession;
import util.ContextManager;
import util.OllamaService;
import ai.AiActionOrchestrator;
import ai.ActionExtractor;
import ai.OllamaActionExtractor;
import ai.ParsedAction;
import ai.ResolvedAction;
import ai.ProposalStatus;
import ai.AiActionType;
import util.VaultRefreshBus;
import util.I18n;
import view.LiquidOrb;

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
     * Orbe liquido "a AI está a pensar" apresentado dentro da bubble de
     * resposta pendente; criado em {@link #handleSend} e removido mal
     * chega o primeiro fragmento da resposta (ou em erro/sucesso/limpeza).
     */
    private LiquidOrb thinkingOrb;

    /**
     * Sinal de prontidão do contexto do utilizador (spec #16): resetado em
     * cada "Nova conversa" e completado quando o perfil/vault/index estão
     * carregados. O primeiro pedido à IA de cada conversa aguarda este
     * sinal (máx. 5s) ANTES de construir o prompt — garantindo
     * READY → THEN first AI request sem bloquear a UI.
     */
    private volatile java.util.concurrent.CompletableFuture<Void> contextReady =
            java.util.concurrent.CompletableFuture.completedFuture(null);

    /**
     * Histórico da conversa, persistido entre trocas de vista através do
     * {@link session.ChatSession}. Sem isto, mudar de vista (ex.: ir ao
     * Dashboard e voltar) recriaria o controlador e apagaria a conversa. A
     * conversa só é reiniciada quando o utilizador clica em "Nova conversa".
     */
    private final List<OllamaService.ChatMessage> conversationHistory =
            session.ChatSession.getInstance().getHistory();

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
            restoreConversation();
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
     * Restaura a conversa persistida no {@link session.ChatSession}. Se houver
     * histórico anterior (de antes de mudar de vista), as bolhas são
     * recriadas para o utilizador continuar onde deixou. Se não houver, mostra
     * a mensagem de boas-vindas.
     */
    private void restoreConversation() {
        if (!conversationHistory.isEmpty()) {
            for (OllamaService.ChatMessage m : conversationHistory) {
                boolean isUser = "user".equals(m.getRole());
                addMessage(isUser ? "You" : "AETHER", m.getContent(), isUser);
            }
            scrollToBottom();
        } else {
            addWelcomeMessage();
        }
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

        // Deteção IMEDIATA de informação pessoal (paralela à resposta da IA):
        // corre num fio de fundo rápido (só regex + persistência SQLite) e
        // incrementa o sino mal o utilizador envia a mensagem — não depende
        // do Ollama nem de a resposta ter terminado. A IA só propõe; o
        // utilizador revê depois pelo sino → Profile.
        detectPersonalInfoImmediately(message.trim());

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

        // Orbe liquido (estilo Siri) dentro da bubble de resposta vazia: fica
        // visível enquanto o modelo pensa e desaparece mal chega o primeiro
        // fragmento do streaming. O orbe já começa em IDLE e transita para
        // THINKING com a ativação suave (0.15s) do componente original.
        LiquidOrb orb = new LiquidOrb(44);
        if (replyLabel.getParent() instanceof VBox bubble) {
            bubble.getChildren().add(orb);
        }
        thinkingOrb = orb;
        orb.start();
        orb.setState(LiquidOrb.OrbState.THINKING);

        // Garante que o orbe é retirado da bubble uma única vez, no primeiro
        // fragmento — os casos sucesso/erro/limpeza chamam o mesmo método e
        // são idempotentes.
        java.util.concurrent.atomic.AtomicBoolean firstToken =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        // Snapshot do histórico antes da thread de fundo para evitar
        // mutação concorrente enquanto a resposta é gerada.
        List<OllamaService.ChatMessage> requestHistory = new ArrayList<>(conversationHistory);
        // Adiciona a mensagem atual do utilizador ao snapshot enviado à IA.
        requestHistory.add(new OllamaService.ChatMessage("user", message.trim()));

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
                //
                // Para a seleção de contexto, usamos não só a mensagem atual
                // mas também as últimas mensagens do utilizador no histórico.
                // Isto resolve perguntas de seguimento como "e o trabalho
                // dele?" — a mensagem atual não menciona "Rafael", mas o
                // turno anterior sim, pelo que o contexto certo é recuperado.
                String contextQuery = buildContextQuery(message.trim(), conversationHistory);
                // Spec #16: o primeiro pedido da conversa aguarda (≤5s) a
                // preparação do contexto do utilizador — READY → THEN AI request.
                try {
                    contextReady.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException te) {
                    // Timeout: o perfil SQLite já está carregado (fonte
                    // canónica); segue-se com o contexto disponível.
                    LOGGER.warning("Preparação de contexto excedeu 5s; a continuar com o contexto atual.");
                }
                String systemContext = ContextManager.buildSystemPrompt(contextQuery);
                // Usa /api/chat com o histórico completo para que a IA recorde
                // os turnos anteriores da conversa.
                boolean received = OllamaService.chatStream(modelId, requestHistory, systemContext, token -> {
                    fullReply.append(token);
                    if (firstToken.compareAndSet(true, false)) {
                        Platform.runLater(() -> stopThinkingOrb());
                    }
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

        chatTask.setOnSucceeded(e -> {
            stopThinkingOrb();
            // Só guarda a resposta no histórico se realmente foi recebida,
            // para não corromper a memória com respostas vazias. Grava no
            // ChatSession para a conversa persistir entre trocas de vista.
            String reply = fullReply.toString().trim();
            if (!reply.isBlank()) {
                ChatSession.getInstance().addUserMessage(message.trim());
                ChatSession.getInstance().addAssistantMessage(reply);
            }
            setAwaiting(false);
            // Fase 2: detetar e propor ações (em segundo plano, sem bloquear a UI).
            // A resposta natural já foi mostrada; esta chamada serve só para
            // extrair ações estruturadas que o utilizador terá de aprovar.
            if (activeModelId != null && !activeModelId.isBlank()) {
                proposeActionsAfterReply(message.trim(), reply);
            }
        });

        chatTask.setOnFailed(e -> {
            stopThinkingOrb();
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
     * Pára o orbe "a pensar" e retira-o da bubble de resposta (idempotente;
     * seguro em qualquer fio — manipula a cena via FX thread quando existe).
     */
    private void stopThinkingOrb() {
        LiquidOrb orb = thinkingOrb;
        if (orb == null) {
            return;
        }
        thinkingOrb = null;
        orb.stop();
        Runnable detach = () -> {
            if (orb.getParent() instanceof VBox bubble) {
                bubble.getChildren().remove(orb);
            }
        };
        if (Platform.isFxApplicationThread()) {
            detach.run();
        } else {
            try {
                Platform.runLater(detach);
            } catch (IllegalStateException toolkitNotInitialized) {
                // Sem toolkit (contexto de teste): nada a remover da cena.
            }
        }
    }


    /**
     * Constrói a consulta usada para selecionar o contexto do vault. Combina a
     * mensagem atual com as últimas mensagens do utilizador no histórico, para
     * que perguntas de seguimento como "e o trabalho dele?" consigam
     * recuperar a entidade certa (Rafael) mesmo quando a mensagem atual não
     * menciona o nome diretamente.
     *
     * @param currentMessage a mensagem atual do utilizador
     * @param history         o histórico da conversa
     * @return a consulta combinada para seleção de contexto
     */
    private String buildContextQuery(String currentMessage,
                                     List<OllamaService.ChatMessage> history) {
        StringBuilder query = new StringBuilder(currentMessage);
        // Acrescenta até às duas últimas mensagens do utilizador, que é onde
        // costumam estar os nomes e tópicos a que o utilizador se refere.
        int added = 0;
        for (int i = history.size() - 1; i >= 0 && added < 2; i--) {
            OllamaService.ChatMessage m = history.get(i);
            if ("user".equals(m.getRole()) && m.getContent() != null
                    && !m.getContent().isBlank()) {
                query.append(" ").append(m.getContent());
                added++;
            }
        }
        return query.toString();
    }

    /**
     * Limpa o histórico da conversa atual. Útil quando o utilizador começa um
     * novo tópico e não quer que a IA continue a referenciar o contexto
     * anterior.
     */
    public void clearConversation() {
        ChatSession.getInstance().clear();
    }

    /**
     * Inicia uma nova conversa: limpa a memória persistida e a área de
     * mensagens. O utilizador usa o botão "Nova conversa" quando muda de
     * tópico para que a IA não misture contexto de assuntos diferentes.
     */
    @FXML
    private void handleNewConversation() {
        clearConversation();
        stopThinkingOrb();
        if (messagesContainer != null) {
            messagesContainer.getChildren().clear();
        }
        addWelcomeMessage();
        prepareUserContext();
    }

    /**
     * PREPARAÇÃO DO CONTEXTO DO UTILIZADOR (spec #16): garante que, numa nova
     * conversa, a memória do utilizador está carregada e fresca ANTES do
     * primeiro pedido à IA:
     * <ol>
     *   <li>USER PROFILE — já carregado do SQLite pelo UserSession (canônico).</li>
     *   <li>USER VAULT — estrutura garantida (inclui a pasta User/).</li>
     *   <li>VAULT INDEX — invalidado; a próxima leitura reconstrói do disco.</li>
     *   <li>USER VAULT SYNC — pasta User/ re-sincronizada a partir do perfil
     *       (merge; preserva edições manuais do Obsidian).</li>
     *   <li>READY — {@code contextReady} é completado; o primeiro
     *       buildSystemPrompt da conversa só corre depois (espera ≤5s).</li>
     * </ol>
     * Corre em thread daemon — NÃO bloqueia a UI nem o primeiro input do
     * utilizador; apenas o pedido à IA (em background) aguarda o sinal.
     */
    private void prepareUserContext() {
        java.util.concurrent.CompletableFuture<Void> ready = new java.util.concurrent.CompletableFuture<>();
        contextReady = ready;
        Thread t = new Thread(() -> {
            try {
                persistence.VaultManager.initializeVault();
                util.VaultIndex.getInstance().invalidate();
                persistence.UserVaultSync.syncProfile(
                        session.UserSession.getInstance().getUserProfile(), "NEW_CONVERSATION");
                LOGGER.info("Contexto do utilizador preparado para a nova conversa.");
            } catch (RuntimeException ex) {
                LOGGER.warning("Preparação de contexto (best-effort) falhou: "
                        + (ex.getMessage() != null ? ex.getMessage() : "erro desconhecido"));
            } finally {
                ready.complete(null);
            }
        }, "aether-context-prepare");
        t.setDaemon(true);
        t.start();
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

    // ------------------------------------------------------------------
    // AI Action proposals (Suggest -> Explain -> Ask -> Execute)
    // ------------------------------------------------------------------

    /**
     * Deteção IMEDIATA de informação pessoal na mensagem do utilizador. Corre
     * em paralelo com a resposta da IA (não espera por ela nem pelo Ollama).
     * Usa só o detetor heurístico ({@link ai.PersonalInfoFallbackExtractor})
     * que é instantâneo (regex) e persiste qualquer campo detetado através
     * do MESMO pipeline (orchestrator → ProposalStore → sino). Assim, mal o
     * utilizador envia "Eu estudo no ISEP", o sino incrementa.
     * <p>
     * A IA apenas sugere — a informação fica PENDING até aceitar no Profile.
     */
    private void detectPersonalInfoImmediately(String userMessage) {
        Thread t = new Thread(() -> {
            try {
                ai.PersonalInfoFallbackExtractor fallback = new ai.PersonalInfoFallbackExtractor();
                java.util.List<ParsedAction> detected = fallback.extract(userMessage, "");
                if (detected == null || detected.isEmpty()) return;
                // Passa pelo orchestrator para validar + deduplicar contra o
                // perfil confirmado e propostas já pendentes.
                AiActionOrchestrator orch = new AiActionOrchestrator(fallback);
                java.util.List<ResolvedAction> actions = orch.orchestrate(userMessage, "");
                if (actions == null || actions.isEmpty()) return;
                // Fonte única (spec #11): só publica refresh se houver de facto
                // uma NOVA proposta pendente (o sino não incrementa à toa).
                boolean anyNew = false;
                for (ResolvedAction ra : actions) {
                    anyNew |= (persistPendingProposal(ra) == ai.ProposalStore.Decision.NEW_PENDING);
                }
                if (!anyNew) return;
                // Atualiza o sino imediatamente na UI.
                Platform.runLater(() -> {
                    util.VaultRefreshBus.publish(util.VaultRefreshBus.ChangeType.UPDATED, "PROFILE");
                    if (DashboardController.getActive() != null) {
                        DashboardController.getActive().refreshProposalBadge();
                    }
                });
            } catch (RuntimeException ex) {
                LOGGER.warning("Deteção imediata falhou (best-effort): "
                        + (ex.getMessage() != null ? ex.getMessage() : "erro desconhecido"));
            }
        }, "aether-instant-detect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Depois da resposta natural, extrai ações estruturadas (segunda chamada ao
     * modelo, formato JSON) e apresenta um cartão de aprovação no chat para cada
     * ação detetada. A IA nunca executa nada — só propõe. O utilizador aprova
     * explicitamente; só então o {@link ai.ActionExecutor} age sobre o vault.
     */
    private void proposeActionsAfterReply(String userMessage, String reply) {
        // Extrator composto: Ollama (1ª linha) + fallback heurístico que
        // garante que informação pessoal é detetada mesmo sem o modelo disponível
        // ou quando este não reconhece uma afirmação sobre o utilizador. O
        // fallback só acrescenta campos de perfil que o Ollama ainda não cobriu
        // — evita propostas duplicadas. Tudo entra no mesmo pipeline.
        ActionExtractor ollama = new OllamaActionExtractor(activeModelId, () -> conversationHistory);
        ActionExtractor fallback = new ai.PersonalInfoFallbackExtractor();
        ActionExtractor composite = (userMessage1, reply1) -> {
            java.util.List<ParsedAction> out = new java.util.ArrayList<>();
            java.util.Set<String> covered = new java.util.HashSet<>();
            try {
                for (ParsedAction p : ollama.extract(userMessage1, reply1)) {
                    out.add(p);
                    if ("PROFILE".equalsIgnoreCase(p.entity) && p.fields != null) {
                        covered.addAll(p.fields.keySet());
                    }
                }
            } catch (RuntimeException ignored) {
                // Ollama indisponível — o fallback abaixo continua a funcionar.
            }
            for (ParsedAction p : fallback.extract(userMessage1, reply1)) {
                if ("PROFILE".equalsIgnoreCase(p.entity) && p.fields != null && !p.fields.isEmpty()) {
                    java.util.Map<String, String> remaining = new java.util.LinkedHashMap<>();
                    p.fields.forEach((k, v) -> { if (!covered.contains(k)) remaining.put(k, v); });
                    if (!remaining.isEmpty()) {
                        // Preserva a classificação/confiança/evidência do
                        // fallback (spec #8, #9) — 9-arg factory.
                        out.add(ParsedAction.of("UPDATE_ENTITY", "PROFILE", remaining,
                                List.of(), "", "Informação pessoal detetada na conversa.",
                                p.classification, p.confidence, p.evidence));
                    }
                } else if (!"PROFILE".equalsIgnoreCase(p.entity)) {
                    out.add(p);
                }
            }
            return out;
        };
        AiActionOrchestrator orchestrator = new AiActionOrchestrator(composite);

        Task<java.util.List<ResolvedAction>> extractTask = new Task<>() {
            @Override
            protected java.util.List<ResolvedAction> call() {
                return orchestrator.orchestrate(userMessage, reply);
            }
        };

        extractTask.setOnSucceeded(e -> {
            java.util.List<ResolvedAction> actions = extractTask.getValue();
            if (actions == null || actions.isEmpty()) {
                return;
            }
            // As propostas são persistidas (sino +1) mas NÃO são apresentadas
            // como cartão no chat — o utilizador revê-as pelo sino → Profile.
            // Fonte única (spec #11): só publica refresh se houver de facto uma
            // NOVA proposta pendente.
            boolean anyNew = false;
            for (ResolvedAction ra : actions) {
                anyNew |= (persistPendingProposal(ra) == ai.ProposalStore.Decision.NEW_PENDING);
            }
            if (!anyNew) return;
            // Garante que o badge do sino atualiza imediatamente.
            util.VaultRefreshBus.publish(util.VaultRefreshBus.ChangeType.UPDATED, "PROFILE");
            if (DashboardController.getActive() != null) {
                DashboardController.getActive().refreshProposalBadge();
            }
        });

        extractTask.setOnFailed(e -> {
            // A extração de ações é best-effort: a resposta natural já foi mostrada.
            // Se falhar, regista-se silenciosamente para não perturbar a conversa.
            Throwable err = extractTask.getException();
            LOGGER.warning("A extração de ações falhou (a resposta natural já foi mostrada): "
                    + (err != null && err.getMessage() != null ? err.getMessage() : "erro desconhecido"));
        });

        Thread t = new Thread(extractTask, "aether-action-extract");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Persists a chat proposal so it survives view changes and application restarts.
     *
     * @return a decisão do ProposalStore (NEW_PENDING quando a proposta é de
     *         facto nova; os restantes casos indicam duplicado/aceite/rejeitado)
     */
    private ai.ProposalStore.Decision persistPendingProposal(ResolvedAction ra) {
        ai.AiActionProposal p = ra.proposal;
        String identifier = p.getFields().getOrDefault("name",
                p.getFields().getOrDefault("title", p.getFields().getOrDefault("content", "profile")));
        String id = ai.ProposalStore.idFor(p.getActionType().name(), p.getEntityType().name(), identifier, p.getFields());
        return ai.ProposalStore.getInstance().propose(new ai.ProposalStore.Snapshot(
                id, "PENDING", p.getActionType().name(), p.getEntityType().name(), identifier,
                p.getFields(), p.getRelationships(), p.getReason(), p.getTrustLevel().name(),
                p.getConfidence(), p.getSourceContext(), System.currentTimeMillis(), p.getSemanticClassification()));
    }

    private void markProposalStatus(ResolvedAction ra, String status) {
        ai.AiActionProposal p = ra.proposal;
        String identifier = p.getFields().getOrDefault("name",
                p.getFields().getOrDefault("title", p.getFields().getOrDefault("content", "profile")));
        String id = ai.ProposalStore.idFor(p.getActionType().name(), p.getEntityType().name(), identifier, p.getFields());
        ai.ProposalStore store = ai.ProposalStore.getInstance();
        store.find(id).ifPresentOrElse(snapshot -> {
            switch (status) {
                case "ACCEPTED" -> store.markAccepted(id);
                case "REJECTED" -> store.markRejected(id);
                case "FAILED" -> store.markFailed(id);
                default -> { }
            }
        }, () -> persistPendingProposal(ra));
    }

    /** Executa uma proposta aprovada e mostra a confirmação (ou erro + retry). */
    private void executeApproved(ResolvedAction ra, VBox card, Runnable markError) {
        new Thread(() -> {
            boolean ok = ai.ApprovalFlow.executeApproved(ra.proposal).success();
            Platform.runLater(() -> {
                if (ok) {
                    markProposalStatus(ra, "ACCEPTED");
                    VaultRefreshBus.publish(VaultRefreshBus.ChangeType.UPDATED, ra.proposal.getEntityType().name());
                    Label done = new Label("✓ " + confirmationMessage(ra.proposal));
                    done.getStyleClass().add("ai-proposal-state");
                    done.getStyleClass().add("done");
                    done.setWrapText(true);
                    card.getChildren().add(done);
                } else {
                    markProposalStatus(ra, "FAILED");
                    if (markError != null) markError.run();
                    Label fail = new Label(I18n.tr("ai.proposal.failed"));
                    fail.getStyleClass().add("ai-proposal-state");
                    fail.getStyleClass().add("error");
                    fail.setWrapText(true);
                    Button retry = new Button(I18n.tr("common.retry"));
                    retry.getStyleClass().addAll("proposal-btn", "proposal-btn-tertiary");
                    retry.setOnAction(ev -> {
                        retry.setDisable(true);
                        executeApproved(ra, card, markError);
                    });
                    card.getChildren().addAll(fail, retry);
                }
                scrollToBottom();
            });
        }, "aether-proposal-execute").start();
    }

    private void showEditHint(VBox card, ResolvedAction ra) {
        Label hint = new Label(I18n.tr("ai.proposal.editHint"));
        hint.getStyleClass().add("ai-approval-reason");
        hint.setWrapText(true);
        card.getChildren().add(hint);
    }

    private String cardTitle(ResolvedAction ra) {
        String verb = switch (ra.proposal.getActionType()) {
            case CREATE_ENTITY -> I18n.tr("ai.proposal.create");
            case UPDATE_ENTITY -> I18n.tr("ai.proposal.update");
            case LINK_ENTITIES -> I18n.tr("ai.proposal.link");
            case UNLINK_ENTITIES -> I18n.tr("ai.proposal.unlink");
            case DELETE_ENTITY -> I18n.tr("ai.proposal.delete");
        };
        return verb + " " + entityLabel(ra.proposal.getEntityType());
    }

    private String primaryButtonLabel(ResolvedAction ra) {
        return switch (ra.proposal.getActionType()) {
            case CREATE_ENTITY -> I18n.tr("common.create");
            case UPDATE_ENTITY -> I18n.tr("common.save");
            case LINK_ENTITIES -> I18n.tr("ai.proposal.link");
            case UNLINK_ENTITIES -> I18n.tr("ai.proposal.unlink");
            case DELETE_ENTITY -> I18n.tr("common.delete");
        };
    }

    private String entityLabel(domain.entities.ContextEntityType t) {
        return switch (t) {
            case PERSON -> I18n.tr("entity.person");
            case PROJECT -> I18n.tr("entity.project");
            case EVENT -> I18n.tr("entity.event");
            case TASK -> I18n.tr("entity.task");
            case NOTE -> I18n.tr("entity.note");
            case PROFILE -> I18n.tr("entity.profile");
        };
    }

    private String describeProposal(ai.AiActionProposal p) {
        StringBuilder sb = new StringBuilder();
        p.getFields().forEach((k, v) -> {
            if (!v.isBlank()) sb.append(k).append(": ").append(v).append("\n");
        });
        if (!p.getRelationships().isEmpty()) {
            sb.append(I18n.tr("ai.proposal.relationships")).append(": ").append(String.join(", ", p.getRelationships()));
        }
        return sb.toString().trim();
    }

    private String confirmationMessage(ai.AiActionProposal p) {
        String name = "";
        switch (p.getEntityType()) {
            case PERSON, PROJECT -> name = p.getFields().getOrDefault("name", "");
            case EVENT, TASK -> name = p.getFields().getOrDefault("title", "");
            case NOTE -> name = p.getFields().getOrDefault("content", "");
            case PROFILE -> name = p.getFields().getOrDefault("preferredName", "");
        }
        String verb = switch (p.getActionType()) {
            case CREATE_ENTITY -> I18n.tr("ai.proposal.doneCreate");
            case UPDATE_ENTITY -> I18n.tr("ai.proposal.doneUpdate");
            case LINK_ENTITIES -> I18n.tr("ai.proposal.doneLink");
            case UNLINK_ENTITIES -> I18n.tr("ai.proposal.doneUnlink");
            case DELETE_ENTITY -> I18n.tr("ai.proposal.doneDelete");
        };
        if (name != null && !name.isBlank()) {
            return verb + " '" + name + "'.";
        }
        return verb + ".";
    }

}
