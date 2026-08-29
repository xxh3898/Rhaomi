package kr.co.rhaomi.backend.media;

enum MediaSourceType {
    JPEG("image/jpeg", "image/jpeg", "jpg", false),
    PNG("image/png", "image/png", "png", false),
    HEIC("image/heic", "image/jpeg", "jpg", true),
    HEIF("image/heif", "image/jpeg", "jpg", true);

    private final String sourceContentType;
    private final String storedContentType;
    private final String storedExtension;
    private final boolean heifFamily;

    MediaSourceType(
            String sourceContentType,
            String storedContentType,
            String storedExtension,
            boolean heifFamily) {
        this.sourceContentType = sourceContentType;
        this.storedContentType = storedContentType;
        this.storedExtension = storedExtension;
        this.heifFamily = heifFamily;
    }

    String sourceContentType() {
        return sourceContentType;
    }

    String storedContentType() {
        return storedContentType;
    }

    String storedExtension() {
        return storedExtension;
    }

    boolean isHeifFamily() {
        return heifFamily;
    }
}
