package kr.co.rhaomi.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class AdminLoginRateLimiter {

    static final Policy PRODUCTION_POLICY = new Policy(
            10,
            Duration.ofSeconds(2),
            5,
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            2_048,
            64);

    private static final long INTERNAL_FAILURE_RETRY_SECONDS = 1;
    private static final HexFormat HEX = HexFormat.of();

    private final Object monitor = new Object();
    private final NanoTimeSource timeSource;
    private final Policy policy;
    private final TokenBucket globalBucket;
    private final LinkedHashMap<IdentifierKey, IdentifierState> identifierStates =
            new LinkedHashMap<>(16, 0.75f, true);
    private long lastObservedNanos;

    public AdminLoginRateLimiter() {
        this(System::nanoTime, PRODUCTION_POLICY);
    }

    AdminLoginRateLimiter(NanoTimeSource timeSource, Policy policy) {
        this.timeSource = timeSource;
        this.policy = policy;
        this.lastObservedNanos = readInitialTime();
        this.globalBucket = new TokenBucket(
                policy.globalCapacity(), policy.globalRefill().toNanos(), lastObservedNanos);
    }

    LoginPermit acquire(String email) {
        final IdentifierKey identifierKey;
        try {
            identifierKey = IdentifierKey.from(email);
        } catch (RuntimeException exception) {
            throw limited(INTERNAL_FAILURE_RETRY_SECONDS);
        }

        synchronized (monitor) {
            var now = currentTimeOrFailClosed();
            cleanupIdleStates(now);

            var globalRetry = globalBucket.consume(now);
            if (globalRetry > 0) {
                throw limited(globalRetry);
            }

            var identifierState = identifierStates.get(identifierKey);
            if (identifierState == null) {
                if (identifierStates.size() >= policy.maxIdentifierEntries()) {
                    throw limited(capacityRetrySeconds(now));
                }
                identifierState = new IdentifierState(new TokenBucket(
                        policy.identifierCapacity(),
                        policy.identifierRefill().toNanos(),
                        now));
                identifierStates.put(identifierKey, identifierState);
            }

            identifierState.lastAccessNanos = now;
            var identifierRetry = identifierState.bucket.consume(now);
            if (identifierRetry > 0) {
                throw limited(identifierRetry);
            }

            return new LoginPermit(this, identifierKey, identifierState);
        }
    }

    void authenticationSucceeded(LoginPermit permit) {
        synchronized (monitor) {
            if (!resolve(permit)) {
                return;
            }
            identifierStates.remove(permit.identifierKey, permit.identifierState);
        }
    }

    void authenticationRejected(LoginPermit permit) {
        synchronized (monitor) {
            resolve(permit);
        }
    }

    void authenticationServiceFailed(LoginPermit permit) {
        synchronized (monitor) {
            if (!resolve(permit)) {
                return;
            }

            var current = identifierStates.get(permit.identifierKey);
            if (current == permit.identifierState) {
                var now = currentTimeOrFailClosed();
                current.bucket.restoreOne();
                current.lastAccessNanos = now;
            }
        }
    }

    void resetForTesting() {
        synchronized (monitor) {
            var now = currentTimeOrFailClosed();
            identifierStates.clear();
            globalBucket.reset(now);
        }
    }

    int identifierStateCount() {
        synchronized (monitor) {
            return identifierStates.size();
        }
    }

    private boolean resolve(LoginPermit permit) {
        if (permit.owner != this || permit.resolved) {
            return false;
        }
        permit.resolved = true;
        return true;
    }

    private long readInitialTime() {
        try {
            return timeSource.nanoTime();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Monotonic time source is unavailable", exception);
        }
    }

    private long currentTimeOrFailClosed() {
        final long now;
        try {
            now = timeSource.nanoTime();
        } catch (RuntimeException exception) {
            throw limited(INTERNAL_FAILURE_RETRY_SECONDS);
        }
        if (now < lastObservedNanos) {
            throw limited(INTERNAL_FAILURE_RETRY_SECONDS);
        }
        lastObservedNanos = now;
        return now;
    }

    private void cleanupIdleStates(long now) {
        var iterator = identifierStates.entrySet().iterator();
        var inspected = 0;
        while (iterator.hasNext() && inspected < policy.cleanupBatchSize()) {
            var state = iterator.next().getValue();
            if (elapsed(now, state.lastAccessNanos) < policy.identifierIdle().toNanos()) {
                break;
            }
            iterator.remove();
            inspected++;
        }
    }

    private long capacityRetrySeconds(long now) {
        var eldest = identifierStates.entrySet().iterator().next().getValue();
        var remaining = policy.identifierIdle().toNanos() - elapsed(now, eldest.lastAccessNanos);
        return retrySeconds(remaining);
    }

    private static long elapsed(long now, long before) {
        if (now < before) {
            throw limited(INTERNAL_FAILURE_RETRY_SECONDS);
        }
        return now - before;
    }

    private static LoginRateLimitExceededException limited(long retryAfterSeconds) {
        return new LoginRateLimitExceededException(Math.max(1, retryAfterSeconds));
    }

    private static long retrySeconds(long remainingNanos) {
        if (remainingNanos <= 0) {
            return 1;
        }
        var wholeSeconds = Duration.ofNanos(remainingNanos).toSeconds();
        return Math.max(1, wholeSeconds + (remainingNanos % Duration.ofSeconds(1).toNanos() == 0 ? 0 : 1));
    }

    @FunctionalInterface
    interface NanoTimeSource {
        long nanoTime();
    }

    record Policy(
            int globalCapacity,
            Duration globalRefill,
            int identifierCapacity,
            Duration identifierRefill,
            Duration identifierIdle,
            int maxIdentifierEntries,
            int cleanupBatchSize) {

        Policy {
            if (globalCapacity < 1
                    || globalRefill.isZero()
                    || globalRefill.isNegative()
                    || identifierCapacity < 1
                    || identifierRefill.isZero()
                    || identifierRefill.isNegative()
                    || identifierIdle.isZero()
                    || identifierIdle.isNegative()
                    || maxIdentifierEntries < 1
                    || cleanupBatchSize < 1) {
                throw new IllegalArgumentException("Invalid login rate-limit policy");
            }
        }
    }

    static final class LoginPermit {

        private final AdminLoginRateLimiter owner;
        private final IdentifierKey identifierKey;
        private final IdentifierState identifierState;
        private boolean resolved;

        private LoginPermit(
                AdminLoginRateLimiter owner,
                IdentifierKey identifierKey,
                IdentifierState identifierState) {
            this.owner = owner;
            this.identifierKey = identifierKey;
            this.identifierState = identifierState;
        }
    }

    private record IdentifierKey(String digest) {

        private static IdentifierKey from(String email) {
            var normalized = email.strip().toLowerCase(Locale.ROOT);
            try {
                var digest = MessageDigest.getInstance("SHA-256")
                        .digest(normalized.getBytes(StandardCharsets.UTF_8));
                return new IdentifierKey(HEX.formatHex(digest));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        @Override
        public String toString() {
            return "[redacted]";
        }
    }

    private static final class IdentifierState {

        private final TokenBucket bucket;
        private long lastAccessNanos;

        private IdentifierState(TokenBucket bucket) {
            this.bucket = bucket;
            this.lastAccessNanos = bucket.lastRefillNanos;
        }
    }

    private static final class TokenBucket {

        private final int capacity;
        private final long refillNanos;
        private int tokens;
        private long lastRefillNanos;

        private TokenBucket(int capacity, long refillNanos, long now) {
            this.capacity = capacity;
            this.refillNanos = refillNanos;
            this.tokens = capacity;
            this.lastRefillNanos = now;
        }

        private long consume(long now) {
            refill(now);
            if (tokens > 0) {
                tokens--;
                return 0;
            }
            return retrySeconds(refillNanos - elapsed(now, lastRefillNanos));
        }

        private void refill(long now) {
            var elapsed = elapsed(now, lastRefillNanos);
            if (elapsed < refillNanos) {
                return;
            }

            var refillCount = elapsed / refillNanos;
            if (refillCount >= capacity - tokens) {
                tokens = capacity;
                lastRefillNanos = now;
                return;
            }

            tokens += Math.toIntExact(refillCount);
            lastRefillNanos += refillCount * refillNanos;
        }

        private void restoreOne() {
            if (tokens < capacity) {
                tokens++;
            }
        }

        private void reset(long now) {
            tokens = capacity;
            lastRefillNanos = now;
        }
    }
}
