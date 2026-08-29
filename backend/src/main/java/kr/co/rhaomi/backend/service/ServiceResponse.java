package kr.co.rhaomi.backend.service;

import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        String status,
        String name,
        String slug,
        String description,
        String priceText,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy) {

    public static ServiceResponse from(GroomingService service) {
        return new ServiceResponse(
                service.getId(),
                service.getStatus().apiValue(),
                service.getName(),
                service.getSlug(),
                service.getDescription(),
                service.getPriceText(),
                service.getSortOrder(),
                service.getCreatedAt(),
                service.getUpdatedAt(),
                service.getCreatedBy(),
                service.getUpdatedBy());
    }
}
