package kr.co.rhaomi.backend.media;

import java.nio.file.Path;

public record ProcessedMedia(
        Path path,
        String sourceContentType,
        String contentType,
        String fileExtension,
        long sourceByteSize,
        long byteSize,
        int width,
        int height,
        String sha256) {}
