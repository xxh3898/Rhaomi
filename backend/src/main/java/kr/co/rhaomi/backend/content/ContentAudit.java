package kr.co.rhaomi.backend.content;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ContentAudit {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    protected ContentAudit() {}

    private ContentAudit(UUID actorId) {
        var actor = Objects.requireNonNull(actorId, "actorId");
        var now = now();
        createdAt = now;
        updatedAt = now;
        createdBy = actor;
        updatedBy = actor;
    }

    public static ContentAudit create(UUID actorId) {
        return new ContentAudit(actorId);
    }

    public void touch(UUID actorId) {
        var actor = Objects.requireNonNull(actorId, "actorId");
        updatedAt = now();
        updatedBy = actor;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
