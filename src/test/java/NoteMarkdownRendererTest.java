import javafx.application.Platform;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import util.NoteMarkdownRenderer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NoteMarkdownRendererTest — verifica que o renderer de markdown produz um
 * TextFlow não vazio para vários tipos de conteúdo e lida graciosamente com
 * texto vazio. Não valida a estrutura exata dos nós (fragil), apenas que o
 * subset suportado produz output e que nada lança exceções.
 */
class NoteMarkdownRendererTest {

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit já inicializado por outro teste.
        }
    }

    private int render(String markdown) {
        VBox flow = new VBox();
        NoteMarkdownRenderer.renderInto(flow, markdown);
        return flow.getChildren().size();
    }

    @Test
    void emptyMarkdown_showsPlaceholder() {
        VBox flow = new VBox();
        NoteMarkdownRenderer.renderInto(flow, "");
        assertEquals(1, flow.getChildren().size(), "empty input should render a placeholder");
    }

    @Test
    void nullMarkdown_showsPlaceholder() {
        VBox flow = new VBox();
        NoteMarkdownRenderer.renderInto(flow, null);
        assertFalse(flow.getChildren().isEmpty());
    }

    @Test
    void headingsRender() {
        assertTrue(render("# Big\n## Medium\n### Small") >= 3);
    }

    @Test
    void boldItalicAndCodeRender() {
        assertTrue(render("This is **bold** and *italic* and `code`.") >= 1);
    }

    @Test
    void listsAndChecklistRender() {
        String md = "- bullet one\n- bullet two\n1. numbered\n- [ ] todo\n- [x] done";
        assertTrue(render(md) >= 1);
    }

    @Test
    void quoteAndDividerAndLinkRender() {
        String md = "> a wise quote\n\n---\n\n[link](https://example.com)";
        assertTrue(render(md) >= 1);
    }

    @Test
    void codeBlockRenders() {
        String md = "Intro\n\n```\ncode line 1\ncode line 2\n```\n\nOutro";
        assertTrue(render(md) >= 1);
    }
}
