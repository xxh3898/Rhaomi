package kr.co.rhaomi.backend.breed;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentAudit;
import kr.co.rhaomi.backend.content.ContentFields;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.content.ContentStatusConverter;
import kr.co.rhaomi.backend.content.PublishValidationException;

@Entity
@Table(name = "breeds")
public class Breed {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Convert(converter = ContentStatusConverter.class)
    @Column(nullable = false, length = 16)
    private ContentStatus status;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, updatable = false, length = 120)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Embedded
    private ContentAudit audit;

    protected Breed() {}

    private Breed(String name, String slug, String description, Integer sortOrder, UUID actorId) {
        id = UUID.randomUUID();
        status = ContentStatus.DRAFT;
        this.name = ContentFields.required(name);
        this.slug = ContentFields.required(slug);
        this.description = ContentFields.optional(description);
        this.sortOrder = sortOrder == null ? 100 : sortOrder;
        audit = ContentAudit.create(actorId);
    }

    public static Breed create(
            String name, String slug, String description, Integer sortOrder, UUID actorId) {
        return new Breed(name, slug, description, sortOrder, actorId);
    }

    public void update(
            ContentStatus status, String name, String description, int sortOrder, UUID actorId) {
        var normalizedName = ContentFields.required(name);
        var normalizedDescription = ContentFields.optional(description);
        validatePublished(status, normalizedName);

        this.status = status;
        this.name = normalizedName;
        this.description = normalizedDescription;
        this.sortOrder = sortOrder;
        audit.touch(actorId);
    }

    private void validatePublished(ContentStatus candidateStatus, String candidateName) {
        if (candidateStatus == ContentStatus.PUBLISHED
                && (candidateName.isBlank() || slug == null || slug.isBlank())) {
            throw new PublishValidationException();
        }
    }

    public UUID getId() {
        return id;
    }

    public ContentStatus getStatus() {
        return status;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public int getSortOrder() {
        return sortOrder;
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
