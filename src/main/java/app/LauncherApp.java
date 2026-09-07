package app;

import java.io.InputStream;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import persistence.Database;
import session.UserSession;
import util.WindowUtils;

/**
 * Ponto de entrada da aplicação AETHER.
 * <p>
 * O arranque mostra sempre o ecrã Start. A decisão entre dashboard e setup
 * é tomada quando o utilizador clica em Start — ver
 * {@link controller.LauncherController#handleStartAether(javafx.event.ActionEvent)}.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.1
 */
public class LauncherApp extends Application {

    /**
     * Cria o launcher. Instanciado pelo runtime do JavaFX ao arrancar a aplicação.
     */
    public LauncherApp() {
        // Construtor por omissão explícito, documentado para o Javadoc.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(LauncherApp.class.getName());

    /** Caminho do ecrã de arranque no classpath. */
    private static final String LAUNCHER_VIEW = "/FXML/LauncherApp.fxml";

    /** Caminho do ícone da aplicação no classpath. */
    private static final String APP_ICON = "/images/logo.png";

    /** Título da janela principal. */
    private static final String WINDOW_TITLE = "AETHER";

    /** Largura inicial da janela, em pixéis. */
    private static final double INITIAL_WIDTH = 1280;

    /** Altura inicial da janela, em pixéis. */
    private static final double INITIAL_HEIGHT = 800;

    /** Largura mínima permitida da janela, em pixéis. */
    private static final double MIN_WIDTH = 900;

    /** Altura mínima permitida da janela, em pixéis. */
    private static final double MIN_HEIGHT = 600;

    /**
     * Inicializa a janela principal mostrando sempre o ecrã de arranque.
     * <p>
     * A decisão entre ir para o dashboard (onboarding concluído) ou para o
     * setup (onboarding por concluir) é tomada no botão Start, não aqui.
     * </p>
     *
     * @param primaryStage o palco principal fornecido pelo JavaFX
     */
    @Override
    public void start(Stage primaryStage) {
        try {
            handleDevelopmentReset();

            // Start monitoring the vault for external (Obsidian) edits.
            util.VaultFileWatcher.getInstance().start();
            Runtime.getRuntime().addShutdownHook(new Thread(util.VaultFileWatcher.getInstance()::stop));

            Parent root = loadLauncherView();

            Scene scene = WindowUtils.makeGlassWindow(primaryStage, root, WINDOW_TITLE);

            applyIcon(primaryStage);

            primaryStage.setTitle(WINDOW_TITLE);
            primaryStage.setScene(scene);
            primaryStage.setWidth(INITIAL_WIDTH);
            primaryStage.setHeight(INITIAL_HEIGHT);
            primaryStage.setMinWidth(MIN_WIDTH);
            primaryStage.setMinHeight(MIN_HEIGHT);
            primaryStage.setResizable(true);
            primaryStage.centerOnScreen();
            primaryStage.show();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Falha ao iniciar a aplicação AETHER.", e);
        }
    }

    /**
     * Resets local application data when the developer explicitly enables the reset flag.
     */
    private void handleDevelopmentReset() {
        if (Boolean.parseBoolean(System.getProperty("aether.dev.reset", "false"))) {
            Database.resetForDevelopment();

            // LauncherController treats this flag as authoritative for the
            // current launch. The database reset keeps the stored profile but
            // resets the persisted onboarding state.
            LOGGER.info("Development reset enabled: onboarding state was reset; user profile data was preserved.");
        }
    }

    /**
     * Carrega o ecrã de arranque a partir do classpath.
     *
     * @return a raiz da vista do launcher
     * @throws Exception se o recurso FXML não existir ou não puder ser carregado
     */
    private Parent loadLauncherView() throws Exception {
        URL fxmlUrl = getClass().getResource(LAUNCHER_VIEW);
        if (fxmlUrl == null) {
            throw new IllegalStateException("Ficheiro FXML não encontrado em '" + LAUNCHER_VIEW + "'.");
        }
        return new FXMLLoader(fxmlUrl).load();
    }

    /**
     * Define o ícone da janela, se a imagem existir no classpath.
     * <p>
     * A ausência do ícone não é um erro crítico: a aplicação continua a arrancar
     * com o ícone por omissão do sistema.
     * </p>
     *
     * @param stage o palco onde o ícone é aplicado
     */
    private void applyIcon(Stage stage) {
        try (InputStream iconStream = getClass().getResourceAsStream(APP_ICON)) {
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            } else {
                LOGGER.warning(() -> "Ícone da aplicação não encontrado em '" + APP_ICON + "'.");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Não foi possível carregar o ícone da aplicação.", e);
        }
    }

    /**
     * Arranca a aplicação JavaFX.
     *
     * @param args argumentos da linha de comandos, encaminhados para o JavaFX
     */
    public static void main(String[] args) {
        launch(args);
    }
}
