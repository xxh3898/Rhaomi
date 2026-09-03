package kr.co.rhaomi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminLoginRateLimiterTest {

    private static final String EMAIL = "admin.rate-limit@example.com";

    @Test
    void should_refillIdentifierToken_when_fiveMinutesPass() {
        var time = new MutableNanoTime();
        var limiter = new AdminLoginRateLimiter(time, AdminLoginRateLimiter.PRODUCTION_POLICY);

        reject(limiter, EMAIL, 5);

        var limited = assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire(EMAIL));
        assertEquals(300, limited.retryAfterSeconds());

        time.advance(Duration.ofMinutes(5));
        assertDoesNotThrow(() -> limiter.authenticationRejected(limiter.acquire(EMAIL)));
    }

    @Test
    void should_refillGlobalToken_when_twoSecondsPass() {
        var time = new MutableNanoTime();
        var limiter = new AdminLoginRateLimiter(time, AdminLoginRateLimiter.PRODUCTION_POLICY);

        for (var index = 0; index < 10; index++) {
            limiter.authenticationRejected(limiter.acquire(email(index)));
        }

        var limited = assertThrows(
                LoginRateLimitExceededException.class, () -> limiter.acquire(email(10)));
        assertEquals(2, limited.retryAfterSeconds());
        assertEquals(10, limiter.identifierStateCount());

        time.advance(Duration.ofSeconds(2));
        assertDoesNotThrow(() -> limiter.authenticationRejected(limiter.acquire(email(10))));
        assertEquals(11, limiter.identifierStateCount());
    }

    @Test
    void should_shareIdentifierQuota_when_emailDiffersOnlyByStripAndCase() {
        var limiter = new AdminLoginRateLimiter(new MutableNanoTime(), highGlobalPolicy(2_048));

        reject(limiter, "  ADMIN.RATE-LIMIT@EXAMPLE.COM  ", 1);
        reject(limiter, "admin.rate-limit@example.com", 4);

        assertThrows(
                LoginRateLimitExceededException.class,
                () -> limiter.acquire("Admin.Rate-Limit@Example.Com"));
        assertEquals(1, limiter.identifierStateCount());
    }

    @Test
    void should_clearIdentifierState_when_authenticationSucceeds() {
        var limiter = new AdminLoginRateLimiter(new MutableNanoTime(), highGlobalPolicy(2_048));

        for (var index = 0; index < 4; index++) {
            limiter.authenticationRejected(limiter.acquire(EMAIL));
        }
        var successful = limiter.acquire(EMAIL);
        limiter.authenticationSucceeded(successful);

        assertEquals(0, limiter.identifierStateCount());
        assertDoesNotThrow(() -> limiter.authenticationRejected(limiter.acquire(EMAIL)));
    }

    @Test
    void should_restoreOnlyIdentifierToken_when_authenticationServiceFails() {
        var limiter = new AdminLoginRateLimiter(new MutableNanoTime(), highGlobalPolicy(2_048));

        for (var index = 0; index < 6; index++) {
            limiter.authenticationServiceFailed(limiter.acquire(EMAIL));
        }

        reject(limiter, EMAIL, 5);
        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire(EMAIL));
    }

    @Test
    void should_notRestoreGlobalToken_when_authenticationServiceFails() {
        var limiter = new AdminLoginRateLimiter(new MutableNanoTime(), AdminLoginRateLimiter.PRODUCTION_POLICY);

        for (var index = 0; index < 10; index++) {
            limiter.authenticationServiceFailed(limiter.acquire(email(index)));
        }

        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire(email(10)));
    }

    @Test
    void should_boundIdentifierStateAndCleanupOnlyIdleEntries() {
        var time = new MutableNanoTime();
        var policy = new AdminLoginRateLimiter.Policy(
                4_096,
                Duration.ofDays(1),
                1,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                2_048,
                64);
        var limiter = new AdminLoginRateLimiter(time, policy);

        for (var index = 0; index < 2_048; index++) {
            limiter.authenticationRejected(limiter.acquire(email(index)));
        }

        assertEquals(2_048, limiter.identifierStateCount());
        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire(email(2_048)));
        assertEquals(2_048, limiter.identifierStateCount());

        time.advance(Duration.ofMinutes(30));
        limiter.authenticationRejected(limiter.acquire(email(2_048)));
        assertEquals(1_985, limiter.identifierStateCount());
    }

    @Test
    void should_failClosed_when_monotonicTimeMovesBackward() {
        var time = new MutableNanoTime(100);
        var limiter = new AdminLoginRateLimiter(time, AdminLoginRateLimiter.PRODUCTION_POLICY);
        time.set(99);

        var limited = assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire(EMAIL));

        assertEquals(1, limited.retryAfterSeconds());
        assertEquals("LOGIN_RATE_LIMITED", limited.getMessage());
    }

    @Test
    void should_storeOnlySha256IdentifierDigest() throws Exception {
        var limiter = new AdminLoginRateLimiter(new MutableNanoTime(), highGlobalPolicy(2_048));
        var rawEmail = "  Admin.Rate-Limit@Example.Com  ";
        limiter.authenticationRejected(limiter.acquire(rawEmail));

        Field statesField = AdminLoginRateLimiter.class.getDeclaredField("identifierStates");
        statesField.setAccessible(true);
        var states = (Map<?, ?>) statesField.get(limiter);
        var key = states.keySet().iterator().next();
        Field digestField = key.getClass().getDeclaredField("digest");
        digestField.setAccessible(true);
        var digest = (String) digestField.get(key);

        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertFalse(digest.contains("admin.rate-limit@example.com"));
        assertEquals("[redacted]", key.toString());
        assertFalse(limiter.toString().contains(rawEmail));
    }

    private static void reject(AdminLoginRateLimiter limiter, String email, int count) {
        for (var index = 0; index < count; index++) {
            limiter.authenticationRejected(limiter.acquire(email));
        }
    }

    private static String email(int index) {
        return "admin-" + index + "@example.com";
    }

    private static AdminLoginRateLimiter.Policy highGlobalPolicy(int maxIdentifierEntries) {
        return new AdminLoginRateLimiter.Policy(
                4_096,
                Duration.ofDays(1),
                5,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                maxIdentifierEntries,
                64);
    }

    private static final class MutableNanoTime implements AdminLoginRateLimiter.NanoTimeSource {

        private long value;

        private MutableNanoTime() {}

        private MutableNanoTime(long value) {
            this.value = value;
        }

        @Override
        public long nanoTime() {
            return value;
        }

        private void advance(Duration duration) {
            value += duration.toNanos();
        }

        private void set(long value) {
            this.value = value;
        }
    }
}
