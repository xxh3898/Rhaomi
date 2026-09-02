package kr.co.rhaomi.backend.auth.webauthn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class AdminWebAuthnCeremonyStoreTest {

    private final AdminWebAuthnCeremonyStore ceremonies = new AdminWebAuthnCeremonyStore();

    @Test
    void should_consumeRegistrationCeremonyExactlyOnce_whenConsumersRace() throws Exception {
        assertAtomicSingleUse(
                ceremonies::storeRegistration,
                session -> ceremonies.consumeRegistration(session, Serializable.class));
    }

    @Test
    void should_consumeAuthenticationCeremonyExactlyOnce_whenConsumersRace() throws Exception {
        assertAtomicSingleUse(
                ceremonies::storeAuthentication,
                session -> ceremonies.consumeAuthentication(session, Serializable.class));
    }

    private static void assertAtomicSingleUse(
            BiConsumer<HttpSession, Serializable> store,
            Function<HttpSession, Serializable> consume)
            throws Exception {
        var session = new ConcurrentReadHttpSession();
        store.accept(session, "ceremony");
        session.enableConcurrentReads();

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> consume(consume, session));
            var second = executor.submit(() -> consume(consume, session));
            var outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, outcomes.stream().filter(outcome -> !outcome).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static boolean consume(
            Function<HttpSession, Serializable> consume, HttpSession session) {
        try {
            consume.apply(session);
            return true;
        } catch (WebAuthnVerificationException exception) {
            return false;
        }
    }

    private static final class ConcurrentReadHttpSession extends MockHttpSession {

        private final CountDownLatch concurrentReads = new CountDownLatch(2);
        private volatile boolean concurrentReadsEnabled;

        void enableConcurrentReads() {
            concurrentReadsEnabled = true;
        }

        @Override
        public Object getAttribute(String name) {
            var stored = super.getAttribute(name);
            if (concurrentReadsEnabled && stored != null && !Thread.holdsLock(this)) {
                concurrentReads.countDown();
                awaitConcurrentReads();
            }
            return stored;
        }

        private void awaitConcurrentReads() {
            try {
                if (!concurrentReads.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent ceremony consumers did not overlap");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent ceremony consume was interrupted", exception);
            }
        }
    }
}
