package kr.co.rhaomi.backend.media;

public record StoredMedia(
        String sourceContentType,
        String contentType,
        String fileExtension,
        String storageKey,
        long sourceByteSize,
        long byteSize,
        int width,
        int height,
        String sha256) {}
