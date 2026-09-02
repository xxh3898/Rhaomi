package kr.co.rhaomi.backend.notice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record NoticeUpdateRequest(
        @NotBlank @Pattern(regexp = "draft|published|archived") String status,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 300) String summary,
        @Size(max = 50_000) String bodyMarkdown,
        @NotNull Boolean pinned,
        Instant publishedAt,
        Instant expiresAt) {}
