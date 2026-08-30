package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import kr.co.rhaomi.backend.publication.PublicationResultCode;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.PublicationState;
import kr.co.rhaomi.backend.publication.PublicationStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class PublicationStateServiceIntegrationTests {

    private static final String OWNER = "publisher-test-1";
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final Instant NOW = Instant.parse("2035-01-01T00:00:00.123456Z");

    @Autowired
    private PublicationStateService publicationStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID adminId;

    @BeforeEach
    void setUpFixtures() {
        clearFixtures();
        adminId = insertAdmin();
    }

    @AfterEach
    void clearFixturesAfterTest() {
        clearFixtures();
    }

    @Test
    void should_claimImmediatePendingWithFirstGeneration_when_eventIsDue() {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                NOW.minusSeconds(1),
                null);

        var claimed = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();

        assertEquals(eventId, claimed.eventId());
        assertEquals(PublicationState.PROCESSING, claimed.state());
        assertEquals(1L, claimed.publishGeneration());
        assertEquals(1, claimed.attemptCount());
        assertEquals(OWNER, claimed.claimOwner());
        assertEquals(NOW, claimed.claimedAt());
        assertEquals(NOW.plus(LEASE), claimed.leaseUntil());
        assertNull(claimed.lastResultCode());
        assertEquals(1L, currentGeneration());
    }

    @Test
    void should_notClaimFutureScheduledEvent_when_availableAtIsAfterNow() {
        var boundary = NOW.plusSeconds(1);
        var eventId = insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                UUID.randomUUID(),
                1,
                boundary,
                boundary);

        assertTrue(publicationStateService.claimNext(OWNER, NOW, LEASE).isEmpty());
        assertEquals(PublicationState.PENDING, status(eventId).state());
        assertEquals(0L, currentGeneration());
    }

    @Test
    void should_claimDueNoticeAndGalleryBoundaries_when_currentSourceMatchesExactly() {
        var noticePublishedAt = NOW.minusSeconds(20);
        var noticeExpiresAt = NOW.plus(Duration.ofDays(1));
        var noticeId = insertNotice("published", noticePublishedAt, noticeExpiresAt);
        var noticeEventId = insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                noticeId,
                7,
                noticePublishedAt,
                noticePublishedAt);
        var galleryPublishedAt = NOW.minusSeconds(10);
        var galleryId = insertGallery("published", galleryPublishedAt);
        var galleryEventId = insertEvent(
                PublicationEventKind.GALLERY_PUBLISHED_AT_DUE,
                PublicationSourceType.GALLERY_ITEM,
                galleryId,
                8,
                galleryPublishedAt,
                galleryPublishedAt);
        var noticeExpiryAt = NOW.minusSeconds(5);
        var expiringNoticeId = insertNotice(
                "published", NOW.minus(Duration.ofDays(1)), noticeExpiryAt);
        var noticeExpiryEventId = insertEvent(
                PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                PublicationSourceType.NOTICE,
                expiringNoticeId,
                9,
                noticeExpiryAt,
                noticeExpiryAt);

        var noticeClaim = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        var galleryClaim = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        var noticeExpiryClaim = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();

        assertEquals(noticeEventId, noticeClaim.eventId());
        assertEquals(1L, noticeClaim.publishGeneration());
        assertEquals(galleryEventId, galleryClaim.eventId());
        assertEquals(2L, galleryClaim.publishGeneration());
        assertEquals(noticeExpiryEventId, noticeExpiryClaim.eventId());
        assertEquals(3L, noticeExpiryClaim.publishGeneration());
    }

    @Test
    void should_markNoticeScheduledEventsStaleWithoutGeneration_when_sourceChangesOrDisappears() {
        var expected = NOW.minus(Duration.ofMinutes(10));

        var rescheduledId = insertNotice(
                "published", expected.plus(Duration.ofMinutes(1)), NOW.plus(Duration.ofDays(1)));
        assertStale(insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                rescheduledId,
                1,
                expected,
                expected));

        var draftId = insertNotice("draft", expected, NOW.plus(Duration.ofDays(1)));
        assertStale(insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                draftId,
                2,
                expected,
                expected));

        var archivedId = insertNotice("archived", expected, NOW.plus(Duration.ofDays(1)));
        assertStale(insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                archivedId,
                3,
                expected,
                expected));

        var expiryChangedId = insertNotice(
                "published",
                expected.minus(Duration.ofDays(1)),
                expected.plus(Duration.ofMinutes(1)));
        assertStale(insertEvent(
                PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                PublicationSourceType.NOTICE,
                expiryChangedId,
                4,
                expected,
                expected));

        var expiryRemovedId = insertNotice(
                "published", expected.minus(Duration.ofDays(1)), null);
        assertStale(insertEvent(
                PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                PublicationSourceType.NOTICE,
                expiryRemovedId,
                5,
                expected,
                expected));

        assertStale(insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                UUID.randomUUID(),
                6,
                expected,
                expected));
        assertEquals(0L, currentGeneration());
    }

    @Test
    void should_markGalleryScheduledEventsStaleWithoutGeneration_when_sourceChangesOrDisappears() {
        var expected = NOW.minus(Duration.ofMinutes(10));

        var rescheduledId = insertGallery(
                "published", expected.plus(Duration.ofMinutes(1)));
        assertStale(insertEvent(
                PublicationEventKind.GALLERY_PUBLISHED_AT_DUE,
                PublicationSourceType.GALLERY_ITEM,
                rescheduledId,
                1,
                expected,
                expected));

        var draftId = insertGallery("draft", expected);
        assertStale(insertEvent(
                PublicationEventKind.GALLERY_PUBLISHED_AT_DUE,
                PublicationSourceType.GALLERY_ITEM,
                draftId,
                2,
                expected,
                expected));

        var archivedId = insertGallery("archived", expected);
        assertStale(insertEvent(
                PublicationEventKind.GALLERY_PUBLISHED_AT_DUE,
                PublicationSourceType.GALLERY_ITEM,
                archivedId,
                3,
                expected,
                expected));

        assertStale(insertEvent(
                PublicationEventKind.GALLERY_PUBLISHED_AT_DUE,
                PublicationSourceType.GALLERY_ITEM,
                UUID.randomUUID(),
                4,
                expected,
                expected));
        assertEquals(0L, currentGeneration());
    }

    @Test
    void should_preservePendingAndGenerationCounter_when_claimTransactionRollsBack() {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.SERVICE,
                UUID.randomUUID(),
                1,
                NOW,
                null);
        jdbcTemplate.execute(
                """
                ALTER TABLE publishing_outbox
                ADD CONSTRAINT ck_publishing_outbox_test_reject_processing
                CHECK (state <> 'PROCESSING')
                """);
        try {
            assertThrows(
                    DataAccessException.class,
                    () -> publicationStateService.claimNext(OWNER, NOW, LEASE));
        } finally {
            jdbcTemplate.execute(
                    """
                    ALTER TABLE publishing_outbox
                    DROP CONSTRAINT IF EXISTS ck_publishing_outbox_test_reject_processing
                    """);
        }

        assertEquals(PublicationState.PENDING, status(eventId).state());
        assertNull(status(eventId).publishGeneration());
        assertEquals(0L, currentGeneration());

        var claimed = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        assertEquals(1L, claimed.publishGeneration());
    }

    @Test
    void should_allowOnlyOneConcurrentClaim_when_sameEventIsReady() throws Exception {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.SHOP_SETTINGS,
                UUID.randomUUID(),
                1,
                NOW,
                null);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return publicationStateService.claimNext("publisher-a", NOW, LEASE);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return publicationStateService.claimNext("publisher-b", NOW, LEASE);
                    }));
            start.countDown();

            var claimed = new ArrayList<PublicationEventStatus>();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS).ifPresent(claimed::add);
            }

            assertEquals(1, claimed.size());
            assertEquals(eventId, claimed.getFirst().eventId());
            assertEquals(1L, currentGeneration());
            assertEquals(1, status(eventId).attemptCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void should_preserveAvailableAtIdOrdering_when_readyEventsAreUnlocked() {
        var availableAt = NOW.minusSeconds(1);
        var lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertEvent(
                lowerId,
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                availableAt,
                null);
        insertEvent(
                higherId,
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                2,
                availableAt,
                null);

        assertEquals(
                lowerId,
                publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow().eventId());
        assertEquals(
                higherId,
                publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow().eventId());
    }

    @Test
    void should_skipLockedReadyEventAndClaimNextCandidate_when_consumersCompete() throws Exception {
        var availableAt = NOW.minusSeconds(1);
        var lowerId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        var higherId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        insertEvent(
                lowerId,
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                availableAt,
                null);
        insertEvent(
                higherId,
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                2,
                availableAt,
                null);

        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var lockFuture = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject(
                                "SELECT id FROM publishing_outbox WHERE id = ? FOR UPDATE",
                                UUID.class,
                                lowerId);
                        locked.countDown();
                        await(release);
                    }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            var claimed = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
            assertEquals(higherId, claimed.eventId());

            release.countDown();
            lockFuture.get(10, TimeUnit.SECONDS);
            assertEquals(
                    lowerId,
                    publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow().eventId());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void should_rejectInvalidOwnerAndLease_withoutChangingState() {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.MEDIA_ASSET,
                UUID.randomUUID(),
                1,
                NOW,
                null);

        assertThrows(
                IllegalArgumentException.class,
                () -> publicationStateService.claimNext(" ", NOW, LEASE));
        assertThrows(
                IllegalArgumentException.class,
                () -> publicationStateService.claimNext(" publisher", NOW, LEASE));
        assertThrows(
                IllegalArgumentException.class,
                () -> publicationStateService.claimNext("publisher\nother", NOW, LEASE));
        assertThrows(
                IllegalArgumentException.class,
                () -> publicationStateService.claimNext("x".repeat(129), NOW, LEASE));
        assertThrows(
                IllegalArgumentException.class,
                () -> publicationStateService.claimNext(OWNER, NOW, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> publicationStateService.claimNext(OWNER, NOW, Duration.ofSeconds(-1)));

        assertEquals(PublicationState.PENDING, status(eventId).state());
        assertEquals(0L, currentGeneration());
    }

    @Test
    void should_renewOnlyCurrentOwnerGenerationWithActiveLease_when_claimIsProcessing() {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                NOW,
                null);
        var claim = publicationStateService.claimNext(OWNER, NOW, Duration.ofMinutes(5)).orElseThrow();
        var renewalAt = NOW.plus(Duration.ofMinutes(1));

        assertFalse(publicationStateService.renewLease(
                eventId, claim.publishGeneration(), "other-owner", renewalAt, LEASE));
        assertFalse(publicationStateService.renewLease(
                eventId, claim.publishGeneration() + 1, OWNER, renewalAt, LEASE));
        assertTrue(publicationStateService.renewLease(
                eventId, claim.publishGeneration(), OWNER, renewalAt, LEASE));
        assertEquals(renewalAt.plus(LEASE), status(eventId).leaseUntil());
        assertTrue(publicationStateService
                .claimNext("other-owner", NOW.plus(Duration.ofMinutes(6)), LEASE)
                .isEmpty());
    }

    @Test
    void should_recoverExpiredLeaseWithSameGenerationAndIncrementedAttempt_when_newOwnerClaims() {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                NOW.minus(Duration.ofHours(1)),
                null);
        makeProcessing(
                eventId,
                1,
                1,
                "old-owner",
                NOW.minus(Duration.ofMinutes(10)),
                NOW.minus(Duration.ofMinutes(1)));

        var recovered = publicationStateService
                .claimNext("recovery-owner", NOW, LEASE)
                .orElseThrow();

        assertEquals(eventId, recovered.eventId());
        assertEquals(1L, recovered.publishGeneration());
        assertEquals(2, recovered.attemptCount());
        assertEquals("recovery-owner", recovered.claimOwner());
        assertEquals(PublicationResultCode.LEASE_EXPIRED, recovered.lastResultCode());
        assertEquals(1L, currentGeneration());
    }

    @Test
    void should_allowOnlyOneConcurrentRecovery_when_processingLeaseExpired() throws Exception {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                NOW.minus(Duration.ofHours(1)),
                null);
        makeProcessing(
                eventId,
                1,
                1,
                "old-owner",
                NOW.minus(Duration.ofMinutes(10)),
                NOW.minus(Duration.ofMinutes(1)));
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return publicationStateService.claimNext("recovery-a", NOW, LEASE);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return publicationStateService.claimNext("recovery-b", NOW, LEASE);
                    }));
            start.countDown();

            var recovered = new ArrayList<PublicationEventStatus>();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS).ifPresent(recovered::add);
            }

            assertEquals(1, recovered.size());
            assertEquals(2, status(eventId).attemptCount());
            assertEquals(1L, status(eventId).publishGeneration());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void should_failWithRetryExhausted_when_fourthProcessingLeaseExpires() {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                NOW.minus(Duration.ofHours(1)),
                null);
        makeProcessing(
                eventId,
                1,
                4,
                "old-owner",
                NOW.minus(Duration.ofMinutes(10)),
                NOW.minus(Duration.ofMinutes(1)));

        var exhausted = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();

        assertEquals(PublicationState.FAILED, exhausted.state());
        assertEquals(1L, exhausted.publishGeneration());
        assertEquals(4, exhausted.attemptCount());
        assertEquals(PublicationResultCode.RETRY_EXHAUSTED, exhausted.lastResultCode());
        assertNull(exhausted.claimOwner());
        assertTrue(publicationStateService.claimNext(OWNER, NOW, LEASE).isEmpty());
    }

    @Test
    void should_useOneFiveFifteenMinuteRetriesWithSameGeneration_when_failuresAreTransient() {
        var eventId = insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.NOTICE,
                UUID.randomUUID(),
                1,
                NOW,
                null);
        var initial = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        var generation = initial.publishGeneration();

        var firstFailureAt = NOW.plusSeconds(1);
        assertTrue(publicationStateService.recordTransientFailure(
                eventId, generation, OWNER, firstFailureAt));
        assertRetryWait(
                eventId, 1, firstFailureAt.plus(Duration.ofMinutes(1)), generation);
        assertTrue(publicationStateService
                .claimNext(
                        OWNER,
                        firstFailureAt.plus(Duration.ofMinutes(1)).minusNanos(1_000),
                        LEASE)
                .isEmpty());
        var second = publicationStateService
                .claimNext(OWNER, firstFailureAt.plus(Duration.ofMinutes(1)), LEASE)
                .orElseThrow();
        assertEquals(2, second.attemptCount());
        assertEquals(generation, second.publishGeneration());

        var secondFailureAt = second.claimedAt().plusSeconds(1);
        assertTrue(publicationStateService.recordTransientFailure(
                eventId, generation, OWNER, secondFailureAt));
        assertRetryWait(
                eventId, 2, secondFailureAt.plus(Duration.ofMinutes(5)), generation);
        var third = publicationStateService
                .claimNext(OWNER, secondFailureAt.plus(Duration.ofMinutes(5)), LEASE)
                .orElseThrow();
        assertEquals(3, third.attemptCount());

        var thirdFailureAt = third.claimedAt().plusSeconds(1);
        assertTrue(publicationStateService.recordTransientFailure(
                eventId, generation, OWNER, thirdFailureAt));
        assertRetryWait(
                eventId, 3, thirdFailureAt.plus(Duration.ofMinutes(15)), generation);
        var fourth = publicationStateService
                .claimNext(OWNER, thirdFailureAt.plus(Duration.ofMinutes(15)), LEASE)
                .orElseThrow();
        assertEquals(4, fourth.attemptCount());

        var fourthFailureAt = fourth.claimedAt().plusSeconds(1);
        assertTrue(publicationStateService.recordTransientFailure(
                eventId, generation, OWNER, fourthFailureAt));
        var failed = status(eventId);
        assertEquals(PublicationState.FAILED, failed.state());
        assertEquals(PublicationResultCode.RETRY_EXHAUSTED, failed.lastResultCode());
        assertEquals(4, failed.attemptCount());
        assertEquals(generation, failed.publishGeneration());
        assertEquals(1L, currentGeneration());
        assertEquals(1, eventCount());
    }

    @Test
    void should_allowOnlyActiveOwnerToWriteTerminalResult_and_neverReclaimTerminalRows() {
        var successId = insertImmediateEvent(1, NOW.minusSeconds(3));
        var noopId = insertImmediateEvent(2, NOW.minusSeconds(2));
        var failedId = insertImmediateEvent(3, NOW.minusSeconds(1));

        var successClaim = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        assertEquals(successId, successClaim.eventId());
        assertFalse(publicationStateService.completeSuccess(
                successId, successClaim.publishGeneration(), "wrong-owner", NOW.plusSeconds(1)));
        assertTrue(publicationStateService.completeSuccess(
                successId, successClaim.publishGeneration(), OWNER, NOW.plusSeconds(1)));

        var noopClaim = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        assertEquals(noopId, noopClaim.eventId());
        assertTrue(publicationStateService.completeNoop(
                noopId, noopClaim.publishGeneration(), OWNER, NOW.plusSeconds(1)));

        var failureClaim = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        assertEquals(failedId, failureClaim.eventId());
        assertTrue(publicationStateService.recordTerminalFailure(
                failedId, failureClaim.publishGeneration(), OWNER, NOW.plusSeconds(1)));

        assertEquals(PublicationResultCode.SUCCESS, status(successId).lastResultCode());
        assertEquals(PublicationResultCode.NO_PUBLIC_CHANGE, status(noopId).lastResultCode());
        assertEquals(PublicationResultCode.TERMINAL_FAILURE, status(failedId).lastResultCode());
        assertTrue(status(successId).state().isTerminal());
        assertTrue(status(noopId).state().isTerminal());
        assertTrue(status(failedId).state().isTerminal());
        assertTrue(publicationStateService
                .claimNext(OWNER, NOW.plus(Duration.ofDays(1)), LEASE)
                .isEmpty());
    }

    @Test
    void should_allocateDifferentMonotonicGenerations_when_dueEventsShareContentRevision() {
        var publishedAt = NOW.minus(Duration.ofMinutes(2));
        var expiresAt = NOW.minus(Duration.ofMinutes(1));
        var noticeId = insertNotice("published", publishedAt, expiresAt);
        var publishEvent = insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                noticeId,
                42,
                publishedAt,
                publishedAt);
        var expiryEvent = insertEvent(
                PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                PublicationSourceType.NOTICE,
                noticeId,
                42,
                expiresAt,
                expiresAt);

        var first = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        var second = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();

        assertEquals(publishEvent, first.eventId());
        assertEquals(1L, first.publishGeneration());
        assertEquals(expiryEvent, second.eventId());
        assertEquals(2L, second.publishGeneration());
        assertEquals(42L, first.contentRevision());
        assertEquals(42L, second.contentRevision());
    }

    @Test
    void should_coalesceOnlyOwnedLowerProcessingIntoExistingHigherProcessing_when_orderIsValid() {
        var lowId = insertImmediateEvent(1, NOW.minusSeconds(4));
        var highId = insertImmediateEvent(2, NOW.minusSeconds(3));
        var low = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        var high = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();

        assertEquals(lowId, low.eventId());
        assertEquals(highId, high.eventId());
        assertFalse(publicationStateService.coalesceInto(
                highId, high.publishGeneration(), low.publishGeneration(), OWNER, NOW.plusSeconds(1)));
        assertFalse(publicationStateService.coalesceInto(
                lowId, low.publishGeneration(), high.publishGeneration() + 1, OWNER, NOW.plusSeconds(1)));
        assertFalse(publicationStateService.coalesceInto(
                lowId, low.publishGeneration(), high.publishGeneration(), "other-owner", NOW.plusSeconds(1)));

        assertTrue(publicationStateService.coalesceInto(
                lowId, low.publishGeneration(), high.publishGeneration(), OWNER, NOW.plusSeconds(1)));
        var coalesced = status(lowId);
        assertEquals(PublicationState.COALESCED, coalesced.state());
        assertEquals(PublicationResultCode.COALESCED, coalesced.lastResultCode());
        assertEquals(high.publishGeneration(), coalesced.coalescedIntoGeneration());
        assertEquals(PublicationState.PROCESSING, status(highId).state());
        assertFalse(publicationStateService.coalesceInto(
                lowId, low.publishGeneration(), high.publishGeneration(), OWNER, NOW.plusSeconds(2)));
    }

    @Test
    void should_rejectCoalesceWhenHigherTargetIsTerminal() {
        var lowId = insertImmediateEvent(1, NOW.minusSeconds(2));
        var highId = insertImmediateEvent(2, NOW.minusSeconds(1));
        var low = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        var high = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        assertTrue(publicationStateService.completeSuccess(
                highId, high.publishGeneration(), OWNER, NOW.plusSeconds(1)));

        assertFalse(publicationStateService.coalesceInto(
                lowId, low.publishGeneration(), high.publishGeneration(), OWNER, NOW.plusSeconds(2)));
        assertEquals(PublicationState.PROCESSING, status(lowId).state());
        assertEquals(PublicationState.SUCCEEDED, status(highId).state());
    }

    private void assertStale(UUID eventId) {
        var result = publicationStateService.claimNext(OWNER, NOW, LEASE).orElseThrow();
        assertEquals(eventId, result.eventId());
        assertEquals(PublicationState.NOOP, result.state());
        assertNull(result.publishGeneration());
        assertEquals(0, result.attemptCount());
        assertEquals(PublicationResultCode.STALE_TRIGGER, result.lastResultCode());
    }

    private void assertRetryWait(
            UUID eventId, int attemptCount, Instant nextAttemptAt, long generation) {
        var status = status(eventId);
        assertEquals(PublicationState.RETRY_WAIT, status.state());
        assertEquals(attemptCount, status.attemptCount());
        assertEquals(nextAttemptAt, status.nextAttemptAt());
        assertEquals(generation, status.publishGeneration());
        assertEquals(PublicationResultCode.TRANSIENT_FAILURE, status.lastResultCode());
        assertNull(status.claimOwner());
        assertNull(status.leaseUntil());
    }

    private UUID insertImmediateEvent(long revision, Instant availableAt) {
        return insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                revision,
                availableAt,
                null);
    }

    private UUID insertEvent(
            PublicationEventKind kind,
            PublicationSourceType sourceType,
            UUID sourceId,
            long revision,
            Instant availableAt,
            Instant expectedBoundaryAt) {
        return insertEvent(
                UUID.randomUUID(),
                kind,
                sourceType,
                sourceId,
                revision,
                availableAt,
                expectedBoundaryAt);
    }

    private UUID insertEvent(
            UUID eventId,
            PublicationEventKind kind,
            PublicationSourceType sourceType,
            UUID sourceId,
            long revision,
            Instant availableAt,
            Instant expectedBoundaryAt) {
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision,
                    available_at, expected_boundary_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                kind.name(),
                sourceType.name(),
                sourceId,
                revision,
                offset(availableAt),
                expectedBoundaryAt == null ? null : offset(expectedBoundaryAt));
        return eventId;
    }

    private UUID insertNotice(String status, Instant publishedAt, Instant expiresAt) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notices (
                    id, status, title, slug, summary, body_markdown, pinned,
                    published_at, expires_at, created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, '게시 상태 테스트', ?, NULL, '게시 상태 테스트 본문', FALSE,
                          ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                "publication-state-" + id,
                publishedAt == null ? null : offset(publishedAt),
                expiresAt == null ? null : offset(expiresAt),
                adminId,
                adminId);
        return id;
    }

    private UUID insertGallery(String status, Instant publishedAt) {
        var breedId = insertBreed();
        var serviceId = insertService();
        var mediaId = insertMedia();
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO gallery_items (
                    id, status, dog_name, breed_id, primary_service_id, cover_image_id,
                    before_image_id, after_image_id, summary, alt_text, featured,
                    sort_order, performed_at, published_at,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, '라미', ?, ?, ?, NULL, NULL, '상태 테스트',
                          '라미의 미용 사진', FALSE, 100, NULL, ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                breedId,
                serviceId,
                mediaId,
                publishedAt == null ? null : offset(publishedAt),
                adminId,
                adminId);
        return id;
    }

    private UUID insertBreed() {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO breeds (
                    id, status, name, slug, description, sort_order,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, 'published', '테스트 견종', ?, '설명', 100,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                "publication-breed-" + id,
                adminId,
                adminId);
        return id;
    }

    private UUID insertService() {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO services (
                    id, status, name, slug, description, price_text, sort_order,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, 'published', '테스트 서비스', ?, '설명', '가격', 100,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                "publication-service-" + id,
                adminId,
                adminId);
        return id;
    }

    private UUID insertMedia() {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (
                    id, status, source_content_type, content_type, file_extension,
                    storage_key, source_byte_size, byte_size, width, height, sha256,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, 'active', 'image/jpeg', 'image/jpeg', 'jpg', ?,
                          100, 100, 4, 3, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                "masters/" + id.toString().substring(0, 2) + "/" + id + ".jpg",
                "a".repeat(64),
                adminId,
                adminId);
        return id;
    }

    private UUID insertAdmin() {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO admin_users (id, email, password_hash, role, active)
                VALUES (?, ?, 'test-password-hash', 'ADMIN', TRUE)
                """,
                id,
                "publication-state-" + id + "@example.com");
        return id;
    }

    private void makeProcessing(
            UUID eventId,
            long generation,
            int attemptCount,
            String owner,
            Instant claimedAt,
            Instant leaseUntil) {
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = ? WHERE singleton_key = 1",
                generation);
        jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'PROCESSING', publish_generation = ?, attempt_count = ?,
                    claim_owner = ?, claimed_at = ?, lease_until = ?
                WHERE id = ?
                """,
                generation,
                attemptCount,
                owner,
                offset(claimedAt),
                offset(leaseUntil),
                eventId);
    }

    private PublicationEventStatus status(UUID eventId) {
        return publicationStateService.findStatus(eventId).orElseThrow();
    }

    private long currentGeneration() {
        return jdbcTemplate.queryForObject(
                "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1",
                Long.class);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox", Integer.class);
    }

    private OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for publication lock test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publication lock test interrupted", exception);
        }
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update("DELETE FROM gallery_items");
        jdbcTemplate.update("DELETE FROM notices");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        jdbcTemplate.update("DELETE FROM admin_users");
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = 0 WHERE singleton_key = 1");
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
    }
}
