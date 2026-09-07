package util;

import ai.AiActionProposal;
import ai.AiActionType;
import ai.ProposalStore;
import ai.ResolvedAction;
import ai.TrustLevel;
import controller.AetherAIController;
import domain.entities.ContextEntityType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ProposalCardBuilder — construtor partilhado da AI Proposal Card rica.
 * <p>
 * Uma única fonte de verdade para o cartão de proposta de IA, usada tanto no
 * chat ({@link AetherAIController}) como nas views de entidades (People,
 * Projects, Tasks, Events, Notes). Garante consistência visual total entre os
 * dois contextos e respeita o design system do AETHER (superfícies glass,
 * cores subtils, micro-interações) definido em {@code Style.css}.
 * </p>
 * <p>
 * A card NUNCA executa nada por si: os botões Accept / Reject / Edit chamam
 * apenas os callbacks fornecidos pelo dono do cartão. A execução real só
 * acontece no {@code ActionExecutor}, após aprovação explícita — a IA propõe,
 * o utilizador decide, o sistema executa, o vault persiste.
 * </p>
 *
 * @author AETHER
 */
public final class ProposalCardBuilder {

    private ProposalCardBuilder() {
        // Construtor estático.
    }

    /** Callbacks que o dono do cartão fornece para os botões. */
    public interface Callbacks {
        /** Chamado quando o utilizador carrega em Accept. {@code lock} desativa os botões. */
        void onAccept(Runnable lock);
        /** Chamado quando o utilizador carrega em Reject. {@code lock} desativa os botões. */
        void onReject(Runnable lock);
        /** Chamado quando o utilizador carrega em Edit. */
        void onEdit(Runnable lock);
    }

    /**
     * Constrói a card rica para uma proposta resolvida.
     *
     * @param ra  a ação resolvida (proposta + estado de duplicado)
     * @param cb  callbacks para os botões
     * @return o nó raiz da card (pronto a inserir num contentor)
     */
    public static VBox build(ResolvedAction ra, Callbacks cb) {
        AiActionProposal p = ra.proposal;
        VBox card = new VBox(8);
        card.getStyleClass().add("ai-proposal-card");
        card.setMaxWidth(Region.USE_COMPUTED_SIZE);
        card.setPrefWidth(440);

        // --- Cabeçalho: eyebrow + trust badge + type badge ---
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label eyebrow = new Label(I18n.tr("ai.proposal.eyebrow"));
        eyebrow.getStyleClass().add("ai-proposal-eyebrow");
        header.getChildren().add(eyebrow);

        Label trust = new Label(classificationLabel(p.getSemanticClassification()));
        trust.getStyleClass().addAll("ai-proposal-trust", classificationClass(p.getSemanticClassification()));
        header.getChildren().add(trust);

        Label typeBadge = new Label(typeLabel(p.getActionType()));
        typeBadge.getStyleClass().add("ai-proposal-type-badge");
        header.getChildren().add(typeBadge);
        card.getChildren().add(header);

        // --- Título: verbo + entidade ---
        Label title = new Label(verbLabel(p.getActionType()) + " " + entityLabel(p.getEntityType()));
        title.getStyleClass().add("ai-proposal-title");
        card.getChildren().add(title);

        // --- Subtítulo: fonte ---
        if (p.getSourceContext() != null && !p.getSourceContext().isBlank()) {
            Label src = new Label(I18n.tr("ai.proposal.source") + ": " + p.getSourceContext());
            src.getStyleClass().add("ai-proposal-subtitle");
            src.setWrapText(true);
            card.getChildren().add(src);
        }

        // --- Evidência (quote) ---
        String evidence = extractEvidence(p.getSourceContext());
        if (!evidence.isBlank()) {
            Label quote = new Label("\u201c" + evidence + "\u201d");
            quote.getStyleClass().add("ai-proposal-quote");
            quote.setWrapText(true);
            card.getChildren().add(quote);
        }

        // --- Meta: campos da entidade ---
        if (!p.getFields().isEmpty()) {
            VBox meta = new VBox(3);
            p.getFields().forEach((k, v) -> {
                HBox row = new HBox(8);
                row.setAlignment(Pos.TOP_LEFT);
                Label lbl = new Label(k + ":");
                lbl.getStyleClass().add("ai-proposal-meta-label");
                Label val = new Label(v == null ? "" : v);
                val.getStyleClass().add("ai-proposal-meta-value");
                val.setWrapText(true);
                row.getChildren().addAll(lbl, val);
                meta.getChildren().add(row);
            });
            card.getChildren().add(meta);
        }

        // --- Confiança (barra) ---
        double conf = p.getConfidence();
        if (conf > 0) {
            HBox confRow = new HBox(8);
            confRow.setAlignment(Pos.CENTER_LEFT);
            Label confLbl = new Label(String.format(Locale.ROOT, "%.0f%%", conf * 100));
            confLbl.getStyleClass().add("ai-proposal-meta-label");
            ProgressBar bar = new ProgressBar(Math.max(0.0, Math.min(1.0, conf)));
            bar.getStyleClass().add("ai-proposal-confidence-bar");
            confRow.getChildren().addAll(confLbl, bar);
            card.getChildren().add(confRow);
        }

        // --- Relações ---
        List<String> rels = p.getRelationships();
        if (rels != null && !rels.isEmpty()) {
            Label relLbl = new Label(I18n.tr("ai.proposal.relations"));
            relLbl.getStyleClass().add("ai-proposal-relations-label");
            FlowPane relFlow = new FlowPane();
            relFlow.setHgap(6);
            relFlow.setVgap(4);
            for (String r : rels) {
                Label chip = new Label("\u2192 " + r);
                chip.getStyleClass().add("ai-proposal-relation");
                relFlow.getChildren().add(chip);
            }
            card.getChildren().addAll(relLbl, relFlow);
        }

        // --- Razão (fallback de explicação) ---
        if (p.getReason() != null && !p.getReason().isBlank() && evidence.isBlank()) {
            Label why = new Label(p.getReason());
            why.getStyleClass().add("ai-proposal-subtitle");
            why.setWrapText(true);
            card.getChildren().add(why);
        }

        // --- Duplicado detectado ---
        if (ra.hasDuplicate() && ra.duplicate != null && ra.duplicate.existing != null) {
            Label dup = new Label(I18n.tr("ai.proposal.duplicate",
                    ra.duplicate.existing.getClass().getSimpleName()));
            dup.getStyleClass().add("ai-proposal-subtitle");
            dup.setWrapText(true);
            card.getChildren().add(dup);
        }

        // --- Ações: Accept / Reject / Edit ---
        HBox actions = new HBox(8);
        actions.getStyleClass().add("ai-proposal-actions");
        actions.setAlignment(Pos.CENTER_LEFT);

        Button accept = new Button(I18n.tr("ai.proposal.accept"));
        accept.getStyleClass().addAll("proposal-btn", "proposal-accept-btn");
        Button reject = new Button(I18n.tr("ai.proposal.reject"));
        reject.getStyleClass().addAll("proposal-btn", "proposal-reject-btn");
        Button edit = new Button(I18n.tr("common.edit"));
        edit.getStyleClass().addAll("proposal-btn", "proposal-btn-tertiary");

        Runnable lock = () -> actions.getChildren().forEach(n -> n.setDisable(true));

        accept.setOnAction(ev -> {
            lock.run();
            applyState(card, "done");
            cb.onAccept(() -> applyState(card, "error"));
        });
        reject.setOnAction(ev -> {
            lock.run();
            applyState(card, "rejected");
            cb.onReject(() -> {});
        });
        edit.setOnAction(ev -> {
            lock.run();
            cb.onEdit(() -> {});
        });

        actions.getChildren().addAll(accept, reject, edit);
        card.getChildren().add(actions);

        return card;
    }

    // ------------------------------------------------------------------
    // Estado visual
    // ------------------------------------------------------------------

    private static void applyState(VBox card, String state) {
        card.getStyleClass().removeAll("accepted", "rejected", "error");
        card.getStyleClass().add(state);
    }

    // ------------------------------------------------------------------
    // Etiquetas (i18n) e classes CSS
    // ------------------------------------------------------------------

    private static String classificationLabel(String c) {
        if (c == null) return "";
        return switch (c.trim().toUpperCase(Locale.ROOT)) {
            case "FACT" -> I18n.tr("ai.proposal.classification.fact");
            case "INFERENCE" -> I18n.tr("ai.proposal.classification.inference");
            default -> I18n.tr("ai.proposal.classification.suggestion");
        };
    }

    private static String classificationClass(String c) {
        if (c == null) return "suggested";
        return switch (c.trim().toUpperCase(Locale.ROOT)) {
            case "FACT" -> "fact";
            case "INFERENCE" -> "inferred";
            default -> "suggested";
        };
    }

    private static String trustLabel(TrustLevel t) {
        return switch (t) {
            case CONFIRMED -> I18n.tr("ai.proposal.trust.confirmed");
            case INFERRED -> I18n.tr("ai.proposal.trust.inferred");
            case SUGGESTED -> I18n.tr("ai.proposal.trust.suggested");
        };
    }

    private static String trustClass(TrustLevel t) {
        return switch (t) {
            case CONFIRMED -> "confirmed";
            case INFERRED -> "inferred";
            case SUGGESTED -> "suggested";
        };
    }

    private static String typeLabel(AiActionType t) {
        return switch (t) {
            case CREATE_ENTITY -> I18n.tr("ai.proposal.verb.create");
            case UPDATE_ENTITY -> I18n.tr("ai.proposal.verb.update");
            case LINK_ENTITIES -> I18n.tr("ai.proposal.verb.link");
            case UNLINK_ENTITIES -> I18n.tr("ai.proposal.verb.unlink");
            case DELETE_ENTITY -> I18n.tr("ai.proposal.verb.delete");
        };
    }

    private static String verbLabel(AiActionType t) {
        return typeLabel(t);
    }

    private static String entityLabel(ContextEntityType e) {
        return switch (e) {
            case PERSON -> I18n.tr("entity.person");
            case PROJECT -> I18n.tr("entity.project");
            case EVENT -> I18n.tr("entity.event");
            case TASK -> I18n.tr("entity.task");
            case NOTE -> I18n.tr("entity.note");
            case PROFILE -> I18n.tr("entity.profile");
        };
    }

    /** Extrai o excerto de evidência do sourceContext (após o "—"). */
    private static String extractEvidence(String sourceContext) {
        if (sourceContext == null || sourceContext.isBlank()) return "";
        int dash = sourceContext.indexOf('\u2014');
        if (dash < 0) dash = sourceContext.indexOf(" — ");
        if (dash >= 0) {
            String ev = sourceContext.substring(dash).replaceFirst("^\s*[\u2014-]+\s*", "").trim();
            // remove aspas finais se presentes
            if (ev.startsWith("\u201c") && ev.endsWith("\u201d") && ev.length() > 2) {
                ev = ev.substring(1, ev.length() - 1);
            }
            return ev;
        }
        return "";
    }
}
