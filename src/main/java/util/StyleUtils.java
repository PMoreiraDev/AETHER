package util;

import java.net.URL;
import java.util.logging.Logger;
import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * Utilitários para aplicar a folha de estilos global do AETHER de forma segura.
 * <p>
 * Centraliza o caminho do {@code Style.css} num único local, evitando caminhos
 * repetidos e espalhados pelo código. Todos os métodos são tolerantes à ausência
 * do ficheiro: registam um aviso em vez de lançar {@code NullPointerException}.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class StyleUtils {

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(StyleUtils.class.getName());

    /** Caminho da folha de estilos global no classpath. */
    public static final String STYLESHEET_PATH = "/Style.css";

    /**
     * Construtor privado: esta é uma classe utilitária e não deve ser instanciada.
     */
    private StyleUtils() {
        // Classe utilitária.
    }

    /**
     * Devolve o URL externo da folha de estilos global.
     *
     * @return o URL da folha de estilos, ou {@code null} se não estiver no classpath
     */
    public static String getStylesheetUrl() {
        URL resource = StyleUtils.class.getResource(STYLESHEET_PATH);
        if (resource == null) {
            LOGGER.warning(() -> "Folha de estilos não encontrada em '" + STYLESHEET_PATH + "'.");
            return null;
        }
        return resource.toExternalForm();
    }

    /**
     * Aplica a folha de estilos global a uma cena, se ainda não estiver aplicada.
     *
     * @param scene a cena a estilizar; ignorado se for {@code null}
     */
    public static void applyTo(Scene scene) {
        if (scene == null) {
            return;
        }
        String url = getStylesheetUrl();
        if (url != null && !scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }

    /**
     * Aplica a folha de estilos global diretamente a um nó raiz, se ainda não
     * estiver aplicada. Útil para diálogos, que têm a sua própria cena.
     *
     * @param parent o nó raiz a estilizar; ignorado se for {@code null}
     */
    public static void applyTo(Parent parent) {
        if (parent == null) {
            return;
        }
        String url = getStylesheetUrl();
        if (url != null && !parent.getStylesheets().contains(url)) {
            parent.getStylesheets().add(url);
        }
    }
}
