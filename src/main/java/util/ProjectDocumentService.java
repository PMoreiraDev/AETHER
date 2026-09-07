package util;

import domain.entities.ProjectDocument;
import persistence.AetherPaths;
import persistence.VaultManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/** Local-only document management for project attachments. No document content is stored in SQLite. */
public final class ProjectDocumentService {
    private static final String DOCUMENTS_DIR = ".project-documents";
    private static final List<String> ALLOWED = List.of("pdf", "doc", "docx", "txt", "md", "markdown");

    private ProjectDocumentService() { }

    /** Lists documents for every project without reading their full contents. */
    public static List<ProjectDocument> listAll() {
        Path root = documentsRoot();
        if (!Files.isDirectory(root)) return List.of();
        List<ProjectDocument> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> out.addAll(list(dir.getFileName().toString())));
        } catch (IOException ignored) { }
        return out;
    }

    public static List<ProjectDocument> list(String projectId) {
        if (projectId == null || projectId.isBlank()) return List.of();
        Path dir = projectDirectory(projectId);
        if (!Files.isDirectory(dir)) return List.of();
        List<ProjectDocument> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                try { out.add(metadata(projectId, p, p.getFileName().toString())); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
        return out;
    }

    public static ProjectDocument add(String projectId, Path source) throws IOException {
        if (projectId == null || projectId.isBlank()) throw new IOException("Invalid project id.");
        boolean projectExists = VaultManager.listProjects().stream().anyMatch(p -> projectId.equals(p.getId()));
        if (!projectExists) throw new IOException("Project does not exist.");
        if (source == null || !Files.isRegularFile(source)) throw new IOException("Document does not exist.");
        String ext = extension(source.getFileName().toString());
        if (!ALLOWED.contains(ext)) throw new IOException("Unsupported document type: " + ext);
        Path dir = projectDirectory(projectId);
        Files.createDirectories(dir);
        String safe = safeFilename(source.getFileName().toString());
        Path target = uniqueTarget(dir, safe);
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        VaultRefreshBus.publish(VaultRefreshBus.ChangeType.CREATED, "DOCUMENT");
        return metadata(projectId, target, source.getFileName().toString());
    }

    public static boolean remove(ProjectDocument document) throws IOException {
        if (document == null || document.getPath() == null) return false;
        Path root = documentsRoot().toAbsolutePath().normalize();
        Path target = document.getPath().toAbsolutePath().normalize();
        if (!target.startsWith(root)) throw new IOException("Invalid document path.");
        boolean deleted = Files.deleteIfExists(target);
        if (deleted) VaultRefreshBus.publish(VaultRefreshBus.ChangeType.DELETED, "DOCUMENT");
        return deleted;
    }

    public static boolean isSupported(Path path) {
        return path != null && ALLOWED.contains(extension(path.getFileName().toString()));
    }

    private static ProjectDocument metadata(String projectId, Path p, String originalFilename) throws IOException {
        String filename = p.getFileName().toString();
        String ext = extension(filename);
        Instant updated = Files.getLastModifiedTime(p).toInstant();
        Instant created = updated;
        try {
            Object value = Files.getAttribute(p, "creationTime");
            if (value instanceof java.nio.file.attribute.FileTime ft) created = ft.toInstant();
        } catch (Exception ignored) { }
        String id = UUID.nameUUIDFromBytes((projectId + "|" + filename).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new ProjectDocument(id, projectId, filename, originalFilename == null ? filename : originalFilename, p, ext,
                typeFor(ext), Files.size(p), created, updated);
    }

    private static Path documentsRoot() {
        return VaultManager.getVaultPath().resolve(DOCUMENTS_DIR);
    }

    private static Path projectDirectory(String projectId) {
        String safeId = projectId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return documentsRoot().resolve(safeId).normalize();
    }

    private static Path uniqueTarget(Path dir, String filename) {
        Path target = dir.resolve(filename);
        if (!Files.exists(target)) return target;
        String ext = extension(filename);
        String base = filename.substring(0, Math.max(0, filename.length() - (ext.isBlank() ? 0 : ext.length() + 1)));
        int n = 2;
        do { target = dir.resolve(base + "-" + n + (ext.isBlank() ? "" : "." + ext)); n++; }
        while (Files.exists(target));
        return target;
    }

    private static String safeFilename(String filename) {
        String clean = filename == null ? "document" : filename.trim();
        clean = clean.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (clean.isBlank() || clean.equals(".") || clean.equals("..")) clean = "document";
        return clean;
    }

    private static String extension(String filename) {
        int i = filename == null ? -1 : filename.lastIndexOf('.');
        return i >= 0 && i < filename.length() - 1 ? filename.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String typeFor(String ext) {
        return switch (ext) {
            case "pdf" -> "PDF";
            case "doc" -> "DOC";
            case "docx" -> "DOCX";
            case "md", "markdown" -> "Markdown";
            case "txt" -> "TXT";
            default -> ext.toUpperCase(Locale.ROOT);
        };
    }
}
