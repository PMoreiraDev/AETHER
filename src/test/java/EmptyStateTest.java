import org.junit.jupiter.api.Test;
import util.EmptyState;
import util.I18n;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmptyStateTest — verifica que o componente reutilizável de estados vazios
 * produz um VBox estilizado com ícone, título, dica e CTA opcional, e que
 * respeita o idioma ativo (não tem strings hardcoded em inglês).
 */
class EmptyStateTest {

    @Test
    void buildsWithTitleHintAndCta() {
        I18n.setLocale(new java.util.Locale("en"));
        Node node = EmptyState.of("empty.notes.title", "empty.notes.hint")
                .cta("empty.notes.cta", () -> {})
                .build();
        assertInstanceOf(VBox.class, node);
        VBox box = (VBox) node;
        assertTrue(box.getStyleClass().contains("aether-empty"));
        // ícone + título + dica + CTA = 4 filhos
        assertEquals(4, box.getChildren().size());
        assertInstanceOf(Button.class, box.getChildren().get(3));
    }

    @Test
    void buildsWithoutCta() {
        I18n.setLocale(new java.util.Locale("pt", "PT"));
        Node node = EmptyState.simple("empty.people.title", "empty.people.hint");
        VBox box = (VBox) node;
        // ícone + título + dica = 3 filhos (sem CTA)
        assertEquals(3, box.getChildren().size());
    }

    @Test
    void respectsLanguage() {
        I18n.setLocale(new java.util.Locale("pt", "PT"));
        Node node = EmptyState.simple("empty.notes.title", "empty.notes.hint");
        // Em PT o título contém "notas" (lowercase)
        VBox box = (VBox) node;
        javafx.scene.control.Label title = (javafx.scene.control.Label) box.getChildren().get(1);
        assertTrue(title.getText().toLowerCase().contains("notas"));

        I18n.setLocale(new java.util.Locale("en"));
        Node enNode = EmptyState.simple("empty.notes.title", "empty.notes.hint");
        javafx.scene.control.Label enTitle = (javafx.scene.control.Label) ((VBox) enNode).getChildren().get(1);
        assertEquals("No notes yet", enTitle.getText());
    }
}
