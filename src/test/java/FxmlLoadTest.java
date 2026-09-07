import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic test: loads each onboarding + dashboard FXML headlessly to surface
 * the real exception thrown when navigating between screens.
 * <p>
 * IMPORTANT: this test FAILS if any FXML cannot be loaded. A previous version
 * swallowed load exceptions and always reported green, which masked a real bug
 * (dashboard.fxml failed to load because &lt;TextField&gt; was used without an
 * &lt;?import?&gt;). That made both "Start" (existing config) and "Finish" (step 3)
 * do nothing at runtime. This test now asserts that every screen loads.
 * </p>
 */
class FxmlLoadTest {

    private final List<String> failures = new ArrayList<>();

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already initialized by another test in the same JVM.
        }
    }

    /** Loads an FXML that has NO %key references (no ResourceBundle needed). */
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
            failures.add(path + ": " + rootMessage(t));
        }
    }

    /** Loads an FXML that uses %key references, passing the I18n ResourceBundle. */
    private void loadWithBundle(String path) {
        URL url = FXMLLoader.class.getResource(path);
        assertNotNull(url, "FXML resource missing: " + path);
        try {
            FXMLLoader loader = new FXMLLoader(url);
            loader.setResources(ResourceBundle.getBundle("i18n.messages",
                    new java.util.Locale("pt", "PT")));
            loader.load();
            System.out.println("OK   " + path + " (with bundle)");
        } catch (Throwable t) {
            System.out.println("FAIL " + path + " -> " + t);
            failures.add(path + ": " + rootMessage(t));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    @Test
    void loadAll() {
        // Onboarding screens (no %key references).
        load("/FXML/profile.fxml");
        load("/FXML/ollama_setup.fxml");
        load("/FXML/obsidian_setup.fxml");
        // Dashboard shell — uses %key i18n references, loaded in production
        // with the I18n ResourceBundle (via Navigator), so we test it the same way.
        loadWithBundle("/FXML/dashboard.fxml");
        loadWithBundle("/FXML/dashboard_view.fxml");
        loadWithBundle("/FXML/people_view.fxml");
        loadWithBundle("/FXML/projects_view.fxml");
        loadWithBundle("/FXML/tasks_view.fxml");
        loadWithBundle("/FXML/events_view.fxml");
        loadWithBundle("/FXML/notes_view.fxml");
        loadWithBundle("/FXML/note_editor.fxml");
        // Screens that use %key i18n references — loaded in production with the
        // I18n ResourceBundle, so we test them the same way.
        loadWithBundle("/FXML/settings.fxml");
        load("/FXML/aether_ai.fxml");
        load("/FXML/LauncherApp.fxml");

        assertTrue(failures.isEmpty(),
                "Some FXML screens failed to load (these break navigation at runtime): "
                        + String.join("; ", failures));
    }
}
