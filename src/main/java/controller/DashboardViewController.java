package controller;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import session.UserSession;
import persistence.VaultManager;
import domain.entities.Event;
import domain.entities.Person;
import domain.entities.Task;
import util.I18n;

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

    /** Número máximo de itens mostrados nos cartões de resumo (People/Events/Tasks). */
    private static final int MAX_DASHBOARD_ITEMS = 4;

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
    @FXML private Label graphPlaceholderLabel;

    // ------------------------------------------------------------------
    // Inicialização
    // ------------------------------------------------------------------

    /**
     * Inicializa a vista do dashboard.
     *
     * @param location o URL do FXML carregado
     * @param resources o pacote de recursos de localização
     */
    /** Listener de refresh do vault (subscrito no initialize). */
    private static java.util.function.Consumer<util.VaultRefreshBus.VaultChangedEvent> activeRefreshListener;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadPeople();
        loadEvents();
        loadTasks();
        loadTodaySchedule();
        renderCalendar();
        renderContextGraph();

        // Refresh reativo: quando o vault muda (na app ou via FileWatcher externo),
        // refresca a vista sem reiniciar. Substitui o listener anterior para não
        // acumular subscrições entre recriações da vista.
        if (activeRefreshListener != null) {
            util.VaultRefreshBus.unsubscribe(activeRefreshListener);
        }
        activeRefreshListener = e -> javafx.application.Platform.runLater(this::refreshAll);
        util.VaultRefreshBus.subscribe(activeRefreshListener);
    }

    /** Refresca todos os painéis do dashboard a partir do VaultIndex atual. */
    private void refreshAll() {
        loadPeople();
        loadEvents();
        loadTasks();
        loadTodaySchedule();
        renderCalendar();
    }

    // ------------------------------------------------------------------
    // People
    // ------------------------------------------------------------------

    /**
     * Carrega a lista de pessoas a partir do vault, limitada aos mais relevantes
     * (com mais informação preenchida). Mostra no máximo
     * {@link #MAX_DASHBOARD_ITEMS} pessoas para o cartão não transbordar a
     * janela; a contagem total continua a aparecer no badge.
     */
    private void loadPeople() {
        peopleListContainer.getChildren().clear();
        var people = VaultManager.listPeople();
        peopleCountLabel.setText(String.valueOf(people.size()));
        if (people.isEmpty()) {
            Label empty = new Label(I18n.tr("dashboard.empty.people"));
            empty.getStyleClass().add("dash-empty-state");
            peopleListContainer.getChildren().add(empty);
            return;
        }

        people.stream()
                .sorted(Comparator.comparingInt(DashboardViewController::personInfoScore).reversed())
                .limit(MAX_DASHBOARD_ITEMS)
                .forEach(p -> {
                    String occupation = p.getOccupation();
                    String subtitle = (occupation != null && !occupation.isBlank()) ? occupation : "No occupation";
                    peopleListContainer.getChildren().add(createListRow(p.getName(), subtitle));
                });
    }

    /**
     * Pontuação de "riqueza de informação" de uma pessoa. Não existe um campo
     * de interações, pelo que ordenamos pelas pessoas com mais dados
     * preenchidos — estas são as mais úteis para ver no dashboard.
     *
     * @param p a pessoa
     * @return a pontuação (maior = mais informação)
     */
    private static int personInfoScore(Person p) {
        int score = 0;
        if (p.getName() != null && !p.getName().isBlank()) score++;
        if (p.getOccupation() != null && !p.getOccupation().isBlank()) score += 2;
        if (p.getBirthDate() != null) score++;
        if (p.getAbout() != null) {
            score += Math.min(p.getAbout().length() / 50, 4);
        }
        return score;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * Carrega a lista de eventos a partir do vault, limitada aos próximos
     * {@link #MAX_DASHBOARD_ITEMS} eventos futuros (por data de início). Mostra
     * apenas os mais próximos no tempo para o cartão não transbordar a janela;
     * a contagem total continua a aparecer no badge.
     */
    private void loadEvents() {
        eventsListContainer.getChildren().clear();
        var events = VaultManager.listEvents();
        eventsCountLabel.setText(String.valueOf(events.size()));
        if (events.isEmpty()) {
            Label empty = new Label(I18n.tr("dashboard.empty.events"));
            empty.getStyleClass().add("dash-empty-state");
            eventsListContainer.getChildren().add(empty);
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime now = LocalDateTime.now();

        // Only future/ongoing events appear in the dashboard summary; past
        // events are hidden to keep the list focused on what's next.
        events.stream()
                .filter(ev -> ev.getStartDateTime() == null || !ev.getStartDateTime().isBefore(now))
                .sorted(Comparator.comparing(
                        ev -> ev.getStartDateTime() == null ? LocalDateTime.MAX : ev.getStartDateTime()))
                .limit(MAX_DASHBOARD_ITEMS)
                .forEach(ev -> {
                    String when = ev.getStartDateTime() != null ? ev.getStartDateTime().format(fmt) : "No date";
                    String loc = ev.getLocation();
                    String subtitle = (loc != null && !loc.isBlank()) ? when + " · " + loc : when;
                    eventsListContainer.getChildren().add(createListRow(ev.getTitle(), subtitle));
                });

        if (eventsListContainer.getChildren().isEmpty()) {
            Label empty = new Label(I18n.tr("dashboard.empty.upcomingEvents"));
            empty.getStyleClass().add("dash-empty-state");
            eventsListContainer.getChildren().add(empty);
        }
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    /**
     * Carrega a lista de tarefas a partir do vault, limitada às
     * {@link #MAX_DASHBOARD_ITEMS} tarefas mais próximas (por deadline).
     * Mostra apenas as mais próximas para o cartão não transbordar a janela;
     * a contagem total continua a aparecer no badge.
     */
    private void loadTasks() {
        tasksListContainer.getChildren().clear();
        var tasks = VaultManager.listTasks();
        tasksCountLabel.setText(String.valueOf(tasks.size()));
        if (tasks.isEmpty()) {
            Label empty = new Label(I18n.tr("dashboard.empty.tasks"));
            empty.getStyleClass().add("dash-empty-state");
            tasksListContainer.getChildren().add(empty);
            return;
        }
        // Hide completed tasks; show upcoming (or undated) first so the
        // dashboard stays focused on what needs attention.
        tasks.stream()
                .filter(t -> t.getStatus() != domain.entities.TaskStatus.DONE)
                .sorted(Comparator.comparing(
                        t -> t.getDeadline() == null ? LocalDateTime.MAX : t.getDeadline()))
                .limit(MAX_DASHBOARD_ITEMS)
                .forEach(t -> {
                    String subtitle = t.getStatus() != null ? t.getStatus().getDisplayName() : "No status";
                    tasksListContainer.getChildren().add(createListRow(t.getTitle(), subtitle));
                });

        if (tasksListContainer.getChildren().isEmpty()) {
            Label empty = new Label(I18n.tr("dashboard.empty.openTasks"));
            empty.getStyleClass().add("dash-empty-state");
            tasksListContainer.getChildren().add(empty);
        }
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
        todayScheduleContainer.getChildren().clear();
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        int count = 0;

        for (Event ev : VaultManager.listEvents()) {
            if (ev.getStartDateTime() != null && ev.getStartDateTime().toLocalDate().equals(today)) {
                todayScheduleContainer.getChildren().add(
                        createListRow(ev.getTitle(), ev.getStartDateTime().format(fmt)));
                count++;
            }
        }
        for (Task t : VaultManager.listTasks()) {
            if (t.getDeadline() != null && t.getDeadline().toLocalDate().equals(today)) {
                todayScheduleContainer.getChildren().add(
                        createListRow(t.getTitle(), t.getDeadline().format(fmt)));
                count++;
            }
        }

        todayCountLabel.setText(String.valueOf(count));
        if (count == 0) {
            Label empty = new Label(I18n.tr("dashboard.empty.today"));
            empty.getStyleClass().add("dash-empty-state");
            todayScheduleContainer.getChildren().add(empty);
        }
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
     * Context Graph — desligado por agora. O desenho do grafo estava a gerar
     * bugs visuais, por isso a área mantém-se no layout mas fica em branco.
     * Não removemos o cartão para não alterar a identidade visual do AETHER.
     * Quando o grafo estiver estável, voltar a chamá-lo aqui.
     */
    private void renderContextGraph() {
        contextGraphContainer.getChildren().clear();
        Label placeholder = new Label(I18n.tr("dashboard.graph.title"));
        placeholder.getStyleClass().add("dash-graph-placeholder-text");
        Label hint = new Label(I18n.tr("dashboard.graph.hint"));
        hint.getStyleClass().add("dash-graph-hint");
        contextGraphContainer.getChildren().addAll(placeholder, hint);
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
     * <p>
     * Mantido para uso futuro quando o Context Graph voltar a ser desenhado.
     * </p>
     *
     * @param fullName o nome completo do utilizador
     * @return as iniciais (1 a 2 caracteres), maiúsculas
     */
    @SuppressWarnings("unused")
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
            currentWeek.getChildren().add(createCalendarDay(prevDays - i, prevMonth.atDay(prevDays - i), false, false));
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
            currentWeek.getChildren().add(createCalendarDay(day, displayedMonth.atDay(day), true, isToday));
            day++;
            currentDayOfWeek++;
        }

        int nextDay = 1;
        while (currentDayOfWeek < 7) {
            currentWeek.getChildren().add(createCalendarDay(nextDay, displayedMonth.plusMonths(1).atDay(nextDay), false, false));
            nextDay++;
            currentDayOfWeek++;
        }
        calendarGrid.getChildren().add(currentWeek);
    }

    /** Número máximo de chips de itens mostrados por dia (evita células gigantes). */
    private static final int MAX_CAL_ITEMS_PER_DAY = 3;

    /**
     * Cria uma célula de dia do calendário, com o número do dia e até
     * {@link #MAX_CAL_ITEMS_PER_DAY} indicadores de eventos/tarefas desse dia.
     * Os dados vêm do {@link util.VaultIndex} (cache central), não de uma segunda
     * fonte de verdade. Eventos e tarefas são visualmente distintos.
     *
     * @param day o número do dia
     * @param date a data real (para consultar o index); {@code null} para dias
     *            de outros meses (não mostram itens)
     * @param currentMonth se pertence ao mês atual
     * @param isToday se é o dia de hoje
     * @return a célula criada
     */
    private VBox createCalendarDay(int day, LocalDate date, boolean currentMonth, boolean isToday) {
        Label dayLabel = new Label(String.valueOf(day));
        dayLabel.getStyleClass().add("dash-cal-day");
        if (!currentMonth) {
            dayLabel.getStyleClass().add("dash-cal-day-other");
        }
        if (isToday) {
            dayLabel.getStyleClass().add("dash-cal-day-today");
        }
        dayLabel.setMaxWidth(Double.MAX_VALUE);
        dayLabel.setAlignment(javafx.geometry.Pos.CENTER);

        VBox cell = new VBox(dayLabel);
        cell.getStyleClass().add("dash-cal-cell");
        cell.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        cell.setSpacing(1);
        cell.setPrefWidth(28);

        if (date != null && currentMonth) {
            util.VaultIndex index = util.VaultIndex.getInstance();
            java.util.List<Event> dayEvents = index.eventsOn(date);
            java.util.List<Task> dayTasks = index.tasksDueOn(date);
            int shown = 0;
            for (Event e : dayEvents) {
                if (shown >= MAX_CAL_ITEMS_PER_DAY) break;
                cell.getChildren().add(createCalendarChip(e.getTitle(), "dash-cal-event"));
                shown++;
            }
            for (Task t : dayTasks) {
                if (shown >= MAX_CAL_ITEMS_PER_DAY) break;
                cell.getChildren().add(createCalendarChip(t.getTitle(), "dash-cal-task"));
                shown++;
            }
            int total = dayEvents.size() + dayTasks.size();
            cell.setOnMouseClicked(event -> showDayDetails(date, dayEvents, dayTasks));
            if (total > MAX_CAL_ITEMS_PER_DAY) {
                Label more = new Label("+" + (total - MAX_CAL_ITEMS_PER_DAY));
                more.getStyleClass().add("dash-cal-more");
                cell.getChildren().add(more);
            }
        }
        return cell;
    }

    /** Shows the existing day data when a calendar day is selected. */
    private void showDayDetails(LocalDate date, java.util.List<Event> events, java.util.List<Task> tasks) {
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        util.AetherDialogs.style(dialog);
        dialog.setTitle(date.toString());
        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(10));
        Label title = new Label(date.toString());
        title.getStyleClass().add("subtitulo-perfil");
        content.getChildren().add(title);
        if (events.isEmpty() && tasks.isEmpty()) {
            Label empty = new Label(util.I18n.tr("calendar.day.empty"));
            empty.getStyleClass().add("dash-empty-state");
            content.getChildren().add(empty);
        } else {
            if (!events.isEmpty()) {
                Label h = new Label(util.I18n.tr("calendar.events")); h.getStyleClass().add("dash-card-title-sm"); content.getChildren().add(h);
                for (Event e : events) {
                    Button item = new Button((e.getStartDateTime() == null ? "" : e.getStartDateTime().toLocalTime() + "  ") + e.getTitle());
                    item.getStyleClass().add("search-result-cell"); item.setMaxWidth(Double.MAX_VALUE);
                    item.setOnAction(ev -> { dialog.close(); if (DashboardController.getActive() != null) DashboardController.getActive().showEvents(); });
                    content.getChildren().add(item);
                }
            }
            if (!tasks.isEmpty()) {
                Label h = new Label(util.I18n.tr("calendar.tasks")); h.getStyleClass().add("dash-card-title-sm"); content.getChildren().add(h);
                for (Task t : tasks) {
                    Button item = new Button((t.getDeadline() == null ? "" : t.getDeadline().toLocalTime() + "  ") + t.getTitle());
                    item.getStyleClass().add("search-result-cell"); item.setMaxWidth(Double.MAX_VALUE);
                    item.setOnAction(ev -> { dialog.close(); if (DashboardController.getActive() != null) DashboardController.getActive().showTasks(); });
                    content.getChildren().add(item);
                }
            }
        }
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /**
     * Cria um chip compacto para um item do calendário (evento ou tarefa).
     *
     * @param title o título do item (truncado)
     * @param styleClass a classe CSS que distingue evento de tarefa
     * @return o chip criado
     */
    private Label createCalendarChip(String title, String styleClass) {
        String text = title == null ? "" : title.trim();
        if (text.length() > 16) {
            text = text.substring(0, 16) + "…";
        }
        Label chip = new Label(text);
        chip.getStyleClass().add(styleClass);
        chip.setMaxWidth(Double.MAX_VALUE);
        return chip;
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
