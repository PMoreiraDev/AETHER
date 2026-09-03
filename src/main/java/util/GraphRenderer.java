package util;

import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Task;
import domain.entities.Project;
import persistence.VaultManager;
import session.UserSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

/**
 * GraphRenderer — desenha o Context Graph interativo no dashboard.
 * <p>
 * Lê os dados do vault (ficheiros + wikilinks) e desenha um grafo
 * interativo com nós (Circle + Label) e arestas (Line) num Pane.
 * O utilizador está no centro, com as entidades à volta.
 * </p>
 * <p>
 * Os nós são clicáveis e arrastáveis. O layout é radial simples:
 * o utilizador no centro, entidades distribuídas num círculo à volta.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class GraphRenderer {

    /** Raio do layout radial. */
    private static final double LAYOUT_RADIUS = 140;

    /** Raio do nó central (utilizador). */
    private static final double USER_NODE_RADIUS = 28;

    /** Raio dos nós periféricos. */
    private static final double ENTITY_NODE_RADIUS = 18;

    /** Cores do AETHER. */
    private static final Color COLOR_USER = Color.web("#22d3ee");
    private static final Color COLOR_PERSON = Color.web("#6366f1");
    private static final Color COLOR_EVENT = Color.web("#a78bfa");
    private static final Color COLOR_TASK = Color.web("#34d399");
    private static final Color COLOR_PROJECT = Color.web("#f59e0b");
    private static final Color COLOR_NOTE = Color.web("#818cf8");
    private static final Color COLOR_EDGE = Color.web("#475569");

    /** Mapa de nós para posições (para arrastar). */
    private static final Map<String, Point2D> nodePositions = new HashMap<>();

    /**
     * Construtor privado — classe utilitária.
     */
    private GraphRenderer() {
        // Classe utilitária.
    }

    /**
     * Renderiza o grafo num Pane.
     * <p>
     * Lê o vault, cria nós para cada entidade e desenha as arestas
     * (wikilinks) entre eles. O utilizador fica no centro.
     * </p>
     *
     * @param canvas o Pane onde desenhar o grafo
     */
    public static void render(Pane canvas) {
        canvas.getChildren().clear();
        nodePositions.clear();

        double centerX = canvas.getWidth() / 2;
        double centerY = canvas.getHeight() / 2;

        // Se o canvas ainda não tem dimensões, usar defaults
        if (centerX <= 0) centerX = 200;
        if (centerY <= 0) centerY = 200;

        // Nó central: utilizador
        String userName = getUserName();
        StackPane userNode = createNode(userName, COLOR_USER, USER_NODE_RADIUS, true);
        userNode.setLayoutX(centerX - USER_NODE_RADIUS);
        userNode.setLayoutY(centerY - USER_NODE_RADIUS);
        userNode.setCursor(Cursor.HAND);
        nodePositions.put(userName, new Point2D(centerX, centerY));

        // Recolher entidades do vault
        List<GraphEntity> entities = collectEntities();

        if (entities.isEmpty()) {
            // Estado vazio
            Label empty = new Label("Your context graph is empty.\nAdd people, events, tasks and notes to see connections here.");
            empty.getStyleClass().add("dash-graph-placeholder-text");
            empty.setLayoutX(centerX - 120);
            empty.setLayoutY(centerY - 20);
            canvas.getChildren().add(empty);
            canvas.getChildren().add(userNode);
            return;
        }

        // Posicionar entidades num círculo à volta do utilizador
        double angleStep = 2 * Math.PI / Math.max(entities.size(), 1);
        for (int i = 0; i < entities.size(); i++) {
            GraphEntity entity = entities.get(i);
            double angle = i * angleStep - Math.PI / 2; // Começar no topo
            double x = centerX + LAYOUT_RADIUS * Math.cos(angle);
            double y = centerY + LAYOUT_RADIUS * Math.sin(angle);

            Color color = getColorForType(entity.type);
            StackPane node = createNode(entity.label, color, ENTITY_NODE_RADIUS, false);
            node.setLayoutX(x - ENTITY_NODE_RADIUS);
            node.setLayoutY(y - ENTITY_NODE_RADIUS);
            node.setCursor(Cursor.HAND);

            // Tornar arrastável
            makeDraggable(node, entity.label, canvas);

            nodePositions.put(entity.label, new Point2D(x, y));

            // Desenhar aresta do utilizador para a entidade
            Line edge = new Line(centerX, centerY, x, y);
            edge.setStroke(COLOR_EDGE);
            edge.setStrokeWidth(1.5);
            edge.setOpacity(0.5);
            canvas.getChildren().add(edge);

            canvas.getChildren().add(node);
        }

        // Desenhar arestas entre entidades (wikilinks)
        Map<String, List<String>> graphData = VaultManager.buildGraphData();
        for (Map.Entry<String, List<String>> entry : graphData.entrySet()) {
            String sourceName = entry.getKey();
            Point2D sourcePos = nodePositions.get(sourceName);
            if (sourcePos == null) continue;

            for (String targetName : entry.getValue()) {
                Point2D targetPos = nodePositions.get(targetName);
                if (targetPos == null) continue;

                Line edge = new Line(sourcePos.getX(), sourcePos.getY(),
                        targetPos.getX(), targetPos.getY());
                edge.setStroke(COLOR_EDGE);
                edge.setStrokeWidth(1);
                edge.setOpacity(0.3);
                edge.getStrokeDashArray().addAll(3.0, 3.0);
                canvas.getChildren().add(0, edge); // Arestas atrás dos nós
            }
        }

        canvas.getChildren().add(userNode);
    }

    /**
     * Cria um nó visual (Circle + Label) dentro de um StackPane.
     */
    private static StackPane createNode(String label, Color color, double radius, boolean isUser) {
        Circle circle = new Circle(radius);
        circle.setFill(color);
        circle.setStroke(color.brighter());
        circle.setStrokeWidth(1.5);
        circle.getStyleClass().add(isUser ? "dash-graph-user-circle" : "dash-graph-entity-circle");

        Label text = new Label(extractInitials(label));
        text.getStyleClass().add(isUser ? "dash-graph-user-initials" : "dash-graph-entity-initials");
        text.setTextFill(Color.WHITE);

        StackPane pane = new StackPane(circle, text);

        // Label abaixo do nó
        VBox container = new VBox(4);
        container.setAlignment(javafx.geometry.Pos.CENTER);

        Label nameLabel = new Label(label);
        nameLabel.getStyleClass().add(isUser ? "dash-graph-user-name" : "dash-graph-entity-name");
        nameLabel.setTextFill(Color.web("#e2e8f0"));

        // Reusar o StackPane como nó visual
        return pane;
    }

    /**
     * Torna um nó arrastável dentro do Pane.
     */
    private static void makeDraggable(StackPane node, String nodeName, Pane canvas) {
        final double[] dragOffset = new double[2];

        node.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX() - node.getLayoutX();
            dragOffset[1] = e.getSceneY() - node.getLayoutY();
        });

        node.setOnMouseDragged(e -> {
            double newX = e.getSceneX() - dragOffset[0];
            double newY = e.getSceneY() - dragOffset[1];
            node.setLayoutX(newX);
            node.setLayoutY(newY);
            nodePositions.put(nodeName, new Point2D(newX + ENTITY_NODE_RADIUS, newY + ENTITY_NODE_RADIUS));
            // Atualizar arestas
            render(canvas);
        });
    }

    /**
     * Recolhe todas as entidades do vault para o grafo.
     */
    private static List<GraphEntity> collectEntities() {
        List<GraphEntity> entities = new ArrayList<>();

        if (!VaultManager.isVaultInitialized()) {
            return entities;
        }

        for (Person p : VaultManager.listPeople()) {
            entities.add(new GraphEntity("person", p.getName()));
        }
        for (Event e : VaultManager.listEvents()) {
            entities.add(new GraphEntity("event", e.getTitle()));
        }
        for (Task t : VaultManager.listTasks()) {
            entities.add(new GraphEntity("task", t.getTitle()));
        }
        for (Project p : VaultManager.listProjects()) {
            entities.add(new GraphEntity("project", p.getName()));
        }

        return entities;
    }

    /**
     * Devolve a cor para um tipo de entidade.
     */
    private static Color getColorForType(String type) {
        return switch (type) {
            case "person" -> COLOR_PERSON;
            case "event" -> COLOR_EVENT;
            case "task" -> COLOR_TASK;
            case "project" -> COLOR_PROJECT;
            default -> COLOR_NOTE;
        };
    }

    /**
     * Devolve o nome do utilizador.
     */
    private static String getUserName() {
        var profile = UserSession.getInstance().getUserProfile();
        String name = profile.getPreferredName();
        if (name == null || name.isBlank()) {
            name = profile.getFullName();
        }
        return (name == null || name.isBlank()) ? "You" : name;
    }

    /**
     * Extrai iniciais de um nome.
     */
    private static String extractInitials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    /**
     * Estrutura interna para representar uma entidade no grafo.
     */
    private static class GraphEntity {
        final String type;
        final String label;

        GraphEntity(String type, String label) {
            this.type = type;
            this.label = label;
        }
    }
}
