package kr.co.rhaomi.backend.media;

import java.nio.file.Path;

public record MediaSourceFile(Path path, long byteSize, String sha256) {}
