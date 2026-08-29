package kr.co.rhaomi.backend.breed;

import java.time.Instant;
import java.util.UUID;

public record BreedResponse(
        UUID id,
        String status,
        String name,
        String slug,
        String description,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy) {

    public static BreedResponse from(Breed breed) {
        return new BreedResponse(
                breed.getId(),
                breed.getStatus().apiValue(),
                breed.getName(),
                breed.getSlug(),
                breed.getDescription(),
                breed.getSortOrder(),
                breed.getCreatedAt(),
                breed.getUpdatedAt(),
                breed.getCreatedBy(),
                breed.getUpdatedBy());
    }
}
