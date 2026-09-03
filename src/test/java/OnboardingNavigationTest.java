import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import util.Navigator;
import util.WindowUtils;

import java.net.URL;

/**
 * Simulates the real onboarding step-3 -> dashboard navigation to surface the
 * actual exception thrown when the user clicks "Finish".
 */
class OnboardingNavigationTest {

    private static Stage stage;

    @BeforeAll
    static void init() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized by another test in the same JVM.
        }
        // Use a fresh temp data dir so the vault starts absent (like a real
        // first-run user who hasn't created the vault yet).
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("aether-onboarding-test");
        System.setProperty("aether.data.dir", tmp.toString());
    }

    @AfterAll
    static void teardown() {
        Platform.exit();
    }

    @Test
    void finishStep3_opensDashboard() throws Exception {
        URL url = FXMLLoader.class.getResource("/FXML/obsidian_setup.fxml");
        Parent onboardingRoot = FXMLLoader.load(url);

        // Put it on a real shown stage (glass frame), like the running app.
        Platform.runLater(() -> {
            stage = new Stage();
            Scene scene = WindowUtils.makeGlassWindow(stage, onboardingRoot, "AETHER");
            stage.setScene(scene);
            stage.show();
        });
        Thread.sleep(800); // allow initialize() (obsidian check) to run

        // Find the root pane (fx:id="rootPane"), used by finishSetup as the
        // navigation origin (the Skip button has no fx:id, so it was null).
        Node origin = onboardingRoot.lookup("#rootPane");
        System.out.println("rootPane found: " + (origin != null));

        // Reproduce ObsidianSetupController.finishSetup():
        //   Navigator.navigate(skipButton, "/FXML/dashboard.fxml");
        Throwable[] err = new Throwable[1];
        Platform.runLater(() -> {
            try {
                boolean ok = Navigator.navigate(origin, "/FXML/dashboard.fxml");
                System.out.println("navigate returned: " + ok);
            } catch (Throwable t) {
                err[0] = t;
            }
        });
        Thread.sleep(1500);

        if (err[0] != null) {
            System.out.println("NAVIGATION THREW: " + err[0]);
            err[0].printStackTrace(System.out);
        } else {
            Scene sc = stage.getScene();
            System.out.println("scene root class: " + (sc != null ? sc.getRoot().getClass().getName() : "null"));
            if (sc != null && sc.getRoot() instanceof BorderPane bp) {
                System.out.println("center class: " + (bp.getCenter() != null ? bp.getCenter().getClass().getName() : "null"));
            }
        }
    }
}
