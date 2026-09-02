package kr.co.rhaomi.backend.auth;

final class LoginRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    LoginRateLimitExceededException(long retryAfterSeconds) {
        super("LOGIN_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
