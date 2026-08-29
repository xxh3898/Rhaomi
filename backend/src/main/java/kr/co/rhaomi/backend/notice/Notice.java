package kr.co.rhaomi.backend.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentAudit;
import kr.co.rhaomi.backend.content.ContentFields;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.content.ContentStatusConverter;
import kr.co.rhaomi.backend.content.NoticeWindowInvalidException;
import kr.co.rhaomi.backend.content.PublishValidationException;

@Entity
@Table(name = "notices")
public class Notice {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Convert(converter = ContentStatusConverter.class)
    @Column(nullable = false, length = 16)
    private ContentStatus status;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, unique = true, updatable = false, length = 160)
    private String slug;

    @Column(length = 300)
    private String summary;

    @Column(name = "body_markdown", columnDefinition = "TEXT")
    private String bodyMarkdown;

    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Embedded
    private ContentAudit audit;

    protected Notice() {}

    private Notice(
            String title,
            String slug,
            String summary,
            String bodyMarkdown,
            Boolean pinned,
            Instant publishedAt,
            Instant expiresAt,
            UUID actorId) {
        var normalizedTitle = ContentFields.required(title);
        var normalizedSlug = ContentFields.required(slug);
        var normalizedSummary = ContentFields.optional(summary);
        var normalizedBodyMarkdown = ContentFields.optional(bodyMarkdown);
        var normalizedPublishedAt = normalizeTimestamp(publishedAt);
        var normalizedExpiresAt = normalizeTimestamp(expiresAt);
        validateWindow(normalizedPublishedAt, normalizedExpiresAt);

        id = UUID.randomUUID();
        status = ContentStatus.DRAFT;
        this.title = normalizedTitle;
        this.slug = normalizedSlug;
        this.summary = normalizedSummary;
        this.bodyMarkdown = normalizedBodyMarkdown;
        this.pinned = Boolean.TRUE.equals(pinned);
        this.publishedAt = normalizedPublishedAt;
        this.expiresAt = normalizedExpiresAt;
        audit = ContentAudit.create(actorId);
    }

    public static Notice create(
            String title,
            String slug,
            String summary,
            String bodyMarkdown,
            Boolean pinned,
            Instant publishedAt,
            Instant expiresAt,
            UUID actorId) {
        return new Notice(
                title,
                slug,
                summary,
                bodyMarkdown,
                pinned,
                publishedAt,
                expiresAt,
                actorId);
    }

    public void update(
            ContentStatus status,
            String title,
            String summary,
            String bodyMarkdown,
            boolean pinned,
            Instant publishedAt,
            Instant expiresAt,
            UUID actorId) {
        var normalizedTitle = ContentFields.required(title);
        var normalizedSummary = ContentFields.optional(summary);
        var normalizedBodyMarkdown = ContentFields.optional(bodyMarkdown);
        var normalizedPublishedAt = normalizeTimestamp(publishedAt);
        var normalizedExpiresAt = normalizeTimestamp(expiresAt);
        validateWindow(normalizedPublishedAt, normalizedExpiresAt);
        validatePublished(status, normalizedTitle, normalizedBodyMarkdown, normalizedPublishedAt);

        this.status = status;
        this.title = normalizedTitle;
        this.summary = normalizedSummary;
        this.bodyMarkdown = normalizedBodyMarkdown;
        this.pinned = pinned;
        this.publishedAt = normalizedPublishedAt;
        this.expiresAt = normalizedExpiresAt;
        audit.touch(actorId);
    }

    private static Instant normalizeTimestamp(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private void validatePublished(
            ContentStatus candidateStatus,
            String candidateTitle,
            String candidateBodyMarkdown,
            Instant candidatePublishedAt) {
        if (candidateStatus == ContentStatus.PUBLISHED
                && (candidateTitle.isBlank()
                        || slug == null
                        || slug.isBlank()
                        || candidateBodyMarkdown == null
                        || candidatePublishedAt == null)) {
            throw new PublishValidationException();
        }
    }

    private static void validateWindow(Instant candidatePublishedAt, Instant candidateExpiresAt) {
        if (candidateExpiresAt != null
                && (candidatePublishedAt == null || !candidateExpiresAt.isAfter(candidatePublishedAt))) {
            throw new NoticeWindowInvalidException();
        }
    }

    public UUID getId() {
        return id;
    }

    public ContentStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getSlug() {
        return slug;
    }

    public String getSummary() {
        return summary;
    }

    public String getBodyMarkdown() {
        return bodyMarkdown;
    }

    public boolean isPinned() {
        return pinned;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
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
