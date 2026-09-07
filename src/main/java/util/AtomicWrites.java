package util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * AtomicWrites — utilitário de escrita atómica de ficheiros para segurança de
 * dados do vault.
 * <p>
 * Escreve primeiro para um ficheiro temporário no mesmo diretório, faz flush +
 * sync, e depois move atomicamente para o destino final. Se o processo for
 * interrompido a meio, o ficheiro original permanece intacto — evita corrupção
 * de ficheiros Markdown durante a escrita.
 * </p>
 * <p>
 * Quando o sistema de ficheiros não suporta moves atómicos (raro em POSIX, mas
 * possível em algumas montagens de rede), recorre graciosamente a uma cópia com
 * substituição, preservando pelo menos a garantia de que o destino não fica
 * truncado a meio da escrita.
 * </p>
 *
 * @author AETHER
 */
public final class AtomicWrites {

    /** Sufixo do ficheiro temporário usado durante a escrita. */
    private static final String TMP_SUFFIX = ".aether-tmp";

    private AtomicWrites() {
        // Classe de utilitário estático.
    }

    /**
     * Escreve texto num ficheiro de forma atómica.
     *
     * @param target  o caminho final do ficheiro
     * @param content o conteúdo a escrever
     * @throws IOException se a escrita falhar
     */
    public static void write(Path target, CharSequence content) throws IOException {
        if (target == null) {
            throw new IOException("target path is null");
        }
        if (content == null) {
            throw new IOException("content is null");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = resolveTempPath(target);

        // 1. Escrever o conteúdo completo no ficheiro temporário.
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(java.nio.ByteBuffer.wrap(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ch.force(true);
        }

        // 2. Mover atomicamente para o destino final.
        try {
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            // Fallback: substituição por cópia. Não é atómico mas evita
            // truncamento a meio da escrita porque o temporário já está completo.
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Escreve bytes num ficheiro de forma atómica (para backups binários, ex. SQLite).
     *
     * @param target  o caminho final do ficheiro
     * @param bytes   os bytes a escrever
     * @throws IOException se a escrita falhar
     */
    public static void writeBytes(Path target, byte[] bytes) throws IOException {
        if (target == null) {
            throw new IOException("target path is null");
        }
        if (bytes == null) {
            throw new IOException("bytes is null");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = resolveTempPath(target);
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        try {
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Resolve um caminho temporário único no mesmo diretório do destino, para
     * garantir que o move atómico funcione (entre sistemas de ficheiros
     * diferentes não é suportado).
     *
     * @param target o caminho final
     * @return o caminho temporário
     */
    private static Path resolveTempPath(Path target) {
        Path parent = target.getParent();
        String name = target.getFileName().toString();
        return (parent == null ? Path.of(name) : parent)
                .resolve(name + TMP_SUFFIX);
    }
}
