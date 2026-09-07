import javafx.application.Platform;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * FxToolkitSessionListener — gere o ciclo de vida do toolkit JavaFX ao longo
 * de toda a sessão de testes JUnit (uma por JVM de testes).
 * <p>
 * O toolkit é iniciado quando a sessão abre e parado quando a sessão fecha
 * (depois de todos os test classes). Isto resolve dois problemas reais:
 * <ul>
 *   <li>Sem parar o toolkit: o Application Thread não-daemon mantém a JVM viva
 *       depois dos testes, fazendo o surefire expirar.</li>
 *   <li>Parar o toolkit num @AfterAll de uma classe: classes posteriores que
 *       ainda precisam do toolkit (Platform.runLater) ficavam com stage null.</li>
 * </ul>
 * Registado via ServiceLoader em
 * META-INF/services/org.junit.platform.launcher.LauncherSessionListener.
 * </p>
 */
public class FxToolkitSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already initialized — fine.
        }
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        Platform.exit();
    }
}
