package kr.co.rhaomi.backend.gallery;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

public record GalleryUpdateRequest(
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = "draft|published|archived")
                String status,
        @JsonProperty(required = true) String dogName,
        @JsonProperty(required = true) UUID breedId,
        @JsonProperty(required = true) UUID primaryServiceId,
        @JsonProperty(required = true) UUID coverImageId,
        @JsonProperty(required = true) UUID beforeImageId,
        @JsonProperty(required = true) UUID afterImageId,
        @JsonProperty(required = true) String summary,
        @JsonProperty(required = true) String altText,
        @JsonProperty(required = true) @NotNull Boolean featured,
        @JsonProperty(required = true) @NotNull Integer sortOrder,
        @JsonProperty(required = true) Instant performedAt,
        @JsonProperty(required = true) Instant publishedAt) {}
