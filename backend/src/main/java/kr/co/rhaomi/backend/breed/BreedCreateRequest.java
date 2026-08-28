package kr.co.rhaomi.backend.breed;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import kr.co.rhaomi.backend.content.ContentFields;

public record BreedCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 120) @Pattern(regexp = ContentFields.SLUG_PATTERN) String slug,
        String description,
        @PositiveOrZero Integer sortOrder) {}
