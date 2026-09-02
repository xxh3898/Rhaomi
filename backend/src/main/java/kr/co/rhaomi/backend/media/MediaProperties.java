package kr.co.rhaomi.backend.media;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rhaomi.media")
public record MediaProperties(
        @NotBlank String root,
        @Min(1) long maxSourceBytes,
        @Min(1) long maxStoredBytes,
        @Min(1) int maxWidth,
        @Min(1) int maxHeight,
        @Min(1) long maxPixels,
        @Min(1) @Max(100) int jpegQuality) {}
