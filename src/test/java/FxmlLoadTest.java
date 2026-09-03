import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Diagnostic test: loads each onboarding + dashboard FXML headlessly to surface
 * the real exception thrown when navigating from setup step 3 to the dashboard.
 */
class FxmlLoadTest {

    @BeforeAll
    static void initToolkit() {
        Platform.startup(() -> {});
    }

    private void load(String path) {
        URL url = FXMLLoader.class.getResource(path);
        assertNotNull(url, "FXML resource missing: " + path);
        try {
            FXMLLoader.load(url);
            System.out.println("OK   " + path);
        } catch (Throwable t) {
            System.out.println("FAIL " + path + " -> " + t);
            Throwable c = t.getCause();
            while (c != null) {
                System.out.println("  cause: " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
            t.printStackTrace(System.out);
        }
    }

    @Test
    void loadAll() {
        load("/FXML/profile.fxml");
        load("/FXML/ollama_setup.fxml");
        load("/FXML/obsidian_setup.fxml");
        load("/FXML/dashboard.fxml");
        load("/FXML/dashboard_view.fxml");
        load("/FXML/settings.fxml");
        load("/FXML/aether_ai.fxml");
    }
}
