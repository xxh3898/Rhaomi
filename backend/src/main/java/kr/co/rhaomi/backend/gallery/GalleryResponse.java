package kr.co.rhaomi.backend.gallery;

import java.time.Instant;
import java.util.UUID;

public record GalleryResponse(
        UUID id,
        String status,
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
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy) {

    public static GalleryResponse from(GalleryItem item) {
        return new GalleryResponse(
                item.getId(),
                item.getStatus().apiValue(),
                item.getDogName(),
                item.getBreedId(),
                item.getPrimaryServiceId(),
                item.getCoverImageId(),
                item.getBeforeImageId(),
                item.getAfterImageId(),
                item.getSummary(),
                item.getAltText(),
                item.isFeatured(),
                item.getSortOrder(),
                item.getPerformedAt(),
                item.getPublishedAt(),
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getCreatedBy(),
                item.getUpdatedBy());
    }
}
