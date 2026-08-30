package kr.co.rhaomi.backend.publication;

public enum PublicationEventKind {
    CONTENT_CHANGED(false),
    NOTICE_PUBLISHED_AT_DUE(true),
    NOTICE_EXPIRES_AT_DUE(true),
    GALLERY_PUBLISHED_AT_DUE(true);

    private final boolean scheduled;

    PublicationEventKind(boolean scheduled) {
        this.scheduled = scheduled;
    }

    public boolean isScheduled() {
        return scheduled;
    }
}
