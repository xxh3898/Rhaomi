package kr.co.rhaomi.backend.media;

import jakarta.validation.constraints.NotNull;

public record MediaStatusUpdateRequest(@NotNull MediaStatus status) {}
