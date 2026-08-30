package kr.co.rhaomi.backend.publication;

public enum PublicationState {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    NOOP,
    FAILED,
    COALESCED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, NOOP, FAILED, COALESCED -> true;
            case PENDING, PROCESSING, RETRY_WAIT -> false;
        };
    }
}
