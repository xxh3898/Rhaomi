package kr.co.rhaomi.backend.notice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import kr.co.rhaomi.backend.content.ContentFields;

public record NoticeCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 160) @Pattern(regexp = ContentFields.SLUG_PATTERN) String slug,
        @Size(max = 300) String summary,
        @Size(max = 50_000) String bodyMarkdown,
        Boolean pinned,
        Instant publishedAt,
        Instant expiresAt) {}
