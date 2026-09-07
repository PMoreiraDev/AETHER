package util;

import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import util.I18n;
import javafx.stage.Window;

/**
 * Navegação entre ecrãs do AETHER dentro da mesma janela Glassmorphism.
 * <p>
 * Todos os ecrãs de onboarding partilham a mesma moldura: só o conteúdo central
 * muda. Esta classe concentra essa lógica num único ponto, para que os
 * controladores não a repitam. Se a janela ainda não tiver a moldura de vidro,
 * ela é criada automaticamente através de {@link WindowUtils}.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class Navigator {

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(Navigator.class.getName());

    /** Título apresentado na barra de janela do AETHER. */
    private static final String WINDOW_TITLE = "AETHER";

    /**
     * Construtor privado: esta é uma classe utilitária e não deve ser instanciada.
     */
    private Navigator() {
        // Classe utilitária.
    }

    /**
     * Carrega um ecrã FXML e apresenta-o na janela a que o nó de origem pertence.
     *
     * @param origin qualquer nó do ecrã atual, usado para localizar a janela
     * @param fxmlPath caminho do FXML no classpath (ex.: {@code /FXML/profile.fxml})
     * @return {@code true} se a navegação foi concluída, {@code false} em caso de erro
     */
    public static boolean navigate(Node origin, String fxmlPath) {
        Stage stage = findStage(origin);
        if (stage == null) {
            LOGGER.warning(() -> "Navegação para '" + fxmlPath + "' cancelada: janela indisponível.");
            return false;
        }

        Parent view = loadView(fxmlPath);
        if (view == null) {
            return false;
        }

        showInGlassFrame(stage, view);
        return true;
    }

    /**
     * Carrega um ecrã FXML a partir do classpath.
     *
     * @param fxmlPath caminho do FXML no classpath
     * @return a raiz do ecrã carregado, ou {@code null} se o recurso não existir ou falhar
     */
    private static Parent loadView(String fxmlPath) {
        URL resource = Navigator.class.getResource(fxmlPath);
        if (resource == null) {
            LOGGER.severe(() -> "Ficheiro FXML não encontrado no classpath: '" + fxmlPath + "'.");
            return null;
        }
        try {
            FXMLLoader loader = new FXMLLoader(resource, I18n.getBundle());
            return loader.load();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Falha ao carregar o ecrã '" + fxmlPath + "'.", e);
            return null;
        }
    }

    /**
     * Coloca a vista dentro da moldura de vidro da janela.
     * <p>
     * Quando a cena atual já tem a moldura ({@link BorderPane} raiz), apenas o
     * centro é substituído, preservando a barra de título e a posição da janela.
     * Caso contrário, é criada uma nova cena com moldura.
     * </p>
     *
     * @param stage a janela de destino
     * @param view a vista a apresentar
     */
    private static void showInGlassFrame(Stage stage, Parent view) {
        Scene currentScene = stage.getScene();
        if (currentScene != null && currentScene.getRoot() instanceof BorderPane glassContainer) {
            glassContainer.setCenter(view);
        } else {
            stage.setScene(WindowUtils.makeGlassWindow(stage, view, WINDOW_TITLE));
        }
    }

    /**
     * Obtém, de forma segura, o {@link Stage} associado a um nó.
     *
     * @param node o nó a inspecionar; pode ser {@code null}
     * @return o palco correspondente, ou {@code null} se ainda não estiver disponível
     */
    public static Stage findStage(Node node) {
        if (node == null || node.getScene() == null) {
            return null;
        }
        Window window = node.getScene().getWindow();
        return window instanceof Stage stage ? stage : null;
    }
}
