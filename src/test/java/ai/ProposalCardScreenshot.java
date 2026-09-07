package ai;

import domain.entities.ContextEntityType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import util.ProposalCardBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Harness visual: renderiza uma AI Proposal Card de exemplo com o CSS real,
 * em tema dark e light, e grava PNGs para revisão de design.
 * Usa Platform.startup() (não extends Application) para funcionar em classpath.
 */
public class ProposalCardScreenshot {

    public static void main(String[] args) {
        try {
            Path tmp = Files.createTempDirectory("aether-shot");
            System.setProperty("aether.data.dir", tmp.toString());
        } catch (Exception e) {
            // ignora
        }
        Platform.startup(() -> {
            try {
                renderAndSave();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    private static void renderAndSave() throws Exception {
        AiActionProposal person = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.PERSON)
                .field("name", "Manel")
                .field("about", "Colega de trabalho")
                .field("occupation", "Engenheiro de software")
                .relationships(List.of("Projeto AETHER", "Preparar dashboard"))
                .reason("Pessoa mencionada na nota com papel claro.")
                .confidence(0.96)
                .trustLevel(TrustLevel.INFERRED)
                .semanticClassification("FACT")
                .sourceContext("Nota: Reunião de hoje — \u201cO Manel é meu colega de trabalho\u201d")
                .build();
        ResolvedAction ra = new ResolvedAction(person, null, ProposalStatus.SUGGESTED);

        VBox card = ProposalCardBuilder.build(ra, new ProposalCardBuilder.Callbacks() {
            @Override public void onAccept(Runnable lock) {}
            @Override public void onReject(Runnable lock) {}
            @Override public void onEdit(Runnable lock) {}
        });

        AiActionProposal task = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .field("title", "Terminar a integração do Ollama")
                .field("deadline", "2026-09-12 00:00")
                .field("status", "TODO")
                .relationships(List.of("AETHER"))
                .reason("Tarefa com deadline explícito na nota.")
                .confidence(0.95)
                .trustLevel(TrustLevel.INFERRED)
                .semanticClassification("FACT")
                .sourceContext("Nota: Reunião de hoje — \u201cPrecisamos de terminar a integração do Ollama até sexta-feira\u201d")
                .build();
        ResolvedAction ra2 = new ResolvedAction(task, null, ProposalStatus.SUGGESTED);
        VBox card2 = ProposalCardBuilder.build(ra2, new ProposalCardBuilder.Callbacks() {
            @Override public void onAccept(Runnable lock) {}
            @Override public void onReject(Runnable lock) {}
            @Override public void onEdit(Runnable lock) {}
        });

        VBox section = new VBox(8);
        section.getStyleClass().add("ai-proposals-section");
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("PROPOSTAS DA IA");
        title.getStyleClass().add("ai-proposals-section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button acceptAll = new Button("Aceitar tudo");
        acceptAll.getStyleClass().addAll("proposal-btn", "proposal-accept-btn");
        Button rejectAll = new Button("Rejeitar tudo");
        rejectAll.getStyleClass().addAll("proposal-btn", "proposal-reject-btn");
        header.getChildren().addAll(title, spacer, acceptAll, rejectAll);

        // Linha de progresso da análise (estado "a analisar")
        ProgressBar analyzeBar = new ProgressBar(-1);
        analyzeBar.setPrefWidth(150);
        analyzeBar.setMaxWidth(150);
        analyzeBar.setPrefHeight(6);
        Label analyzeStatus = new Label("A analisar a nota com IA\u2026");
        analyzeStatus.getStyleClass().add("ai-analyze-status");
        Button cancelBtn = new Button("Cancelar");
        cancelBtn.getStyleClass().addAll("proposal-btn", "proposal-btn-tertiary");
        Region gap2 = new Region();
        HBox.setHgrow(gap2, Priority.ALWAYS);
        HBox analyzeRow = new HBox(10, analyzeBar, analyzeStatus, gap2, cancelBtn);
        analyzeRow.setAlignment(Pos.CENTER_LEFT);
        analyzeRow.getStyleClass().add("ai-analyze-progress");

        section.getChildren().addAll(header, analyzeRow, card, card2);

        VBox root = new VBox(section);
        root.setPadding(new Insets(24));

        // Tema dark
        root.setStyle("-fx-background-color: #0b0e14;");
        Scene scene = new Scene(root, 540, 640);
        java.net.URL css = ProposalCardScreenshot.class.getResource("/Style.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        scene.getRoot().getStyleClass().add("dark");

        // anexa a uma janela offscreen para forçar o layout
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        Thread.sleep(120);

        WritableImage img = root.snapshot(null, null);
        Path out = Path.of("/home/user/workspace/proposal_card_dark.png");
        javax.imageio.ImageIO.write(toBufferedImage(img), "png", new File(out.toString()));
        System.out.println("SCREENSHOT_SAVED:" + out);

        // Tema light
        scene.getRoot().getStyleClass().remove("dark");
        scene.getRoot().getStyleClass().add("light");
        root.setStyle("-fx-background-color: #f6f7fb;");
        root.applyCss();
        root.layout();
        Thread.sleep(120);
        WritableImage img2 = root.snapshot(null, null);
        Path out2 = Path.of("/home/user/workspace/proposal_card_light.png");
        javax.imageio.ImageIO.write(toBufferedImage(img2), "png", new File(out2.toString()));
        System.out.println("SCREENSHOT_SAVED:" + out2);
        stage.close();
        System.exit(0);
    }

    private static java.awt.image.BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return bi;
    }
}
