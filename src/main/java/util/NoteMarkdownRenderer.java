package util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * NoteMarkdownRenderer — converte um subconjunto seguro de Markdown num
 * {@link TextFlow} para a pré-visualização do editor de notas do AETHER.
 *
 * <p>Não é um parser de Markdown completo. Suporta o suficiente para notas
 * pessoais: cabeçalhos (H1–H3), negrito, itálico, listas, listas numeradas,
 * checklists, citações, código inline e blocos de código, links e divisores.
 * É intencionalmente defensivo: texto desconhecido é apresentado como parágrafo.</p>
 *
 * @author AETHER
 */
public final class NoteMarkdownRenderer {

    private NoteMarkdownRenderer() {
        // Classe utilitária.
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^[-*]\\s+(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\d+\\.\\s+(.+)$");
    private static final Pattern CHECKLIST = Pattern.compile("^[-*]\\s+\\[([ xX])]\\s+(.+)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern DIVIDER = Pattern.compile("^---+$");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");

    /** Blocos de código delimitados por ```. */
    private static final String CODE_FENCE = "```";

    /**
     * Renderiza o markdown num contentor (VBox) empilhando os blocos
     * verticalmente. Cada bloco (parágrafo, cabeçalho, item de lista, etc.) é
     * um {@link TextFlow} próprio, para que o conteúdo não colida numa única
     * linha. O contentor deve ser um {@link VBox} (ou outro {@link Pane}).
     */
    public static void renderInto(Pane flow, String markdown) {
        flow.getChildren().clear();
        if (markdown == null || markdown.isBlank()) {
            Text empty = styled("Nothing to preview yet.", "md-muted");
            TextFlow emptyFlow = new TextFlow(empty);
            flow.getChildren().add(emptyFlow);
            return;
        }

        String[] lines = markdown.split("\n", -1);
        List<Node> pending = new ArrayList<>();
        List<String> codeBlock = null;
        List<String> paragraph = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.replace("\r", "");

            if (codeBlock != null) {
                if (line.trim().startsWith(CODE_FENCE)) {
                    pending.add(codeNode(String.join("\n", codeBlock)));
                    codeBlock = null;
                } else {
                    codeBlock.add(line);
                }
                continue;
            }

            if (line.trim().startsWith(CODE_FENCE)) {
                flushParagraph(pending, paragraph);
                codeBlock = new ArrayList<>();
                continue;
            }

            if (DIVIDER.matcher(line.trim()).matches()) {
                flushParagraph(pending, paragraph);
                pending.add(dividerNode());
                continue;
            }

            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                flushParagraph(pending, paragraph);
                int level = h.group(1).length();
                pending.add(inlineNodes(h.group(2), "md-h" + level, true));
                continue;
            }

            Matcher q = QUOTE.matcher(line);
            if (q.matches()) {
                flushParagraph(pending, paragraph);
                pending.add(quoteNode(q.group(1)));
                continue;
            }

            Matcher cl = CHECKLIST.matcher(line);
            if (cl.matches()) {
                flushParagraph(pending, paragraph);
                boolean done = cl.group(1).equalsIgnoreCase("x");
                pending.add(checklistNode(cl.group(2), done));
                continue;
            }

            Matcher b = BULLET.matcher(line);
            if (b.matches()) {
                flushParagraph(pending, paragraph);
                pending.add(bulletNode(b.group(1)));
                continue;
            }

            Matcher n = NUMBERED.matcher(line);
            if (n.matches()) {
                flushParagraph(pending, paragraph);
                pending.add(numberedNode(n.group(1)));
                continue;
            }

            // Linha em branco termina parágrafo.
            if (line.isBlank()) {
                flushParagraph(pending, paragraph);
                continue;
            }

            paragraph.add(line);
        }

        flushParagraph(pending, paragraph);
        if (codeBlock != null) {
            pending.add(codeNode(String.join("\n", codeBlock)));
        }

        flow.getChildren().addAll(pending);
    }

    private static void flushParagraph(List<Node> out, List<String> para) {
        if (para.isEmpty()) {
            return;
        }
        out.add(inlineNodes(String.join("\n", para), "md-body", true));
        para.clear();
    }

    /** Texto com formatação inline (negrito/itálico/código/links). */
    private static Node inlineNodes(String text, String baseStyle, boolean wrap) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add(baseStyle);
        flow.setLineSpacing(1.4);
        // Tokeniza por links, depois por código inline, depois bold/italic.
        for (Text t : parseInline(text, baseStyle)) {
            flow.getChildren().add(t);
        }
        return flow;
    }

    private static List<Text> parseInline(String text, String baseStyle) {
        List<Text> out = new ArrayList<>();
        // Primeiro extrai links [text](url) — texto do link fica ciano.
        Matcher linkM = LINK.matcher(text);
        int last = 0;
        while (linkM.find()) {
            if (linkM.start() > last) {
                out.addAll(parseBoldItalic(text.substring(last, linkM.start()), baseStyle));
            }
            Text link = styled(linkM.group(1), "md-link");
            out.add(link);
            last = linkM.end();
        }
        if (last < text.length()) {
            out.addAll(parseBoldItalic(text.substring(last), baseStyle));
        }
        return out;
    }

    private static List<Text> parseBoldItalic(String text, String baseStyle) {
        List<Text> out = new ArrayList<>();
        // Código inline primeiro.
        Matcher code = INLINE_CODE.matcher(text);
        int last = 0;
        List<Text> afterCode = new ArrayList<>();
        while (code.find()) {
            if (code.start() > last) {
                afterCode.addAll(parseBoldItalicSimple(text.substring(last, code.start()), baseStyle));
            }
            afterCode.add(styled(code.group(1), "md-code"));
            last = code.end();
        }
        if (last < text.length()) {
            afterCode.addAll(parseBoldItalicSimple(text.substring(last), baseStyle));
        }
        out.addAll(afterCode);
        return out;
    }

    private static List<Text> parseBoldItalicSimple(String text, String baseStyle) {
        List<Text> out = new ArrayList<>();
        Matcher bold = BOLD.matcher(text);
        int last = 0;
        while (bold.find()) {
            if (bold.start() > last) {
                out.addAll(parseItalic(text.substring(last, bold.start()), baseStyle));
            }
            Text t = styled(bold.group(1), baseStyle);
            t.setStyle("-fx-font-weight: bold;");
            out.add(t);
            last = bold.end();
        }
        if (last < text.length()) {
            out.addAll(parseItalic(text.substring(last), baseStyle));
        }
        if (out.isEmpty()) {
            out.add(styled(text, baseStyle));
        }
        return out;
    }

    private static List<Text> parseItalic(String text, String baseStyle) {
        List<Text> out = new ArrayList<>();
        Matcher it = ITALIC.matcher(text);
        int last = 0;
        while (it.find()) {
            if (it.start() > last) {
                out.add(styled(text.substring(last, it.start()), baseStyle));
            }
            Text t = styled(it.group(1), baseStyle);
            t.setStyle("-fx-font-style: italic;");
            out.add(t);
            last = it.end();
        }
        if (last < text.length()) {
            out.add(styled(text.substring(last), baseStyle));
        }
        if (out.isEmpty()) {
            out.add(styled(text, baseStyle));
        }
        return out;
    }

    private static Node bulletNode(String content) {
        Text bullet = styled("•  ", "md-muted");
        TextFlow flow = new TextFlow(bullet);
        flow.getStyleClass().add("md-body");
        for (Text t : parseInline(content, "md-body")) {
            flow.getChildren().add(t);
        }
        return flow;
    }

    private static Node numberedNode(String content) {
        Text bullet = styled("›  ", "md-muted");
        TextFlow flow = new TextFlow(bullet);
        flow.getStyleClass().add("md-body");
        for (Text t : parseInline(content, "md-body")) {
            flow.getChildren().add(t);
        }
        return flow;
    }

    private static Node checklistNode(String content, boolean done) {
        Text box = styled(done ? "☑  " : "☐  ", done ? "md-quote" : "md-muted");
        TextFlow flow = new TextFlow(box);
        flow.getStyleClass().add("md-body");
        for (Text t : parseInline(content, done ? "md-muted" : "md-body")) {
            flow.getChildren().add(t);
        }
        return flow;
    }

    private static Node quoteNode(String content) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().addAll("md-quote", "md-body");
        for (Text t : parseInline(content, "md-body")) {
            flow.getChildren().add(t);
        }
        return flow;
    }

    private static Node codeNode(String content) {
        Text t = styled(content, "md-code");
        TextFlow flow = new TextFlow(t);
        return flow;
    }

    private static Node dividerNode() {
        Text t = styled("──────────────────", "md-muted");
        TextFlow flow = new TextFlow(t);
        return flow;
    }

    private static Text styled(String text, String styleClass) {
        Text t = new Text(text == null ? "" : text);
        if (styleClass != null && !styleClass.isBlank()) {
            t.getStyleClass().add(styleClass);
        }
        return t;
    }
}
