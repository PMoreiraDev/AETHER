package domain.entities;

import java.nio.file.Path;
import java.time.Instant;

/** Metadata for a local document attached to an AETHER project. */
public final class ProjectDocument {
    private final String id;
    private final String projectId;
    private final String filename;
    private final String originalFilename;
    private final Path path;
    private final String extension;
    private final String type;
    private final long size;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ProjectDocument(String id, String projectId, String filename, String originalFilename,
                           Path path, String extension, String type, long size,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.filename = filename;
        this.originalFilename = originalFilename;
        this.path = path;
        this.extension = extension;
        this.type = type;
        this.size = size;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getFilename() { return filename; }
    public String getOriginalFilename() { return originalFilename; }
    public Path getPath() { return path; }
    public String getExtension() { return extension; }
    public String getType() { return type; }
    public long getSize() { return size; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
