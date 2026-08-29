package kr.co.rhaomi.backend.breed;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record BreedUpdateRequest(
        @NotBlank @Pattern(regexp = "draft|published|archived") String status,
        @NotBlank @Size(max = 100) String name,
        String description,
        @NotNull @PositiveOrZero Integer sortOrder) {}
