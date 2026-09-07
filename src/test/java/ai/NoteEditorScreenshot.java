package ai;

import controller.NoteEditorController;
import domain.entities.Note;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import util.I18n;
import util.NoteEditorContext;

/**
 * NoteEditorScreenshot — harness de verificação visual do editor de notas.
 *
 * <p>Carrega o FXML real do editor com o ResourceBundle i18n (o mesmo caminho de
 * produção), injeta uma nota de exemplo com markdown rico, ativa o modo
 * Preview (para mostrar o {@link util.NoteMarkdownRenderer}) e tira snapshots em
 * Dark e Light. Isto confirma que o editor carrega, estiliza e renderiza sem
 * erros — verificação visual real, não apenas compilação.</p>
 */
public class NoteEditorScreenshot {

    private static final String SAMPLE_TITLE = "Reunião de Produto — Q4 Roadmap";
    private static final String SAMPLE_BODY =
            "# Reunião de Produto — Q4 Roadmap\n\n"
            + "Hoje discutimos as **prioridades** do próximo trimestre e o *posicionamento* da IA.\n\n"
            + "## Decisões\n\n"
            + "- Definir o **escopo** do MVP\n"
            + "- Atribuir responsáveis\n"
            + "- [ ] Enviar ata aos participantes\n"
            + "- [x] Agendar próxima reunião\n\n"
            + "## Notas\n\n"
            + "> A IA propõe, o utilizador aprova — nunca escreve no vault sozinha.\n\n"
            + "Próximo passo: ver a [proposta](https://example.com) na vista de Notas.\n\n"
            + "---\n\n"
            + "Código de referência:\n\n"
            + "```\nNoteAnalysisService.analyze(text)\n```\n";

    public static void main(String[] args) throws Exception {
        System.setProperty("aether.data.dir",
                java.nio.file.Files.createTempDirectory("aether-shot").toString());
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                render();
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
        });
        Thread.sleep(4000);
    }

    private static void render() throws Exception {
        URL fxml = NoteEditorScreenshot.class.getResource("/FXML/note_editor.fxml");
        FXMLLoader loader = new FXMLLoader(fxml, I18n.getBundle());

        // Prepara uma nota de exemplo e injeta no editor via contexto ANTES do load
        // para que initialize() a consuma corretamente.
        Note note = new Note();
        note.setTitle(SAMPLE_TITLE);
        note.setContent(SAMPLE_BODY);
        note.setCreatedAt(java.time.LocalDateTime.now());
        note.setUpdatedAt(java.time.LocalDateTime.now());
        NoteEditorContext.setPending(note);

        Parent root = loader.load();
        NoteEditorController controller = loader.getController();
        controller.showPreview(); // ativa o modo preview para mostrar o renderer

        Scene scene = new Scene(root, 720, 760);
        URL css = NoteEditorScreenshot.class.getResource("/Style.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        scene.getRoot().getStyleClass().add("dark");
        scene.getRoot().setStyle("-fx-background-color: #0a0f1a;");

        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

        snapshot(root, "/home/user/workspace/note_editor_dark.png");

        // Tema light
        scene.getRoot().getStyleClass().remove("dark");
        scene.getRoot().getStyleClass().add("light");
        scene.getRoot().setStyle("-fx-background-color: #f6f7fb;");
        root.applyCss();
        root.layout();
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        snapshot(root, "/home/user/workspace/note_editor_light.png");

        stage.close();
        System.out.println("SCREENSHOT_SAVED: note_editor_dark.png + note_editor_light.png");
        System.exit(0);
    }

    private static void snapshot(Parent root, String path) throws Exception {
        WritableImage img = root.snapshot(null, null);
        javax.imageio.ImageIO.write(toBufferedImage(img), "png", new File(path));
        System.out.println("SCREENSHOT_SAVED:" + path);
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
