package controller;

import java.net.URL;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import session.UserSession;

/**
 * Controlador da vista do dashboard ({@code dashboard_view.fxml}), carregada
 * dentro do {@code contentArea} do shell principal.
 * <p>
 * Apresenta o Context Graph, os cartões de resumo (People, Events, Tasks) e o
 * painel direito com calendário e agenda do dia. Todos os dados vêm da sessão
 * do utilizador ou de repositórios locais — nunca são hardcoded.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.1
 */
public class DashboardViewController implements Initializable {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public DashboardViewController() {
        // Construtor por omissão explícito.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(DashboardViewController.class.getName());

    /** Mês e ano atualmente apresentados no calendário. */
    private YearMonth displayedMonth = YearMonth.now();

    // ------------------------------------------------------------------
    // FXML
    // ------------------------------------------------------------------

    @FXML private Label peopleCountLabel;
    @FXML private Label eventsCountLabel;
    @FXML private Label tasksCountLabel;
    @FXML private Label todayCountLabel;
    @FXML private Label monthLabel;
    @FXML private VBox peopleListContainer;
    @FXML private VBox eventsListContainer;
    @FXML private VBox tasksListContainer;
    @FXML private VBox todayScheduleContainer;
    @FXML private VBox calendarGrid;
    @FXML private VBox contextGraphContainer;

    // ------------------------------------------------------------------
    // Inicialização
    // ------------------------------------------------------------------

    /**
     * Inicializa a vista do dashboard.
     *
     * @param location o URL do FXML carregado
     * @param resources o pacote de recursos de localização
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadPeople();
        loadEvents();
        loadTasks();
        loadTodaySchedule();
        renderCalendar();
        renderContextGraph();
    }

    // ------------------------------------------------------------------
    // People
    // ------------------------------------------------------------------

    /**
     * Carrega a lista de pessoas a partir dos dados disponíveis.
     * <p>
     * Atualmente não existe repositório de entidades. O perfil do utilizador
     * já é apresentado no cartão de perfil da sidebar, por isso o cartão
     * People mostra o estado vazio. Quando houver repositório de pessoas,
     * substituir o corpo deste método pela leitura da base de dados local.
     * </p>
     */
    private void loadPeople() {
        peopleCountLabel.setText("0");
        Label empty = new Label("No people yet.");
        empty.getStyleClass().add("dash-empty-state");
        peopleListContainer.getChildren().add(empty);
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * Carrega a lista de eventos a partir dos dados disponíveis.
     * <p>
     * Sem repositório de eventos ainda — mostra estado vazio.
     * </p>
     */
    private void loadEvents() {
        eventsCountLabel.setText("0");
        Label empty = new Label("No events yet.");
        empty.getStyleClass().add("dash-empty-state");
        eventsListContainer.getChildren().add(empty);
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    /**
     * Carrega a lista de tarefas a partir dos dados disponíveis.
     * <p>
     * Sem repositório de tarefas ainda — mostra estado vazio.
     * </p>
     */
    private void loadTasks() {
        tasksCountLabel.setText("0");
        Label empty = new Label("No tasks yet.");
        empty.getStyleClass().add("dash-empty-state");
        tasksListContainer.getChildren().add(empty);
    }

    // ------------------------------------------------------------------
    // Today schedule
    // ------------------------------------------------------------------

    /**
     * Carrega a agenda do dia.
     * <p>
     * Sem repositório de eventos/tarefas ainda — mostra estado vazio.
     * </p>
     */
    private void loadTodaySchedule() {
        todayCountLabel.setText("0");
        Label empty = new Label("Nothing scheduled for today.");
        empty.getStyleClass().add("dash-empty-state");
        todayScheduleContainer.getChildren().add(empty);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Cria uma linha de lista com título e subtítulo.
     *
     * @param title o título
     * @param subtitle o subtítulo
     * @return a linha criada
     */
    private HBox createListRow(String title, String subtitle) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dash-list-item-title");

        Label subLabel = new Label(" — " + subtitle);
        subLabel.getStyleClass().add("dash-list-item-sub");

        HBox row = new HBox(titleLabel, subLabel);
        row.getStyleClass().add("dash-list-row");
        return row;
    }

    // ------------------------------------------------------------------
    // Context Graph
    // ------------------------------------------------------------------

    /**
     * Renderiza o Context Graph com o utilizador como nó central.
     * <p>
     * O nó central é identificado pelo nome do utilizador e o seu conteúdo
     * vem do contexto de IA guardado no perfil. Se o contexto estiver vazio,
     * mostra um estado vazio a convidar o utilizador a preenchê-lo.
     * </p>
     */
    private void renderContextGraph() {
        contextGraphContainer.getChildren().clear();

        UserSession session = UserSession.getInstance();
        var profile = session.getUserProfile();

        String fullName = profile.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = "AETHER User";
        }

        String displayName = profile.getPreferredName();
        if (displayName == null || displayName.isBlank()) {
            displayName = fullName;
        }

        String displaySummary = profile.getDisplaySummary();
        boolean hasSummary = displaySummary != null && !displaySummary.isBlank();

        // Nó central do utilizador
        VBox userNode = new VBox(4);
        userNode.getStyleClass().add("dash-graph-user-node");
        userNode.setAlignment(javafx.geometry.Pos.CENTER);

        Circle userCircle = new Circle(30);
        userCircle.getStyleClass().add("dash-graph-user-circle");

        Label userInitials = new Label(extractInitials(fullName));
        userInitials.getStyleClass().add("dash-graph-user-initials");

        StackPane avatarPane = new StackPane(userCircle, userInitials);
        avatarPane.getStyleClass().add("dash-graph-user-avatar");

        Label userNameLabel = new Label(displayName);
        userNameLabel.getStyleClass().add("dash-graph-user-name");

        Label contextLabel;
        if (hasSummary) {
            // No Context Graph, mostra apenas o resumo principal do utilizador.
            // Os dados completos continuam internamente disponíveis para a IA
            // através do ContextManager, mas não são exibidos no gráfico.
            String preview = displaySummary.trim();
            if (preview.length() > 120) {
                preview = preview.substring(0, 120).trim() + "...";
            }
            contextLabel = new Label(preview);
            contextLabel.getStyleClass().add("dash-graph-context-preview");
        } else {
            contextLabel = new Label("Your context is empty. Tell AETHER about yourself in Profile.");
            contextLabel.getStyleClass().add("dash-graph-empty-context");
        }

        contextLabel.setWrapText(true);
        contextLabel.setMaxWidth(280);
        contextLabel.setAlignment(javafx.geometry.Pos.CENTER);

        userNode.getChildren().addAll(avatarPane, userNameLabel, contextLabel);

        // O nó central representa a Central Context Note do utilizador — a
        // mesma nota editável em Profile. Clicar nele abre essa mesma vista,
        // em vez de apenas selecionar o nó.
        userNode.getStyleClass().add("dash-graph-user-node-clickable");
        userNode.setCursor(javafx.scene.Cursor.HAND);
        userNode.setOnMouseClicked(e -> openCentralContextNote());

        contextGraphContainer.getChildren().add(userNode);
    }

    /**
     * Abre a Central Context Note do utilizador.
     * <p>
     * Reutiliza a Profile View existente (com o editor de AI Context) através
     * do shell do dashboard, em vez de criar uma segunda interface de edição.
     * A Central Context Note é a mesma entidade em Profile, no Context Graph e
     * no contexto lido pela IA — não existem cópias separadas.
     * </p>
     */
    private void openCentralContextNote() {
        DashboardController shell = DashboardController.getActive();
        if (shell != null) {
            shell.showProfileView();
        } else {
            LOGGER.warning("Não foi possível abrir a Central Context Note: shell do dashboard indisponível.");
        }
    }

    /**
     * Extrai as iniciais de um nome completo.
     *
     * @param fullName o nome completo do utilizador
     * @return as iniciais (1 a 2 caracteres), maiúsculas
     */
    private String extractInitials(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "AU";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    // ------------------------------------------------------------------
    // Calendário
    // ------------------------------------------------------------------

    /**
     * Renderiza o calendário do mês atual.
     */
    private void renderCalendar() {
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy");
        monthLabel.setText(displayedMonth.format(monthFmt));

        calendarGrid.getChildren().clear();

        LocalDate firstOfMonth = displayedMonth.atDay(1);
        int dayOfWeek = firstOfMonth.getDayOfWeek().getValue() % 7;
        int daysInMonth = displayedMonth.lengthOfMonth();

        HBox currentWeek = new HBox();
        currentWeek.getStyleClass().add("dash-cal-week");
        currentWeek.setAlignment(javafx.geometry.Pos.CENTER);
        currentWeek.setSpacing(2);

        YearMonth prevMonth = displayedMonth.minusMonths(1);
        int prevDays = prevMonth.lengthOfMonth();
        for (int i = dayOfWeek - 1; i >= 0; i--) {
            currentWeek.getChildren().add(createCalendarDay(prevDays - i, false, false));
        }

        LocalDate today = LocalDate.now();
        int day = 1;
        int currentDayOfWeek = dayOfWeek;

        while (day <= daysInMonth) {
            if (currentDayOfWeek == 7) {
                calendarGrid.getChildren().add(currentWeek);
                currentWeek = new HBox();
                currentWeek.getStyleClass().add("dash-cal-week");
                currentWeek.setAlignment(javafx.geometry.Pos.CENTER);
                currentWeek.setSpacing(2);
                currentDayOfWeek = 0;
            }

            boolean isToday = YearMonth.from(today).equals(displayedMonth) && day == today.getDayOfMonth();
            currentWeek.getChildren().add(createCalendarDay(day, true, isToday));
            day++;
            currentDayOfWeek++;
        }

        int nextDay = 1;
        while (currentDayOfWeek < 7) {
            currentWeek.getChildren().add(createCalendarDay(nextDay, false, false));
            nextDay++;
            currentDayOfWeek++;
        }
        calendarGrid.getChildren().add(currentWeek);
    }

    /**
     * Cria um label de dia do calendário.
     *
     * @param day o número do dia
     * @param currentMonth se pertence ao mês atual
     * @param isToday se é o dia de hoje
     * @return o label criado
     */
    private Label createCalendarDay(int day, boolean currentMonth, boolean isToday) {
        Label label = new Label(String.valueOf(day));
        label.getStyleClass().add("dash-cal-day");
        if (!currentMonth) {
            label.getStyleClass().add("dash-cal-day-other");
        }
        if (isToday) {
            label.getStyleClass().add("dash-cal-day-today");
        }
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(javafx.geometry.Pos.CENTER);
        return label;
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    /**
     * Navega para o mês anterior no calendário.
     */
    @FXML
    private void handlePrevMonth() {
        displayedMonth = displayedMonth.minusMonths(1);
        renderCalendar();
    }

    /**
     * Navega para o mês seguinte no calendário.
     */
    @FXML
    private void handleNextMonth() {
        displayedMonth = displayedMonth.plusMonths(1);
        renderCalendar();
    }
}
