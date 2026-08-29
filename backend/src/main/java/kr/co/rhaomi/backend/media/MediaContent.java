package kr.co.rhaomi.backend.media;

import java.nio.file.Path;

public record MediaContent(Path path, String contentType, long byteSize) {}
