package kr.co.rhaomi.backend.publication;

public enum PublicationResultCode {
    SUCCESS,
    STALE_TRIGGER,
    NO_PUBLIC_CHANGE,
    TRANSIENT_FAILURE,
    RETRY_EXHAUSTED,
    TERMINAL_FAILURE,
    COALESCED,
    LEASE_EXPIRED
}
