package kr.co.rhaomi.backend.media;

import java.time.Instant;
import java.util.UUID;

public record MediaResponse(
        UUID id,
        String status,
        String sourceContentType,
        String contentType,
        long sourceByteSize,
        long byteSize,
        int width,
        int height,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy) {

    public static MediaResponse from(MediaAsset asset) {
        return new MediaResponse(
                asset.getId(),
                asset.getStatus().value(),
                asset.getSourceContentType(),
                asset.getContentType(),
                asset.getSourceByteSize(),
                asset.getByteSize(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                asset.getCreatedBy(),
                asset.getUpdatedBy());
    }
}
