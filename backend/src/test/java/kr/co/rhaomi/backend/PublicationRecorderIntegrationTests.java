package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationRecorder;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.ScheduledPublicationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class PublicationRecorderIntegrationTests {

    @Autowired
    private PublicationRecorder publicationRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetPublicationState() {
        clearPublicationState();
    }

    @AfterEach
    void resetPublicationStateAfterTest() {
        clearPublicationState();
    }

    @Test
    void should_requireExistingTransaction_when_recorderIsCalledDirectly() {
        assertThrows(
                IllegalTransactionStateException.class,
                () -> publicationRecorder.record(
                        PublicationSourceType.BREED, UUID.randomUUID(), false));

        assertEquals(0L, currentRevision());
        assertEquals(0, eventCount());
    }

    @Test
    void should_allocateUniqueGaplessRevisions_when_transactionsRunConcurrently() throws Exception {
        var mutationCount = 8;
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>(mutationCount);
            for (var index = 0; index < mutationCount; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return new TransactionTemplate(transactionManager).execute(status ->
                            publicationRecorder.record(
                                    PublicationSourceType.BREED,
                                    UUID.randomUUID(),
                                    false));
                }));
            }

            start.countDown();
            var revisions = new HashSet<Long>();
            for (var future : futures) {
                revisions.add(future.get());
            }

            assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), revisions);
            assertEquals(8L, currentRevision());
            assertEquals(0, eventCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void should_notConsumeRevisionOrEvent_when_transactionRollsBack() {
        var transaction = new TransactionTemplate(transactionManager);

        assertThrows(ForcedRollbackException.class, () -> transaction.executeWithoutResult(status -> {
            publicationRecorder.record(
                    PublicationSourceType.BREED, UUID.randomUUID(), true);
            throw new ForcedRollbackException();
        }));

        assertEquals(0L, currentRevision());
        assertEquals(0, eventCount());

        var nextRevision = transaction.execute(status -> publicationRecorder.record(
                PublicationSourceType.BREED, UUID.randomUUID(), true));
        assertEquals(1L, nextRevision);
        assertEquals(1L, currentRevision());
        assertEquals(1, eventCount());
        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT state FROM publishing_outbox", String.class));
    }

    @Test
    void should_rejectMismatchedScheduledSource_withoutAllocatingRevision() {
        var transaction = new TransactionTemplate(transactionManager);
        var boundary = java.time.Instant.parse("2030-01-01T00:00:00.123456Z");

        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(status ->
                publicationRecorder.record(
                        PublicationSourceType.BREED,
                        UUID.randomUUID(),
                        false,
                        new ScheduledPublicationEvent(
                                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                                boundary))));

        assertEquals(0L, currentRevision());
        assertEquals(0, eventCount());
    }

    private long currentRevision() {
        return jdbcTemplate.queryForObject(
                "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1",
                Long.class);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox", Integer.class);
    }

    private void clearPublicationState() {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
    }

    private static final class ForcedRollbackException extends RuntimeException {}
}
