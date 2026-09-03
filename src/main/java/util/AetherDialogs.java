package util;

import java.time.LocalDate;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;

/**
 * AetherDialogs — helpers partilhados para diálogos com o visual do AETHER.
 * <p>
 * Centraliza a aplicação da classe {@code .custom-dialog-pane} (fundo escuro,
 * botões estilizados) e dos labels de formulário ({@code .label-campo}) aos
 * diálogos de criação de entidades, para que deixem de ter o aspeto padrão
 * (claro) do JavaFX e fiquem visualmente coerentes com o AETHER. Inclui também
 * o modal "Add Note" partilhado entre o dashboard e a vista de Notas.
 * </p>
 * <p>
 * Reutiliza as classes CSS já existentes ({@code .subtitulo-perfil},
 * {@code .descricao-perfil}, {@code .campo-texto}, {@code .area-texto}) e o
 * {@link StyleUtils} — não introduz um novo sistema de diálogo nem duplica
 * lógica de notas.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class AetherDialogs {

    private AetherDialogs() {
        // Classe utilitária.
    }

    /**
     * Aplica o visual do AETHER a um {@link Dialog}: folha de estilos global +
     * classe {@code custom-dialog-pane} (fundo escuro, cantos arredondados,
     * botões primários em índigo) no {@link DialogPane}, e torna a janela do
     * diálogo transparente/sem moldura nativa.
     * <p>
     * [CORRIGIDO] Antes só estilizava o {@link DialogPane}; a janela por trás
     * continuava a ser uma janela normal do sistema operativo (com barra de
     * título branca), visível por cima/à volta do painel escuro — daí a tira
     * branca no topo dos diálogos "Add Event", "Add Note", "Add Person", etc.
     * Agora, tal como o resto da AETHER (janela principal sem moldura), o
     * diálogo usa {@link StageStyle#TRANSPARENT} com a cena transparente, para
     * que só o cartão em vidro (com os seus próprios cantos arredondados e
     * sombra) fique visível.
     * </p>
     *
     * @param dialog o diálogo a estilizar
     */
    public static void style(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        DialogPane pane = dialog.getDialogPane();
        StyleUtils.applyTo(pane);
        if (!pane.getStyleClass().contains("custom-dialog-pane")) {
            pane.getStyleClass().add("custom-dialog-pane");
        }

        // [CORRIGIDO] NÃO usar pane.setStyle("-fx-background-color: transparent")
        // aqui: um estilo definido via setStyle() tem sempre prioridade sobre
        // QUALQUER regra de folha de estilos para a mesma propriedade — isso
        // anularia por completo o fundo em vidro definido em ".custom-dialog-pane"
        // (Style.css), deixando só a borda/sombra visíveis e o interior
        // totalmente transparente. É exatamente essa combinação (moldura visível,
        // interior a ver o que está por trás) que dá o aspeto "muito transparente
        // e confuso". A classe CSS já define um fundo opaco e cantos
        // arredondados — só é preciso tornar a CENA por trás transparente, para
        // a área fora do cartão (retângulo da janela) não aparecer sólida.
        dialog.initStyle(StageStyle.TRANSPARENT);
        if (pane.getScene() != null) {
            pane.getScene().setFill(Color.TRANSPARENT);
        } else {
            pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    newScene.setFill(Color.TRANSPARENT);
                }
            });
        }
    }

    /**
     * Cria um {@link Label} de campo de formulário com o estilo do AETHER
     * ({@code .label-campo}), para que os rótulos não apareçam a preto sobre o
     * fundo escuro do diálogo.
     *
     * @param text o texto do rótulo
     * @return o label estilizado
     */
    public static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("label-campo");
        return label;
    }

    /**
     * Cria um {@link DatePicker} com o visual em vidro da AETHER (campo e
     * popup do calendário escuros), pronto a colocar num formulário.
     * <p>
     * [ADICIONADO] Substitui os antigos campos de texto livre para datas
     * (ex.: "Date (yyyy-MM-dd)", "Birthday (d/M/yyyy)"), que obrigavam o
     * utilizador a escrever a data à mão num formato exato. Agora basta
     * clicar no campo e escolher o dia num calendário, tal como pedido.
     * </p>
     *
     * @return o {@link DatePicker} estilizado, com largura preferida de 320px
     */
    public static DatePicker datePicker() {
        DatePicker picker = new DatePicker();
        picker.setPromptText("Select a date");
        picker.getStyleClass().add("date-picker-glass");
        picker.setPrefWidth(320);
        return picker;
    }

    /**
     * Igual a {@link #datePicker()}, mas desativa visualmente os dias futuros
     * no calendário — pensado para campos de data de nascimento, onde uma
     * data futura nunca é válida.
     *
     * @return o {@link DatePicker} estilizado, com os dias futuros desativados
     */
    public static DatePicker pastDatePicker() {
        DatePicker picker = datePicker();
        picker.setDayCellFactory(dp -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                if (date != null && date.isAfter(LocalDate.now())) {
                    setDisable(true);
                    setStyle("-fx-opacity: 0.35;");
                }
            }
        });
        return picker;
    }

    /**
     * Abre o modal "Add Note" partilhado: pequeno, polido, com título, área de
     * texto e botões Save/Cancel, no visual do AETHER. Usado tanto pelo botão
     * "+ Add note" do cabeçalho do dashboard como pela vista de Notas.
     *
     * @return o texto da nota, ou empty se cancelado/vazio
     */
    public static Optional<String> showAddNoteDialog() {
        Dialog<String> dialog = new Dialog<>();
        DialogPane pane = dialog.getDialogPane();
        style(dialog);

        Label titleLabel = new Label("Add Note");
        titleLabel.getStyleClass().add("subtitulo-perfil");

        Label subtitleLabel = new Label(
                "Write a note in natural language. AETHER will extract people, events, and tasks automatically.");
        subtitleLabel.getStyleClass().add("descricao-perfil");
        subtitleLabel.setWrapText(true);

        TextArea noteArea = new TextArea();
        noteArea.setPromptText("e.g. Meeting with João about the AETHER project tomorrow at 10:00");
        noteArea.getStyleClass().add("area-texto");
        noteArea.setPrefRowCount(4);
        noteArea.setPrefWidth(380);

        VBox content = new VBox(12);
        content.setPadding(new Insets(10, 4, 10, 4));
        content.getChildren().addAll(titleLabel, subtitleLabel, noteArea);

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(cancelType, saveType);
        pane.setContent(content);

        Platform.runLater(noteArea::requestFocus);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            String text = noteArea.getText().trim();
            return text.isBlank() ? null : text;
        });

        return dialog.showAndWait();
    }

    /**
     * Mostra um diálogo de confirmação de eliminação no visual do AETHER.
     * O botão de confirmação está rotulado como "Delete" para que a ação seja
     * inequívoca.
     *
     * @param name o nome/título da entidade a eliminar (mostrado no cabeçalho)
     * @return {@code true} se o utilizador confirmou a eliminação
     */
    public static boolean confirmDelete(String name) {
        Alert alert = new Alert(AlertType.CONFIRMATION);
        DialogPane pane = alert.getDialogPane();
        style(alert);
        alert.setTitle("Delete");
        alert.setHeaderText("Delete \"" + (name == null || name.isBlank() ? "this item" : name) + "\"?");
        alert.setContentText("This action cannot be undone.");
        ButtonType deleteType = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(cancelType, deleteType);
        Optional<ButtonType> result = alert.showAndWait();
        return result.filter(r -> r == deleteType).isPresent();
    }

    /**
     * Cria um botão "Delete" pequeno e estilizado para colocar num cartão de
     * entidade. Ao clicar, mostra a confirmação e, se confirmada, executa o
     * {@code onDelete}. Consome o evento para não disparar o clique do cartão
     * (que abre o diálogo de edição).
     *
     * @param name     o nome/título da entidade (para a mensagem de confirmação)
     * @param onDelete a ação a executar após confirmação
     * @return o botão configurado
     */
    public static Button deleteButton(String name, Runnable onDelete) {
        Button button = new Button("Delete");
        button.getStyleClass().addAll("botao-perigo", "button-small");
        button.setOnAction(event -> {
            event.consume();
            if (confirmDelete(name)) {
                onDelete.run();
            }
        });
        return button;
    }
}
