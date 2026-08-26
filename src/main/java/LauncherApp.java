import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.Objects;

public class LauncherApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            // 1. Carregar a interface FXML principal
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/LauncherApp.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 1280, 800);

            // 2. Adicionar o logótipo ao ícone da aplicação
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            } else {
                System.err.println("[AETHER] Logótipo não encontrado no caminho '/images/logo.png'.");
            }

            // 3. Configurar e exibir a janela
            primaryStage.setTitle("AETHER");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);
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