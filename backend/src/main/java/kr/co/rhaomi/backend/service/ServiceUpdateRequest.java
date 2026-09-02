package kr.co.rhaomi.backend.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ServiceUpdateRequest(
        @NotBlank @Pattern(regexp = "draft|published|archived") String status,
        @NotBlank @Size(max = 100) String name,
        String description,
        @Size(max = 100) String priceText,
        @NotNull @PositiveOrZero Integer sortOrder) {}
