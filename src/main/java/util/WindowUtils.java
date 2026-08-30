package util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Utilitários para criação de janelas JavaFX com a moldura Glassmorphism do AETHER.
 * <p>
 * Fornece um método centralizado que envolve qualquer conteúdo numa janela sem
 * bordas nativas, com barra de título própria, botões de controlo (fechar,
 * minimizar e maximizar) e suporte a arrastar a janela pelo cabeçalho.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class WindowUtils {

    /** Espaçamento horizontal entre os botões de controlo da janela. */
    private static final double CONTROLS_SPACING = 8.0;

    /** Margens internas da barra de título. */
    private static final Insets HEADER_PADDING = new Insets(12, 16, 12, 16);

    /**
     * Classe CSS aplicada ao contentor da janela enquanto está maximizada:
     * remove cantos arredondados, borda e sombra, para a janela preencher o
     * ecrã como qualquer janela maximizada normal.
     * <p>
     * Não se usa {@link Stage#setMaximized(boolean)} nesta janela: em palcos
     * {@link StageStyle#TRANSPARENT} sem decoração nativa, o JavaFX calcula
     * mal os limites da maximização (conta com margens de moldura que aqui
     * não existem), o que faz a janela ficar cortada, desalinhada ou maior
     * do que o ecrã ao maximizar/restaurar — é isto que se via como as
     * janelas a "desformatarem". Em vez disso, o próprio {@link WindowUtils}
     * guarda a posição/tamanho anteriores e ajusta o palco manualmente aos
     * limites visíveis do ecrã, o que é fiável em qualquer sistema.
     * </p>
     */
    private static final String MAXIMIZED_STYLE_CLASS = "glass-window-container-maximized";

    /**
     * Construtor privado: esta é uma classe utilitária e não deve ser instanciada.
     */
    private WindowUtils() {
        // Classe utilitária.
    }

    /**
     * Transforma um {@link Stage} numa janela Glassmorphism sem bordas nativas.
     * <p>
     * O conteúdo recebido é colocado no centro de um {@link BorderPane}, cujo
     * topo é a barra de título personalizada. Guardar a referência deste
     * contentor permite trocar apenas o centro ao navegar entre ecrãs
     * (ver {@link Navigator}).
     * </p>
     *
     * @param stage o palco a transformar; não pode ser {@code null}
     * @param content o nó de conteúdo a apresentar no centro da janela
     * @param title o título apresentado na barra superior
     * @return a cena configurada, com fundo transparente e a folha de estilos aplicada
     * @throws IllegalArgumentException se {@code stage} for {@code null}
     */
    public static Scene makeGlassWindow(Stage stage, Parent content, String title) {
        if (stage == null) {
            throw new IllegalArgumentException("O Stage não pode ser nulo.");
        }

        applyTransparentStyle(stage);

        BorderPane rootContainer = new BorderPane();
        rootContainer.getStyleClass().add("glass-window-container");
        rootContainer.setTop(createHeaderBar(stage, title, rootContainer));
        rootContainer.setCenter(content);

        Scene scene = new Scene(rootContainer);
        scene.setFill(Color.TRANSPARENT);
        StyleUtils.applyTo(scene);

        return scene;
    }

    /**
     * Aplica o estilo transparente ao palco, se ainda for possível.
     * <p>
     * O JavaFX só permite {@code initStyle} antes de o palco ser mostrado; após
     * isso a chamada lança exceção, que aqui é ignorada por ser inofensiva (a
     * janela já tem o estilo pretendido).
     * </p>
     *
     * @param stage o palco a configurar
     */
    private static void applyTransparentStyle(Stage stage) {
        if (stage.getStyle() == StageStyle.TRANSPARENT || stage.isShowing()) {
            return;
        }
        try {
            stage.initStyle(StageStyle.TRANSPARENT);
        } catch (IllegalStateException ignored) {
            // O palco já foi mostrado: mantém o estilo atual.
        }
    }

    /**
     * Constrói a barra de título personalizada, com botões de controlo e
     * capacidade de arrastar a janela.
     *
     * @param stage o palco controlado pela barra
     * @param title o título a apresentar ao centro
     * @param windowRoot o contentor raiz da janela, para alternar o estilo
     *                   "maximizada" (ver {@link #MAXIMIZED_STYLE_CLASS})
     * @return a barra de título pronta a usar
     */
    private static BorderPane createHeaderBar(Stage stage, String title, BorderPane windowRoot) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("glass-window-title");

        Button closeButton = createWindowDot("dot-close", "Fechar");
        closeButton.setOnAction(event -> stage.close());

        Button minimizeButton = createWindowDot("dot-minimize", "Minimizar");
        minimizeButton.setOnAction(event -> stage.setIconified(true));

        // Estado de maximização e posição/tamanho anteriores, guardados por
        // janela através deste closure (o mesmo padrão já usado abaixo para
        // o arrasto da janela).
        boolean[] maximized = {false};
        double[] restoreBounds = new double[4];

        Button maximizeButton = createWindowDot("dot-maximize", "Maximizar");
        maximizeButton.setOnAction(event -> toggleMaximize(stage, windowRoot, maximized, restoreBounds));

        HBox controlsBox = new HBox(CONTROLS_SPACING, closeButton, minimizeButton, maximizeButton);
        controlsBox.setAlignment(Pos.CENTER_LEFT);

        BorderPane headerBar = new BorderPane();
        headerBar.setLeft(controlsBox);
        headerBar.setCenter(titleLabel);
        headerBar.setPadding(HEADER_PADDING);
        headerBar.getStyleClass().add("glass-header-bar");

        // Duplo-clique na barra de título também alterna maximizar, como é
        // hábito noutras janelas.
        headerBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                toggleMaximize(stage, windowRoot, maximized, restoreBounds);
            }
        });

        enableWindowDragging(stage, headerBar, maximized);
        return headerBar;
    }

    /**
     * Alterna manualmente entre maximizado e restaurado, sem recorrer a
     * {@link Stage#setMaximized(boolean)} (ver {@link #MAXIMIZED_STYLE_CLASS}
     * para o porquê).
     *
     * @param stage o palco a maximizar ou restaurar
     * @param windowRoot o contentor raiz, para ligar/desligar o estilo
     *                   "maximizada"
     * @param maximized estado atual, partilhado com o resto da janela
     * @param restoreBounds {@code [x, y, width, height]} anteriores, para
     *                      restaurar ao sair do estado maximizado
     */
    private static void toggleMaximize(Stage stage, BorderPane windowRoot, boolean[] maximized, double[] restoreBounds) {
        if (maximized[0]) {
            stage.setX(restoreBounds[0]);
            stage.setY(restoreBounds[1]);
            stage.setWidth(restoreBounds[2]);
            stage.setHeight(restoreBounds[3]);
            windowRoot.getStyleClass().remove(MAXIMIZED_STYLE_CLASS);
            maximized[0] = false;
        } else {
            restoreBounds[0] = stage.getX();
            restoreBounds[1] = stage.getY();
            restoreBounds[2] = stage.getWidth();
            restoreBounds[3] = stage.getHeight();

            Rectangle2D visualBounds = Screen.getScreensForRectangle(
                            stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                    .stream().findFirst().orElse(Screen.getPrimary())
                    .getVisualBounds();

            stage.setX(visualBounds.getMinX());
            stage.setY(visualBounds.getMinY());
            stage.setWidth(visualBounds.getWidth());
            stage.setHeight(visualBounds.getHeight());
            windowRoot.getStyleClass().add(MAXIMIZED_STYLE_CLASS);
            maximized[0] = true;
        }
    }

    /**
     * Cria um botão circular de controlo da janela ao estilo macOS.
     *
     * @param variantStyleClass a classe CSS da variante de cor (ex.: {@code dot-close})
     * @param tooltipText o texto descritivo para acessibilidade
     * @return o botão configurado
     */
    private static Button createWindowDot(String variantStyleClass, String tooltipText) {
        Button dot = new Button();
        dot.getStyleClass().addAll("window-dot", variantStyleClass);
        dot.setAccessibleText(tooltipText);
        dot.setFocusTraversable(false);
        return dot;
    }

    /**
     * Permite mover a janela arrastando o nó indicado.
     * <p>
     * O deslocamento inicial do cursor é guardado num array local a esta janela,
     * evitando estado partilhado entre várias janelas abertas.
     * </p>
     *
     * @param stage o palco a mover
     * @param dragHandle o nó que funciona como área de arrasto
     * @param maximized estado de maximização gerido manualmente por
     *                  {@link #toggleMaximize}; o arrasto fica desativado
     *                  enquanto for {@code true}, tal como aconteceria com
     *                  {@link Stage#isMaximized()}
     */
    private static void enableWindowDragging(Stage stage, Node dragHandle, boolean[] maximized) {
        final double[] dragOffset = new double[2];

        dragHandle.setOnMousePressed(event -> {
            dragOffset[0] = event.getSceneX();
            dragOffset[1] = event.getSceneY();
        });

        dragHandle.setOnMouseDragged(event -> {
            if (!maximized[0]) {
                stage.setX(event.getScreenX() - dragOffset[0]);
                stage.setY(event.getScreenY() - dragOffset[1]);
            }
        });
    }
}
