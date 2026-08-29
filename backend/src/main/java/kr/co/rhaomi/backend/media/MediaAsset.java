package kr.co.rhaomi.backend.media;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentAudit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Convert(converter = MediaStatusConverter.class)
    @Column(nullable = false, length = 16)
    private MediaStatus status;

    @Column(name = "source_content_type", nullable = false, length = 32, updatable = false)
    private String sourceContentType;

    @Column(name = "content_type", nullable = false, length = 32, updatable = false)
    private String contentType;

    @Column(name = "file_extension", nullable = false, length = 8, updatable = false)
    private String fileExtension;

    @Column(name = "storage_key", nullable = false, unique = true, length = 255, updatable = false)
    private String storageKey;

    @Column(name = "source_byte_size", nullable = false, updatable = false)
    private long sourceByteSize;

    @Column(name = "byte_size", nullable = false, updatable = false)
    private long byteSize;

    @Column(nullable = false, updatable = false)
    private int width;

    @Column(nullable = false, updatable = false)
    private int height;

    @Column(nullable = false, length = 64, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String sha256;

    @Embedded
    private ContentAudit audit;

    protected MediaAsset() {}

    private MediaAsset(UUID id, StoredMedia storedMedia, UUID actorId) {
        this.id = Objects.requireNonNull(id, "id");
        status = MediaStatus.ACTIVE;
        sourceContentType = storedMedia.sourceContentType();
        contentType = storedMedia.contentType();
        fileExtension = storedMedia.fileExtension();
        storageKey = storedMedia.storageKey();
        sourceByteSize = storedMedia.sourceByteSize();
        byteSize = storedMedia.byteSize();
        width = storedMedia.width();
        height = storedMedia.height();
        sha256 = storedMedia.sha256();
        audit = ContentAudit.create(actorId);
    }

    public static MediaAsset create(UUID id, StoredMedia storedMedia, UUID actorId) {
        return new MediaAsset(id, storedMedia, actorId);
    }

    public void changeStatus(MediaStatus status, UUID actorId) {
        this.status = Objects.requireNonNull(status, "status");
        audit.touch(actorId);
    }

    public UUID getId() {
        return id;
    }

    public MediaStatus getStatus() {
        return status;
    }

    public String getSourceContentType() {
        return sourceContentType;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public long getSourceByteSize() {
        return sourceByteSize;
    }

    public long getByteSize() {
        return byteSize;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getCreatedAt() {
        return audit.getCreatedAt();
    }

    public Instant getUpdatedAt() {
        return audit.getUpdatedAt();
    }

    public UUID getCreatedBy() {
        return audit.getCreatedBy();
    }

    public UUID getUpdatedBy() {
        return audit.getUpdatedBy();
    }
}
