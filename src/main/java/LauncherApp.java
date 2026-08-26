import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;

public class LauncherApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            // 1. Carregar o FXML principal
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/LauncherApp.fxml"));
            Parent root = loader.load();

            // 2. Definir a dimensão inicial padrão (1280x800)
            Scene scene = new Scene(root, 1280, 800);

            // 3. Carregar o ícone da aplicação
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            } else {
                System.err.println("[AETHER] Logótipo não encontrado no caminho '/images/logo.png'.");
            }

            // 4. Configurar a janela
            primaryStage.setTitle("AETHER");
            primaryStage.setScene(scene);

            // Garantir proporções mínimas e permitir redimensionar / full screen
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