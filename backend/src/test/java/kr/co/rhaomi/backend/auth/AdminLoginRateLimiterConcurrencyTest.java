package kr.co.rhaomi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

class AdminLoginRateLimiterConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 20;

    @Test
    void should_invokeAuthenticationManagerAtMostFiveTimes_when_sameIdentifierRequestsRace()
            throws Exception {
        var result = runConcurrentLogins(index -> "same-admin@example.com");

        assertEquals(5, result.authenticationCalls());
        assertEquals(5, result.credentialFailures());
        assertEquals(15, result.rateLimitedFailures());
    }

    @Test
    void should_invokeAuthenticationManagerAtMostTenTimes_when_differentIdentifiersRace()
            throws Exception {
        var result = runConcurrentLogins(index -> "admin-" + index + "@example.com");

        assertEquals(10, result.authenticationCalls());
        assertEquals(10, result.credentialFailures());
        assertEquals(10, result.rateLimitedFailures());
    }

    private static ConcurrentResult runConcurrentLogins(EmailFactory emailFactory) throws Exception {
        var authenticationCalls = new AtomicInteger();
        var authenticationManager = (org.springframework.security.authentication.AuthenticationManager)
                authentication -> {
                    authenticationCalls.incrementAndGet();
                    throw new BadCredentialsException("synthetic credential failure");
                };
        var controller = new AuthController(
                authenticationManager,
                mock(SessionAuthenticationStrategy.class),
                mock(SecurityContextRepository.class),
                new AdminLoginRateLimiter(() -> 0, AdminLoginRateLimiter.PRODUCTION_POLICY));
        var barrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        var executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        try {
            List<java.util.concurrent.Future<LoginOutcome>> futures = new ArrayList<>();
            for (var index = 0; index < CONCURRENT_REQUESTS; index++) {
                var email = emailFactory.create(index);
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        controller.login(
                                new LoginRequest(email, "synthetic-password"),
                                new MockHttpServletRequest(),
                                new MockHttpServletResponse());
                        throw new AssertionError("Synthetic authentication must fail");
                    } catch (InvalidAdminCredentialsException exception) {
                        return LoginOutcome.CREDENTIAL_FAILURE;
                    } catch (LoginRateLimitExceededException exception) {
                        return LoginOutcome.RATE_LIMITED;
                    }
                }));
            }

            var outcomes = new ArrayList<LoginOutcome>();
            for (var future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            return new ConcurrentResult(
                    authenticationCalls.get(),
                    outcomes.stream().filter(outcome -> outcome == LoginOutcome.CREDENTIAL_FAILURE).count(),
                    outcomes.stream().filter(outcome -> outcome == LoginOutcome.RATE_LIMITED).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface EmailFactory {
        String create(int index);
    }

    private enum LoginOutcome {
        CREDENTIAL_FAILURE,
        RATE_LIMITED
    }

    private record ConcurrentResult(
            int authenticationCalls, long credentialFailures, long rateLimitedFailures) {}
}
