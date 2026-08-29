package kr.co.rhaomi.backend.gallery;

import java.time.Instant;
import java.util.UUID;

public record GalleryCreateRequest(
        String dogName,
        UUID breedId,
        UUID primaryServiceId,
        UUID coverImageId,
        UUID beforeImageId,
        UUID afterImageId,
        String summary,
        String altText,
        Boolean featured,
        Integer sortOrder,
        Instant performedAt,
        Instant publishedAt) {}
