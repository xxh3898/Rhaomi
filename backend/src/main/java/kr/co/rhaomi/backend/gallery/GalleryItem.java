package kr.co.rhaomi.backend.gallery;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentAudit;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.content.ContentStatusConverter;

@Entity
@Table(name = "gallery_items")
public class GalleryItem {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Convert(converter = ContentStatusConverter.class)
    @Column(nullable = false, length = 16)
    private ContentStatus status;

    @Column(name = "dog_name", length = 100)
    private String dogName;

    @Column(name = "breed_id")
    private UUID breedId;

    @Column(name = "primary_service_id")
    private UUID primaryServiceId;

    @Column(name = "cover_image_id")
    private UUID coverImageId;

    @Column(name = "before_image_id")
    private UUID beforeImageId;

    @Column(name = "after_image_id")
    private UUID afterImageId;

    @Column(length = 1_000)
    private String summary;

    @Column(name = "alt_text", length = 300)
    private String altText;

    @Column(nullable = false)
    private boolean featured;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "performed_at")
    private Instant performedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Embedded
    private ContentAudit audit;

    protected GalleryItem() {}

    private GalleryItem(GalleryValues values, UUID actorId) {
        id = UUID.randomUUID();
        audit = ContentAudit.create(actorId);
        apply(values);
    }

    public static GalleryItem create(GalleryValues values, UUID actorId) {
        return new GalleryItem(values, actorId);
    }

    public void update(GalleryValues values, UUID actorId) {
        apply(values);
        audit.touch(actorId);
    }

    private void apply(GalleryValues values) {
        status = values.status();
        dogName = values.dogName();
        breedId = values.breedId();
        primaryServiceId = values.primaryServiceId();
        coverImageId = values.coverImageId();
        beforeImageId = values.beforeImageId();
        afterImageId = values.afterImageId();
        summary = values.summary();
        altText = values.altText();
        featured = values.featured();
        sortOrder = values.sortOrder();
        performedAt = values.performedAt();
        publishedAt = values.publishedAt();
    }

    public UUID getId() {
        return id;
    }

    public ContentStatus getStatus() {
        return status;
    }

    public String getDogName() {
        return dogName;
    }

    public UUID getBreedId() {
        return breedId;
    }

    public UUID getPrimaryServiceId() {
        return primaryServiceId;
    }

    public UUID getCoverImageId() {
        return coverImageId;
    }

    public UUID getBeforeImageId() {
        return beforeImageId;
    }

    public UUID getAfterImageId() {
        return afterImageId;
    }

    public String getSummary() {
        return summary;
    }

    public String getAltText() {
        return altText;
    }

    public boolean isFeatured() {
        return featured;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
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
