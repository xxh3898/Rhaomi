package kr.co.rhaomi.backend.gallery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentStatus;

record GalleryValues(
        ContentStatus status,
        String dogName,
        UUID breedId,
        UUID primaryServiceId,
        UUID coverImageId,
        UUID beforeImageId,
        UUID afterImageId,
        String summary,
        String altText,
        boolean featured,
        int sortOrder,
        Instant performedAt,
        Instant publishedAt) {

    static GalleryValues fromCreate(GalleryCreateRequest request) {
        if (request == null) {
            throw new GalleryInvalidRequestException();
        }
        return create(
                ContentStatus.DRAFT,
                request.dogName(),
                request.breedId(),
                request.primaryServiceId(),
                request.coverImageId(),
                request.beforeImageId(),
                request.afterImageId(),
                request.summary(),
                request.altText(),
                Boolean.TRUE.equals(request.featured()),
                request.sortOrder() == null ? 100 : request.sortOrder(),
                request.performedAt(),
                request.publishedAt());
    }

    static GalleryValues fromUpdate(GalleryUpdateRequest request) {
        if (request == null || request.featured() == null || request.sortOrder() == null) {
            throw new GalleryInvalidRequestException();
        }
        final ContentStatus status;
        try {
            status = ContentStatus.fromApiValue(request.status());
        } catch (IllegalArgumentException exception) {
            throw new GalleryInvalidRequestException();
        }
        return create(
                status,
                request.dogName(),
                request.breedId(),
                request.primaryServiceId(),
                request.coverImageId(),
                request.beforeImageId(),
                request.afterImageId(),
                request.summary(),
                request.altText(),
                request.featured(),
                request.sortOrder(),
                request.performedAt(),
                request.publishedAt());
    }

    private static GalleryValues create(
            ContentStatus status,
            String dogName,
            UUID breedId,
            UUID primaryServiceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            String summary,
            String altText,
            boolean featured,
            int sortOrder,
            Instant performedAt,
            Instant publishedAt) {
        if (sortOrder < 0) {
            throw new GalleryInvalidRequestException();
        }
        if (beforeImageId != null && beforeImageId.equals(afterImageId)) {
            throw new GalleryPublishInvalidException();
        }

        var values = new GalleryValues(
                status,
                optional(dogName, 100),
                breedId,
                primaryServiceId,
                coverImageId,
                beforeImageId,
                afterImageId,
                optional(summary, 1_000),
                optional(altText, 300),
                featured,
                sortOrder,
                normalizeTimestamp(performedAt),
                normalizeTimestamp(publishedAt));
        values.validatePublished();
        return values;
    }

    private void validatePublished() {
        if (status == ContentStatus.PUBLISHED
                && (breedId == null
                        || primaryServiceId == null
                        || coverImageId == null
                        || altText == null
                        || publishedAt == null)) {
            throw new GalleryPublishInvalidException();
        }
    }

    private static String optional(String value, int maxLength) {
        var normalized = value == null ? null : stripUnicodeWhitespace(value);
        if (normalized != null && normalized.isEmpty()) {
            return null;
        }
        if (normalized != null && normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw new GalleryInvalidRequestException();
        }
        return normalized;
    }

    private static Instant normalizeTimestamp(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private static String stripUnicodeWhitespace(String value) {
        var start = 0;
        var end = value.length();
        while (start < end) {
            var codePoint = value.codePointAt(start);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            var codePoint = value.codePointBefore(end);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
