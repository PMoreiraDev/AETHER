import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import util.WindowUtils;

import java.io.InputStream;

public class LauncherApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            // 1. Carregar o FXML principal
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/LauncherApp.fxml"));
            Parent root = loader.load();

            // 2. Criar a cena sem bordas com o método makeGlassWindow
            Scene scene = WindowUtils.makeGlassWindow(primaryStage, root, "AETHER");

            // 3. Carregar o ícone da aplicação
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            } else {
                System.err.println("[AETHER] Logótipo não encontrado no caminho '/images/logo.png'.");
            }

            // 4. Configurar e apresentar a janela
            primaryStage.setTitle("AETHER");
            primaryStage.setScene(scene);

            primaryStage.setWidth(1280);
            primaryStage.setHeight(800);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);
            primaryStage.setResizable(true);

            primaryStage.centerOnScreen();
            primaryStage.show();

        } catch (Exception e) {
            System.err.println("[AETHER Erro] Falha ao iniciar a aplicação:");
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}