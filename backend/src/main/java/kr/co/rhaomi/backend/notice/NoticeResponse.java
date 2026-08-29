package kr.co.rhaomi.backend.notice;

import java.time.Instant;
import java.util.UUID;

public record NoticeResponse(
        UUID id,
        String status,
        String title,
        String slug,
        String summary,
        String bodyMarkdown,
        boolean pinned,
        Instant publishedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy) {

    public static NoticeResponse from(Notice notice) {
        return new NoticeResponse(
                notice.getId(),
                notice.getStatus().apiValue(),
                notice.getTitle(),
                notice.getSlug(),
                notice.getSummary(),
                notice.getBodyMarkdown(),
                notice.isPinned(),
                notice.getPublishedAt(),
                notice.getExpiresAt(),
                notice.getCreatedAt(),
                notice.getUpdatedAt(),
                notice.getCreatedBy(),
                notice.getUpdatedBy());
    }
}
