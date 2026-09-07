package util;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * EmptyState — componente reutilizável para estados vazios em todo o AETHER.
 *
 * <p>Substitui as mensagens de uma linha ("No people added yet.") por um estado
 * vazio útil: ícone + título + dica + CTA opcional. Mantém a identidade visual
 * (classes {@code .aether-empty-*} do design system) e é acessível (não depende
 * só de cor: o ícono e o título comunicam o estado).</p>
 *
 * <p>Uso:</p>
 * <pre>{@code
 * EmptyState.of("empty.notes.title", "empty.notes.hint")
 *          .cta("empty.notes.cta", () -> createNote())
 *          .build();
 * }</pre>
 *
 * @author AETHER
 */
public final class EmptyState {

    private EmptyState() {
        // Builder estático.
    }

    /** Cria um builder para um estado vazio com título e dica i18n. */
    public static Builder of(String titleKey, String hintKey) {
        return new Builder(titleKey, hintKey);
    }

    /** Cria um estado vazio simples (sem CTA). */
    public static Node simple(String titleKey, String hintKey) {
        return of(titleKey, hintKey).build();
    }

    public static final class Builder {
        private final String titleKey;
        private final String hintKey;
        private String icon = "✦";
        private String ctaKey;
        private Runnable ctaAction;

        Builder(String titleKey, String hintKey) {
            this.titleKey = titleKey;
            this.hintKey = hintKey;
        }

        /** Define o ícone (emoji ou carácter unicode). */
        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        /** Adiciona um botão de call-to-action. */
        public Builder cta(String ctaKey, Runnable action) {
            this.ctaKey = ctaKey;
            this.ctaAction = action;
            return this;
        }

        public Node build() {
            VBox box = new VBox(10);
            box.getStyleClass().add("aether-empty");
            box.setAlignment(Pos.CENTER);

            Label iconLabel = new Label(icon);
            iconLabel.getStyleClass().add("aether-empty-icon");
            box.getChildren().add(iconLabel);

            Label title = new Label(I18n.tr(titleKey));
            title.getStyleClass().add("aether-empty-title");
            box.getChildren().add(title);

            if (hintKey != null && !hintKey.isBlank()) {
                Label hint = new Label(I18n.tr(hintKey));
                hint.getStyleClass().add("aether-empty-hint");
                hint.setWrapText(true);
                hint.setMaxWidth(360);
                hint.setAlignment(Pos.CENTER);
                box.getChildren().add(hint);
            }

            if (ctaKey != null && !ctaKey.isBlank() && ctaAction != null) {
                Button cta = new Button(I18n.tr(ctaKey));
                cta.getStyleClass().addAll("aether-btn", "aether-btn-primary");
                cta.setOnAction(e -> ctaAction.run());
                box.getChildren().add(cta);
            }

            return box;
        }
    }
}
