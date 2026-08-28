package kr.co.rhaomi.backend.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import kr.co.rhaomi.backend.content.ContentFields;

public record ServiceCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 120) @Pattern(regexp = ContentFields.SLUG_PATTERN) String slug,
        String description,
        @Size(max = 100) String priceText,
        @PositiveOrZero Integer sortOrder) {}
