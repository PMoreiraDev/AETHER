package util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/**
 * Utilitário partilhado para mostrar a foto de perfil do utilizador em
 * qualquer {@link ImageView} circular da aplicação (avatar da Profile View,
 * pré-visualização no diálogo de edição, cartão de perfil na sidebar, ...).
 * <p>
 * Em vez de esticar a imagem original para caber no círculo (o que distorcia
 * fotos que não fossem quadradas), recorta primeiro o quadrado central da
 * imagem e só depois a escala — assim a foto fica sempre <b>centrada</b>
 * dentro do círculo, sem deformar.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class AvatarImages {

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(AvatarImages.class.getName());

    /**
     * Construtor privado: esta é uma classe utilitária e não deve ser instanciada.
     */
    private AvatarImages() {
        // Classe utilitária.
    }

    /**
     * Carrega a foto de perfil indicada no {@link ImageView} dado, recortada
     * ao quadrado central e escalada ao diâmetro pedido.
     *
     * @param imageView o {@link ImageView} de destino; quem chama deve
     *                  aplicar-lhe o clip circular, se pretendido
     * @param rawPath   o caminho da imagem, ou {@code null}/vazio para limpar
     * @param diameter  o diâmetro (em píxeis) do círculo a preencher
     * @return {@code true} se uma imagem válida foi carregada e o
     *         {@link ImageView} ficou visível; {@code false} caso contrário
     *         (o {@link ImageView} fica sem imagem e invisível, para que
     *         quem chama possa mostrar as iniciais como reserva)
     */
    public static boolean applyCenteredCircularImage(ImageView imageView, String rawPath, double diameter) {
        if (rawPath == null || rawPath.isBlank()) {
            imageView.setImage(null);
            imageView.setVisible(false);
            return false;
        }
        try {
            Path photo = Path.of(rawPath);
            if (!Files.isRegularFile(photo)) {
                imageView.setImage(null);
                imageView.setVisible(false);
                return false;
            }
            Image image = new Image(photo.toUri().toString());
            if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
                imageView.setImage(null);
                imageView.setVisible(false);
                return false;
            }

            // Recorta o quadrado central da imagem original antes de a
            // escalar, para que fique sempre centrada e sem distorção
            // dentro do círculo, seja qual for a proporção do ficheiro.
            double side = Math.min(image.getWidth(), image.getHeight());
            double viewportX = (image.getWidth() - side) / 2.0;
            double viewportY = (image.getHeight() - side) / 2.0;

            imageView.setImage(image);
            imageView.setPreserveRatio(false);
            imageView.setViewport(new Rectangle2D(viewportX, viewportY, side, side));
            imageView.setFitWidth(diameter);
            imageView.setFitHeight(diameter);
            imageView.setVisible(true);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warning("Could not load profile photo: " + e.getMessage());
            imageView.setImage(null);
            imageView.setVisible(false);
            return false;
        }
    }
}
