package util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class WindowUtils {

    private static double xOffset = 0;
    private static double yOffset = 0;

    /**
     * Transforma qualquer Stage numa janela Glassmorphism sem bordas nativas.
     */
    public static Scene makeGlassWindow(Stage stage, Parent content, String title) {
        // 1. Aplica transparência na Stage
        if (stage.getStyle() != StageStyle.TRANSPARENT) {
            try {
                stage.initStyle(StageStyle.TRANSPARENT);
            } catch (Exception ignored) {}
        }

        // 2. Título minimalista
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("glass-window-title");

        // 3. Botões estilo "Traffic Light" / iOS minimal
        Button btnClose = new Button();
        btnClose.getStyleClass().addAll("window-dot", "dot-close");
        btnClose.setOnAction(e -> stage.close());

        Button btnMin = new Button();
        btnMin.getStyleClass().addAll("window-dot", "dot-minimize");
        btnMin.setOnAction(e -> stage.setIconified(true));

        Button btnMax = new Button();
        btnMax.getStyleClass().addAll("window-dot", "dot-maximize");
        btnMax.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));

        HBox controlsBox = new HBox(8, btnClose, btnMin, btnMax);
        controlsBox.setAlignment(Pos.CENTER_LEFT);

        // 4. Barra Superior (Header de Vidro)
        BorderPane headerBar = new BorderPane();
        headerBar.setLeft(controlsBox);
        headerBar.setCenter(titleLabel);
        headerBar.setPadding(new Insets(12, 16, 12, 16));
        headerBar.getStyleClass().add("glass-header-bar");

        // Drag & Drop para mover a janela
        headerBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });

        headerBar.setOnMouseDragged(event -> {
            if (!stage.isMaximized()) {
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            }
        });

        // 5. Estrutura Principal
        BorderPane rootContainer = new BorderPane();
        rootContainer.getStyleClass().add("glass-window-container");
        rootContainer.setTop(headerBar);
        rootContainer.setCenter(content);

        Scene scene = new Scene(rootContainer);
        scene.setFill(Color.TRANSPARENT);

        // Injeta o CSS global automaticamente em todas as janelas criadas
        if (WindowUtils.class.getResource("/Style.css") != null) {
            scene.getStylesheets().add(WindowUtils.class.getResource("/Style.css").toExternalForm());
        }

        return scene;
    }
}