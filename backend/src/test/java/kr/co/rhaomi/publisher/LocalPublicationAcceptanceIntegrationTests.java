package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import kr.co.rhaomi.backend.BackendApplication;
import kr.co.rhaomi.backend.media.MediaTestFixtures;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import kr.co.rhaomi.backend.publication.PublicationResultCode;
import kr.co.rhaomi.backend.publication.PublicationState;
import kr.co.rhaomi.backend.publication.PublicationStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = BackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=8080")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RHAOMI_LOCAL_PUBLICATION_ACCEPTANCE", matches = "true")
class LocalPublicationAcceptanceIntegrationTests {

    private static final String BUILD_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ADMIN_EMAIL = "publication.acceptance@example.invalid";
    private static final String PUBLIC_SITE_URL = "https://acceptance.rhaomi.invalid/";
    private static final String IMAGE_DIGEST = "sha256:" + "b".repeat(64);
    private static final String MARKER_FILE = ".rhaomi-publication-acceptance";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PublicationStateService stateService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Clock buildClock;

    private MutableClock clock;
    private Path acceptanceRoot;
    private Path sourceRoot;
    private Path nodeExecutable;
    private Path releaseRoot;
    private Path currentLink;
    private Path previousLink;
    private Path lockFile;
    private Instant baseTime;

    @BeforeEach
    void setUp() throws Exception {
        acceptanceRoot = requiredAcceptanceRoot();
        sourceRoot = requiredPath("RHAOMI_PUBLICATION_ACCEPTANCE_SOURCE_ROOT");
        nodeExecutable = requiredPath("RHAOMI_PUBLICATION_ACCEPTANCE_NODE");
        releaseRoot = acceptanceRoot.resolve("public/releases");
        currentLink = acceptanceRoot.resolve("public/current");
        previousLink = acceptanceRoot.resolve("public/previous");
        lockFile = acceptanceRoot.resolve("state/locks/publisher.lock");
        baseTime = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);
        clock = new MutableClock(baseTime);
        when(buildClock.instant()).thenAnswer(ignored -> clock.instant());
        when(buildClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(buildClock.withZone(ZoneOffset.UTC)).thenReturn(buildClock);

        clearContentFixtures();
        Files.createDirectories(requiredMediaRoot());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_users WHERE email = ?",
                        Integer.class,
                        ADMIN_EMAIL));
    }

    @AfterEach
    void tearDown() {
        clearContentFixtures();
    }

    @Test
    void should_acceptActualAdminMutationThroughScheduledReleaseAndIndependentServing()
            throws Exception {
        var session = loginAndRefreshCsrf();
        var fixtures = createSyntheticContent(session);

        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNextAt(baseTime));

        var firstCurrentTarget = Files.readSymbolicLink(currentLink);
        var firstManifest = releaseManifest(currentLink);
        assertFalse(Files.exists(previousLink, LinkOption.NOFOLLOW_LINKS));
        assertEquals(baseTime.plusSeconds(30).toString(), firstManifest.get("generatedAt").asText());
        assertEquals(Long.toString(currentRevision()), firstManifest.get("contentRevision").asText());
        assertEquals(Long.toString(currentGeneration()), firstManifest.get("publishGeneration").asText());

        var initialHome = homeHtml();
        assertHomeContainsInitialPublicDataset(initialHome);
        assertNoticeVisibility("acceptance-current-notice", true);
        assertNoticeVisibility("acceptance-scheduled-notice", false);
        assertNoticeVisibility("acceptance-rescheduled-notice", false);
        assertNoticeVisibility("acceptance-overdue-notice", false);
        assertNoticeVisibility("acceptance-close-publish", false);
        assertNoticeVisibility("acceptance-close-expiry", true);

        var draftRevisionBefore = currentRevision();
        var draftGenerationBefore = currentGeneration();
        var draftEventCountBefore = outboxCount();
        var draftTargetBefore = Files.readSymbolicLink(currentLink);
        assertStatus(
                putJson(
                        session,
                        "/api/admin/gallery-items/" + fixtures.draftGalleryId(),
                        galleryUpdate(
                                "draft",
                                "합성 임시 갤러리 수정",
                                fixtures.secondBreedId(),
                                fixtures.secondServiceId(),
                                fixtures.pngMediaId(),
                                null,
                                null,
                                "공개되지 않는 임시 갤러리",
                                "합성 임시 갤러리 사진",
                                false,
                                91,
                                null,
                                null)),
                200);
        assertEquals(draftRevisionBefore + 1, currentRevision());
        assertEquals(draftGenerationBefore, currentGeneration());
        assertEquals(draftEventCountBefore, outboxCount());
        assertEquals(draftTargetBefore, Files.readSymbolicLink(currentLink));

        clock.set(baseTime.plusSeconds(31));
        var publicUpdateRevisionBefore = currentRevision();
        var publicUpdateGenerationBefore = currentGeneration();
        assertStatus(
                putJson(
                        session,
                        "/api/admin/services/" + fixtures.secondServiceId(),
                        serviceUpdate(
                                "published",
                                "합성 부분 미용",
                                "합성 부분 미용 설명",
                                "합성 35,000원부터",
                                20)),
                200);
        assertEquals(publicUpdateRevisionBefore + 1, currentRevision());
        assertEquals(publicUpdateGenerationBefore, currentGeneration());
        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNextAt(baseTime.plusSeconds(31)));
        var publicUpdateTarget = Files.readSymbolicLink(currentLink);
        assertNotEquals(firstCurrentTarget, publicUpdateTarget);
        assertEquals(firstCurrentTarget, Files.readSymbolicLink(previousLink));
        assertEquals(publicUpdateGenerationBefore + 1, currentGeneration());
        assertTrue(homeHtml().contains("합성 35,000원부터"));
        assertFalse(homeHtml().contains("합성 30,000원부터"));

        clock.set(baseTime.plusSeconds(62));
        assertStatus(
                putJson(
                        session,
                        "/api/admin/gallery-items/" + fixtures.secondGalleryId(),
                        galleryUpdate(
                                "archived",
                                "합성 두부",
                                fixtures.secondBreedId(),
                                fixtures.secondServiceId(),
                                fixtures.pngMediaId(),
                                fixtures.largeMediaId(),
                                fixtures.heicMediaId(),
                                "아카이브되는 공개 갤러리",
                                "합성 두부의 미용 완료 사진",
                                false,
                                20,
                                baseTime.minusSeconds(3_600),
                                baseTime.minusSeconds(60))),
                200);
        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNextAt(baseTime.plusSeconds(62)));
        var archivedHome = homeHtml();
        assertFalse(archivedHome.contains("합성 두부"));
        assertFalse(archivedHome.contains("합성 두부의 미용 완료 사진"));
        assertTrue(archivedHome.contains("합성 라미"));
        assertTrue(archivedHome.contains("합성 전체 미용"));
        assertTrue(archivedHome.contains("합성 상시 공지"));
        assertEquals(publicUpdateTarget, Files.readSymbolicLink(previousLink));
        assertTrue(currentGeneration() > Long.parseLong(firstManifest.get("publishGeneration").asText()));

        var staleGenerationBefore = currentGeneration();
        var staleTargetBefore = Files.readSymbolicLink(currentLink);
        assertEquals(
                PublisherControlLoop.CycleOutcome.STALE_TRIGGER,
                runNextAt(baseTime.plusSeconds(100)));
        assertEquals(staleGenerationBefore, currentGeneration());
        assertEquals(staleTargetBefore, Files.readSymbolicLink(currentLink));
        assertEvent(
                fixtures.stalePublishedAtEventId(),
                PublicationState.NOOP,
                PublicationResultCode.STALE_TRIGGER);
        assertNoticeVisibility("acceptance-rescheduled-notice", false);

        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNextAt(baseTime.plusSeconds(120)));
        var scheduledHome = homeHtml();
        assertTrue(scheduledHome.contains("합성 예약 공개 공지"));
        assertTrue(scheduledHome.contains(
                "dateTime=\"" + baseTime.plusSeconds(120) + "\""));
        assertNoticeVisibility("acceptance-scheduled-notice", true);

        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNextAt(baseTime.plusSeconds(180)));
        assertFalse(homeHtml().contains("합성 예약 공개 공지"));
        assertNoticeVisibility("acceptance-scheduled-notice", false);

        var overdueGenerationBefore = currentGeneration();
        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNextAt(baseTime.plusSeconds(300)));
        assertEquals(overdueGenerationBefore + 2, currentGeneration());
        var overdueHome = homeHtml();
        assertTrue(overdueHome.contains("합성 재예약 공지"));
        assertTrue(overdueHome.contains("합성 중단 복구 공지"));
        assertNoticeVisibility("acceptance-rescheduled-notice", true);
        assertNoticeVisibility("acceptance-overdue-notice", true);
        assertEvent(
                fixtures.rescheduledPublishedAtEventId(),
                PublicationState.COALESCED,
                PublicationResultCode.COALESCED);
        assertEvent(
                fixtures.overduePublishedAtEventId(),
                PublicationState.SUCCEEDED,
                PublicationResultCode.SUCCESS);

        var closeGenerationBefore = currentGeneration();
        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNextAt(baseTime.plusSeconds(600)));
        assertEquals(closeGenerationBefore + 2, currentGeneration());
        assertEvent(
                fixtures.closePublishedAtEventId(),
                PublicationState.COALESCED,
                PublicationResultCode.COALESCED);
        assertEvent(
                fixtures.closeExpiresAtEventId(),
                PublicationState.SUCCEEDED,
                PublicationResultCode.SUCCESS);

        var finalHome = homeHtml();
        assertTrue(finalHome.contains("합성 근접 경계 공개 공지"));
        assertFalse(finalHome.contains("합성 근접 경계 만료 공지"));
        assertTrue(finalHome.contains("합성 재예약 공지"));
        assertTrue(finalHome.contains("합성 중단 복구 공지"));
        assertNoticeVisibility("acceptance-close-publish", true);
        assertNoticeVisibility("acceptance-close-expiry", false);
        assertFinalPublicContract(finalHome);
        writeServingContract();
    }

    private Fixtures createSyntheticContent(Session session) throws Exception {
        var largeMedia = upload(
                session,
                new Part("file", "synthetic-large-hero.jpg", "image/jpeg", largeJpeg()));
        var pngMedia = upload(
                session,
                new Part(
                        "file",
                        "synthetic-groomer.png",
                        "image/png",
                        MediaTestFixtures.resource("synthetic-source.png")));
        var secondPngMedia = upload(
                session,
                new Part(
                        "file",
                        "synthetic-after.png",
                        "image/png",
                        MediaTestFixtures.resource("synthetic-source-2.png")));
        var heicMedia = upload(
                session,
                new Part(
                        "file",
                        "synthetic-iphone.heic",
                        "image/heic",
                        MediaTestFixtures.resource("synthetic-orientation-metadata.heic")));
        assertStatus(largeMedia, 201);
        assertStatus(pngMedia, 201);
        assertStatus(secondPngMedia, 201);
        assertStatus(heicMedia, 201);
        var largeMediaId = id(largeMedia);
        var pngMediaId = id(pngMedia);
        var secondPngMediaId = id(secondPngMedia);
        var heicMediaId = id(heicMedia);
        assertEquals("image/heic", json(heicMedia).get("sourceContentType").asText());
        assertEquals("image/jpeg", json(heicMedia).get("contentType").asText());

        var firstBreed = createBreed(session, "합성 비숑", "acceptance-bichon", 10);
        publishBreed(session, firstBreed, "합성 비숑", "합성 비숑 설명", 10);
        var secondBreed = createBreed(session, "합성 푸들", "acceptance-poodle", 20);
        publishBreed(session, secondBreed, "합성 푸들", "합성 푸들 설명", 20);
        var draftBreed = createBreed(session, "합성 임시 견종", "acceptance-draft-breed", 90);
        var archivedBreed = createBreed(
                session, "합성 보관 견종", "acceptance-archived-breed", 100);
        assertStatus(
                putJson(
                        session,
                        "/api/admin/breeds/" + archivedBreed,
                        breedUpdate("archived", "합성 보관 견종", "보관 설명", 100)),
                200);

        var firstService = createService(
                session, "합성 전체 미용", "acceptance-full-grooming", 10);
        publishService(
                session,
                firstService,
                "합성 전체 미용",
                "합성 전체 미용 설명",
                "합성 상담 후 안내",
                10);
        var secondService = createService(
                session, "합성 부분 미용", "acceptance-partial-grooming", 20);
        publishService(
                session,
                secondService,
                "합성 부분 미용",
                "합성 부분 미용 설명",
                "합성 30,000원부터",
                20);
        createService(session, "합성 임시 서비스", "acceptance-draft-service", 90);
        var archivedService = createService(
                session, "합성 보관 서비스", "acceptance-archived-service", 100);
        assertStatus(
                putJson(
                        session,
                        "/api/admin/services/" + archivedService,
                        serviceUpdate(
                                "archived",
                                "합성 보관 서비스",
                                "보관 서비스 설명",
                                "합성 가격",
                                100)),
                200);

        assertStatus(
                putJson(
                        session,
                        "/api/admin/shop-settings",
                        shopSettings(largeMediaId, pngMediaId, largeMediaId)),
                201);

        var firstGallery = createGallery(
                session,
                "합성 라미",
                firstBreed,
                firstService,
                largeMediaId,
                pngMediaId,
                heicMediaId,
                10);
        publishGallery(
                session,
                firstGallery,
                "합성 라미",
                firstBreed,
                firstService,
                largeMediaId,
                pngMediaId,
                heicMediaId,
                "합성 라미의 before-after 사례",
                "합성 라미의 미용 완료 사진",
                true,
                10,
                baseTime.minusSeconds(7_200),
                baseTime.minusSeconds(120));
        var secondGallery = createGallery(
                session,
                "합성 두부",
                secondBreed,
                secondService,
                pngMediaId,
                largeMediaId,
                heicMediaId,
                20);
        publishGallery(
                session,
                secondGallery,
                "합성 두부",
                secondBreed,
                secondService,
                pngMediaId,
                largeMediaId,
                heicMediaId,
                "합성 두부의 before-after 사례",
                "합성 두부의 미용 완료 사진",
                false,
                20,
                baseTime.minusSeconds(3_600),
                baseTime.minusSeconds(60));
        var draftGallery = createGallery(
                session,
                "합성 임시 갤러리",
                firstBreed,
                firstService,
                secondPngMediaId,
                null,
                null,
                90);
        var archivedGallery = createGallery(
                session,
                "합성 보관 갤러리",
                firstBreed,
                firstService,
                secondPngMediaId,
                null,
                null,
                100);
        assertStatus(
                putJson(
                        session,
                        "/api/admin/gallery-items/" + archivedGallery,
                        galleryUpdate(
                                "archived",
                                "합성 보관 갤러리",
                                firstBreed,
                                firstService,
                                secondPngMediaId,
                                null,
                                null,
                                "보관 갤러리",
                                "합성 보관 갤러리 사진",
                                false,
                                100,
                                null,
                                null)),
                200);

        var currentNotice = createNotice(session, "합성 상시 공지", "acceptance-current-notice");
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + currentNotice,
                        noticeUpdate(
                                "published",
                                "합성 상시 공지",
                                "합성 상시 공지 요약",
                                "## 합성 공지 안내\n\n- 합성 목록 항목\n- 두 번째 항목\n\n`안전한 코드`\n\n**안전한 Markdown**과 [예약 링크](/#location)\n\n<script>alert(1)</script>\n\n![원격](https://private.example.invalid/image.jpg)",
                                true,
                                kst(baseTime.minusSeconds(300)),
                                null)),
                200);

        var scheduledNotice = createNotice(
                session, "합성 예약 공개 공지", "acceptance-scheduled-notice");
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + scheduledNotice,
                        noticeUpdate(
                                "published",
                                "합성 예약 공개 공지",
                                "추가 mutation 없이 공개되고 만료됩니다.",
                                "예약 공개 본문",
                                false,
                                kst(baseTime.plusSeconds(120)),
                                kst(baseTime.plusSeconds(180)))),
                200);

        var rescheduledNotice = createNotice(
                session, "합성 재예약 공지", "acceptance-rescheduled-notice");
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + rescheduledNotice,
                        noticeUpdate(
                                "published",
                                "합성 재예약 공지",
                                "과거 예약 event는 stale입니다.",
                                "재예약 본문",
                                false,
                                utc(baseTime.plusSeconds(100)),
                                null)),
                200);
        var stalePublishedAtEvent = scheduledEvent(
                rescheduledNotice,
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                baseTime.plusSeconds(100));
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + rescheduledNotice,
                        noticeUpdate(
                                "published",
                                "합성 재예약 공지",
                                "과거 예약 event는 stale입니다.",
                                "재예약 본문",
                                false,
                                utc(baseTime.plusSeconds(240)),
                                null)),
                200);
        var rescheduledPublishedAtEvent = scheduledEvent(
                rescheduledNotice,
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                baseTime.plusSeconds(240));

        var overdueNotice = createNotice(
                session, "합성 중단 복구 공지", "acceptance-overdue-notice");
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + overdueNotice,
                        noticeUpdate(
                                "published",
                                "합성 중단 복구 공지",
                                "publisher 중단 경계를 지나 재시작됩니다.",
                                "중단 복구 본문",
                                false,
                                utc(baseTime.plusSeconds(250)),
                                null)),
                200);
        var overduePublishedAtEvent = scheduledEvent(
                overdueNotice,
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                baseTime.plusSeconds(250));

        var closeFutureNotice = createNotice(
                session, "합성 근접 경계 공개 공지", "acceptance-close-publish");
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + closeFutureNotice,
                        noticeUpdate(
                                "published",
                                "합성 근접 경계 공개 공지",
                                "고정 debounce 공개 경계",
                                "근접 공개 본문",
                                false,
                                utc(baseTime.plusSeconds(600)),
                                null)),
                200);
        var closePublishedAtEvent = scheduledEvent(
                closeFutureNotice,
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                baseTime.plusSeconds(600));

        var closeExpiryNotice = createNotice(
                session, "합성 근접 경계 만료 공지", "acceptance-close-expiry");
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + closeExpiryNotice,
                        noticeUpdate(
                                "published",
                                "합성 근접 경계 만료 공지",
                                "고정 debounce 만료 경계",
                                "근접 만료 본문",
                                true,
                                utc(baseTime.minusSeconds(10)),
                                utc(baseTime.plusSeconds(610)))),
                200);
        var closeExpiresAtEvent = scheduledEvent(
                closeExpiryNotice,
                PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                baseTime.plusSeconds(610));

        createNotice(session, "합성 임시 공지", "acceptance-draft-notice");
        var archivedNotice = createNotice(
                session, "합성 보관 공지", "acceptance-archived-notice");
        assertStatus(
                putJson(
                        session,
                        "/api/admin/notices/" + archivedNotice,
                        noticeUpdate(
                                "archived",
                                "합성 보관 공지",
                                "보관 공지 요약",
                                "보관 공지 본문",
                                false,
                                null,
                                null)),
                200);

        return new Fixtures(
                largeMediaId,
                pngMediaId,
                heicMediaId,
                secondBreed,
                secondService,
                draftGallery,
                secondGallery,
                stalePublishedAtEvent,
                rescheduledPublishedAtEvent,
                overduePublishedAtEvent,
                closePublishedAtEvent,
                closeExpiresAtEvent);
    }

    private void assertHomeContainsInitialPublicDataset(String home) {
        for (var expected : List.of(
                "합성 라오미펫",
                "합성 전체 미용",
                "합성 부분 미용",
                "합성 라미",
                "합성 두부",
                "합성 상시 공지",
                "합성 근접 경계 만료 공지",
                "합성 Hero 이미지",
                "합성 미용사 프로필")) {
            assertTrue(home.contains(expected), expected);
        }
        for (var forbidden : List.of(
                "합성 임시 견종",
                "합성 보관 견종",
                "합성 임시 서비스",
                "합성 보관 서비스",
                "합성 임시 갤러리",
                "합성 보관 갤러리",
                "합성 예약 공개 공지",
                "합성 재예약 공지",
                "합성 중단 복구 공지",
                "합성 근접 경계 공개 공지",
                "합성 임시 공지",
                "합성 보관 공지")) {
            assertFalse(home.contains(forbidden), forbidden);
        }
        assertTrue(home.contains("href=\"tel:0212345678\""));
        assertTrue(home.contains("href=\"https://map.example/naver-acceptance\""));
        assertTrue(home.contains("href=\"https://channel.example/kakao-acceptance\""));
        assertFalse(home.contains(">인스타그램<"));
        assertFalse(home.contains(">네이버 톡톡<"));
        assertTrue(home.contains("dateTime=\"" + baseTime.minusSeconds(300) + "\""));
    }

    private void assertFinalPublicContract(String home) throws Exception {
        var site = resolveSite(currentLink);
        var notice = Files.readString(
                site.resolve("notices/acceptance-current-notice/index.html"));
        assertTrue(notice.contains("<h1>합성 상시 공지</h1>"));
        assertTrue(notice.contains("<h2>합성 공지 안내</h2>"));
        assertTrue(notice.contains("<li>합성 목록 항목</li>"));
        assertTrue(notice.contains("<code>안전한 코드</code>"));
        assertTrue(notice.contains("<strong>안전한 Markdown</strong>"));
        assertTrue(notice.contains("href=\"/#location\""));
        assertFalse(notice.contains("<script>alert(1)</script>"));
        assertFalse(notice.contains("https://private.example.invalid/image.jpg"));
        assertTrue(notice.contains("원격"));

        assertTrue(home.contains("<html lang=\"ko\""));
        assertTrue(home.contains("<main>"));
        assertTrue(home.contains("<h1"));
        assertTrue(home.contains("<h2"));
        assertTrue(home.contains("aria-labelledby="));
        assertTrue(home.contains("<picture>"));
        assertTrue(home.contains("image/avif"));
        assertTrue(home.contains("image/webp"));
        assertTrue(home.contains(".jpeg"));
        assertTrue(home.contains("alt=\"합성 Hero 이미지\""));
        assertTrue(home.contains("alt=\"합성 미용사 프로필\""));
        assertTrue(home.contains("alt=\"합성 라미의 미용 완료 사진\""));
        assertFalse(home.contains("BUILD_API_CREDENTIAL"));
        assertFalse(home.contains("RHAOMI_BUILD_SERVICE_TOKEN"));
        assertFalse(home.contains("jdbc:postgresql:"));
        assertFalse(home.contains(acceptanceRoot.resolve("media").toString()));
        assertFalse(home.contains(acceptanceRoot.resolve("state").toString()));
        assertFalse(home.contains(releaseRoot.toString()));
        assertFalse(home.contains(BUILD_TOKEN));

        var sitemap = Files.readString(site.resolve("sitemap.xml"));
        var robots = Files.readString(site.resolve("robots.txt"));
        assertTrue(sitemap.contains(PUBLIC_SITE_URL));
        assertTrue(sitemap.contains("/notices/acceptance-current-notice/"));
        assertFalse(sitemap.contains("/admin/"));
        assertTrue(robots.contains("Sitemap: " + PUBLIC_SITE_URL + "sitemap.xml"));

        var generatedMedia = site.resolve("generated/media");
        var extensions = new ArrayList<String>();
        var jpegWidths = new ArrayList<Integer>();
        try (var files = Files.list(generatedMedia)) {
            for (var path : files.filter(Files::isRegularFile).toList()) {
                var filename = path.getFileName().toString();
                var separator = filename.lastIndexOf('.');
                assertTrue(separator > 0, filename);
                var hash = filename.substring(0, separator);
                assertEquals(hash, sha256(Files.readAllBytes(path)), filename);
                assertTrue(Files.size(path) > 0, filename);
                extensions.add(filename.substring(separator + 1));
                if (filename.endsWith(".jpeg")) {
                    var image = ImageIO.read(path.toFile());
                    assertNotNull(image, filename);
                    assertTrue(image.getWidth() <= 2_000, filename);
                    jpegWidths.add(image.getWidth());
                }
            }
        }
        assertTrue(extensions.containsAll(Set.of("avif", "webp", "jpeg")));
        assertFalse(extensions.contains("png"));
        assertFalse(extensions.contains("heic"));
        assertFalse(extensions.contains("jpg"));
        assertTrue(jpegWidths.containsAll(List.of(360, 640, 768, 960, 1_200, 1_280, 1_600, 1_920)));
        assertTrue(jpegWidths.stream().anyMatch(width -> width <= 64));

        var manifest = releaseManifest(currentLink);
        assertEquals(1, manifest.get("schemaVersion").asInt());
        assertEquals(Long.toString(currentRevision()), manifest.get("contentRevision").asText());
        assertEquals(Long.toString(currentGeneration()), manifest.get("publishGeneration").asText());
        assertEquals(baseTime.plusSeconds(630).toString(), manifest.get("generatedAt").asText());
        assertEquals(requiredEnvironment("RHAOMI_ACCEPTANCE_GIT_HEAD"), manifest.get("codeSha").asText());
        assertTrue(Files.exists(previousLink, LinkOption.NOFOLLOW_LINKS));
        assertNotEquals(Files.readSymbolicLink(currentLink), Files.readSymbolicLink(previousLink));
        assertFalse(Files.exists(site.resolve("release-manifest.json")));
        assertPrivateMediaValuesAbsentFromPublicHtml(site);
    }

    private void assertNoticeVisibility(String slug, boolean expected) throws Exception {
        var site = resolveSite(currentLink);
        var routeExists = Files.isRegularFile(site.resolve("notices/" + slug + "/index.html"));
        var sitemapContains = Files.readString(site.resolve("sitemap.xml"))
                .contains(PUBLIC_SITE_URL + "notices/" + slug + "/");
        assertEquals(expected, routeExists, slug);
        assertEquals(expected, sitemapContains, slug);
    }

    private void assertPrivateMediaValuesAbsentFromPublicHtml(Path site) throws Exception {
        var html = new StringBuilder();
        try (var files = Files.walk(site)) {
            for (var path : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .toList()) {
                html.append(Files.readString(path));
            }
        }
        var publicHtml = html.toString();
        var privateValues = jdbcTemplate.query(
                "SELECT id::text, storage_key FROM media_assets",
                (resultSet, rowNumber) -> List.of(
                        resultSet.getString(1), resultSet.getString(2)));
        for (var values : privateValues) {
            for (var value : values) {
                assertFalse(publicHtml.contains(value), "private media value leaked");
            }
        }
        for (var value : List.of(
                BUILD_TOKEN,
                acceptanceRoot.resolve("media").toString(),
                acceptanceRoot.resolve("state").toString(),
                releaseRoot.toString())) {
            assertFalse(publicHtml.contains(value), "private runtime value leaked");
        }
        for (var filename : List.of(
                "synthetic-large-hero.jpg",
                "synthetic-groomer.png",
                "synthetic-after.png",
                "synthetic-iphone.heic")) {
            assertFalse(publicHtml.contains(filename), "source filename leaked");
        }
    }

    private void writeServingContract() throws Exception {
        var site = resolveSite(currentLink);
        String mediaPath;
        try (var files = Files.list(site.resolve("generated/media"))) {
            mediaPath = files.filter(Files::isRegularFile)
                    .map(path -> "/generated/media/" + path.getFileName())
                    .sorted()
                    .findFirst()
                    .orElseThrow();
        }
        var contract = object(
                "schemaVersion", 1,
                "homeText", List.of(
                        "합성 라오미펫",
                        "합성 상시 공지",
                        "합성 재예약 공지",
                        "합성 중단 복구 공지",
                        "합성 근접 경계 공개 공지"),
                "absentHomeText", List.of(
                        "합성 두부",
                        "합성 예약 공개 공지",
                        "합성 근접 경계 만료 공지"),
                "noticePath", "/notices/acceptance-current-notice/",
                "noticeText", "안전한 Markdown",
                "noticeTitle", "합성 상시 공지",
                "noticePublishedAt", baseTime.minusSeconds(300).toString(),
                "mediaPath", mediaPath,
                "expectedGeneration", Long.toString(currentGeneration()),
                "publicSiteUrl", PUBLIC_SITE_URL,
                "shopName", "합성 라오미펫",
                "homeTitle", "합성 라오미펫 | 서울 테스트구 반려견 미용",
                "homeDescription", "실제 운영 데이터가 아닌 수용 테스트 설명입니다.",
                "phone", "02-1234-5678",
                "address", "서울 테스트구 합성로 45",
                "sameAs", List.of(
                        "https://blog.example/rhaomi-acceptance",
                        "https://channel.example/kakao-acceptance"));
        Files.writeString(
                acceptanceRoot.resolve("serving-contract.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(contract) + "\n",
                StandardCharsets.UTF_8);
    }

    private PublisherControlLoop.CycleOutcome runNextAt(Instant start) throws Exception {
        clock.set(start);
        var buildThreads = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("local-publication-acceptance-", 0).factory());
        try {
            var settings = new PublisherSettings(
                    "local-publication-acceptance",
                    Duration.ofMillis(100),
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(5),
                    lockFile);
            var loop = new PublisherControlLoop(
                    new PublicationStateServiceAdapter(stateService),
                    new AsyncPublicationBuildTaskFactory(executor(), buildThreads),
                    new FileSystemPublicationExecutionLock(lockFile),
                    clock,
                    (duration, stopSignal) -> {
                        clock.advance(PublisherSettings.DEBOUNCE_WINDOW);
                        return !stopSignal.isRequested();
                    },
                    settings,
                    new PublisherStopSignal());
            return loop.runNext();
        } finally {
            buildThreads.shutdownNow();
            assertTrue(buildThreads.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private NodePublicationBuildExecutor executor() {
        var codeSha = requiredEnvironment("RHAOMI_ACCEPTANCE_GIT_HEAD");
        assertTrue(codeSha.matches("[0-9a-f]{40}"));
        var environment = new LinkedHashMap<String, String>();
        environment.put("BUILD_API_INTERNAL_URL", backendBaseUrl());
        environment.put("BUILD_API_CREDENTIAL", BUILD_TOKEN);
        environment.put("RHAOMI_PUBLISHER_SOURCE_ROOT", sourceRoot.toString());
        environment.put("RHAOMI_PUBLISHER_WORK_ROOT", acceptanceRoot.resolve("state/publisher").toString());
        environment.put("RHAOMI_PUBLIC_RELEASE_ROOT", releaseRoot.toString());
        environment.put("RHAOMI_PUBLIC_CURRENT_LINK", currentLink.toString());
        environment.put("RHAOMI_PUBLIC_PREVIOUS_LINK", previousLink.toString());
        environment.put("PUBLIC_SITE_URL", PUBLIC_SITE_URL);
        environment.put("RHAOMI_CODE_SHA", codeSha);
        environment.put("RHAOMI_CODE_IMAGE_TAG", "sha-" + codeSha);
        environment.put("RHAOMI_CODE_IMAGE_DIGEST", IMAGE_DIGEST);
        environment.put("RHAOMI_FLYWAY_VERSION", "9");
        environment.put("RHAOMI_SBOM_REFERENCE", IMAGE_DIGEST);
        environment.put("RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS", "180000");
        environment.put("RHAOMI_RELEASE_RETENTION", "10");
        environment.put("PATH", requiredEnvironment("PATH"));
        return new NodePublicationBuildExecutor(new PublicationExecutorSettings(
                nodeExecutable,
                sourceRoot.resolve("scripts/publish-static-release.mts"),
                Duration.ofMillis(250),
                environment));
    }

    private Session loginAndRefreshCsrf() throws Exception {
        var client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        var preLoginCsrf = csrf(client);
        var login = request(
                client,
                "POST",
                "/api/admin/auth/login",
                body(
                        "email",
                        ADMIN_EMAIL,
                        "password",
                        requiredEnvironment("RHAOMI_BOOTSTRAP_ADMIN_PASSWORD")),
                preLoginCsrf);
        assertStatus(login, 200);
        assertEquals(ADMIN_EMAIL, json(login).get("email").asText());

        var freshCsrf = csrf(client);
        assertNotEquals(preLoginCsrf, freshCsrf);
        var me = request(client, "GET", "/api/admin/auth/me", null, null);
        assertStatus(me, 200);
        assertEquals(ADMIN_EMAIL, json(me).get("email").asText());
        assertFalse(me.body().toLowerCase().contains("password"));
        return new Session(client, freshCsrf);
    }

    private String csrf(HttpClient client) throws Exception {
        var response = request(client, "GET", "/api/admin/auth/csrf", null, null);
        assertStatus(response, 200);
        var token = json(response).get("token").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private HttpResponse<String> upload(Session session, Part... parts) throws Exception {
        var boundary = "RhaomiAcceptance" + UUID.randomUUID().toString().replace("-", "");
        var multipart = new ByteArrayOutputStream();
        for (var part : parts) {
            multipart.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            multipart.write(("Content-Disposition: form-data; name=\"" + part.name()
                            + "\"; filename=\"" + part.filename() + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            multipart.write(("Content-Type: " + part.contentType() + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            multipart.write(part.bytes());
            multipart.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        multipart.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        var request = HttpRequest.newBuilder(uri("/api/admin/media"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CSRF-TOKEN", session.csrfToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                .build();
        return session.client().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> putJson(Session session, String path, String body)
            throws Exception {
        return request(session.client(), "PUT", path, body, session.csrfToken());
    }

    private HttpResponse<String> postJson(Session session, String path, String body)
            throws Exception {
        return request(session.client(), "POST", path, body, session.csrfToken());
    }

    private HttpResponse<String> request(
            HttpClient client, String method, String path, String body, String csrfToken)
            throws Exception {
        var publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        var builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(60))
                .method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private UUID createBreed(Session session, String name, String slug, int sortOrder)
            throws Exception {
        var response = postJson(session, "/api/admin/breeds", breedCreate(name, slug, sortOrder));
        assertStatus(response, 201);
        return id(response);
    }

    private void publishBreed(
            Session session, UUID id, String name, String description, int sortOrder)
            throws Exception {
        assertStatus(
                putJson(
                        session,
                        "/api/admin/breeds/" + id,
                        breedUpdate("published", name, description, sortOrder)),
                200);
    }

    private UUID createService(Session session, String name, String slug, int sortOrder)
            throws Exception {
        var response = postJson(
                session,
                "/api/admin/services",
                body(
                        "name", name,
                        "slug", slug,
                        "description", null,
                        "priceText", null,
                        "sortOrder", sortOrder));
        assertStatus(response, 201);
        return id(response);
    }

    private void publishService(
            Session session,
            UUID id,
            String name,
            String description,
            String priceText,
            int sortOrder)
            throws Exception {
        assertStatus(
                putJson(
                        session,
                        "/api/admin/services/" + id,
                        serviceUpdate(
                                "published", name, description, priceText, sortOrder)),
                200);
    }

    private UUID createGallery(
            Session session,
            String dogName,
            UUID breedId,
            UUID serviceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            int sortOrder)
            throws Exception {
        var response = postJson(
                session,
                "/api/admin/gallery-items",
                body(
                        "dogName", dogName,
                        "breedId", breedId,
                        "primaryServiceId", serviceId,
                        "coverImageId", coverImageId,
                        "beforeImageId", beforeImageId,
                        "afterImageId", afterImageId,
                        "summary", null,
                        "altText", null,
                        "featured", false,
                        "sortOrder", sortOrder,
                        "performedAt", null,
                        "publishedAt", null));
        assertStatus(response, 201);
        return id(response);
    }

    private void publishGallery(
            Session session,
            UUID id,
            String dogName,
            UUID breedId,
            UUID serviceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            String summary,
            String altText,
            boolean featured,
            int sortOrder,
            Instant performedAt,
            Instant publishedAt)
            throws Exception {
        assertStatus(
                putJson(
                        session,
                        "/api/admin/gallery-items/" + id,
                        galleryUpdate(
                                "published",
                                dogName,
                                breedId,
                                serviceId,
                                coverImageId,
                                beforeImageId,
                                afterImageId,
                                summary,
                                altText,
                                featured,
                                sortOrder,
                                performedAt,
                                publishedAt)),
                200);
    }

    private UUID createNotice(Session session, String title, String slug) throws Exception {
        var response = postJson(
                session,
                "/api/admin/notices",
                body(
                        "title", title,
                        "slug", slug,
                        "summary", null,
                        "bodyMarkdown", null,
                        "pinned", false,
                        "publishedAt", null,
                        "expiresAt", null));
        assertStatus(response, 201);
        return id(response);
    }

    private String breedCreate(String name, String slug, int sortOrder) throws Exception {
        return body(
                "name", name,
                "slug", slug,
                "description", null,
                "sortOrder", sortOrder);
    }

    private String breedUpdate(String status, String name, String description, int sortOrder)
            throws Exception {
        return body(
                "status", status,
                "name", name,
                "description", description,
                "sortOrder", sortOrder);
    }

    private String serviceUpdate(
            String status,
            String name,
            String description,
            String priceText,
            int sortOrder)
            throws Exception {
        return body(
                "status", status,
                "name", name,
                "description", description,
                "priceText", priceText,
                "sortOrder", sortOrder);
    }

    private String shopSettings(UUID hero, UUID groomer, UUID og) throws Exception {
        return body(
                "shopName", "합성 라오미펫",
                "regionLabel", "서울 테스트구",
                "businessType", "반려견 미용",
                "phone", "02-1234-5678",
                "address", "서울 테스트구 합성로 45",
                "openingTime", "10:00",
                "closingTime", "19:00",
                "closedWeekday", "MONDAY",
                "parkingAvailable", true,
                "parkingNote", "합성 주차 안내",
                "heroTitle", "합성 반려견의 편안한 하루",
                "heroDescription", "실제 운영 데이터가 아닌 수용 테스트 설명입니다.",
                "groomerName", "합성 미용사",
                "groomerIntro", "합성 반려견의 속도에 맞춥니다.",
                "reservationNotice", "합성 예약 전 상담이 필요합니다.",
                "heroImageId", hero,
                "heroImageAltText", "합성 Hero 이미지",
                "groomerImageId", groomer,
                "groomerImageAltText", "합성 미용사 프로필",
                "ogImageId", og,
                "instagramUrl", null,
                "naverBlogUrl", "https://blog.example/rhaomi-acceptance",
                "naverMapUrl", "https://map.example/naver-acceptance",
                "kakaoMapUrl", "https://map.example/kakao-acceptance",
                "naverTalktalkUrl", null,
                "kakaoChannelUrl", "https://channel.example/kakao-acceptance");
    }

    private String galleryUpdate(
            String status,
            String dogName,
            UUID breedId,
            UUID serviceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            String summary,
            String altText,
            boolean featured,
            int sortOrder,
            Instant performedAt,
            Instant publishedAt)
            throws Exception {
        return body(
                "status", status,
                "dogName", dogName,
                "breedId", breedId,
                "primaryServiceId", serviceId,
                "coverImageId", coverImageId,
                "beforeImageId", beforeImageId,
                "afterImageId", afterImageId,
                "summary", summary,
                "altText", altText,
                "featured", featured,
                "sortOrder", sortOrder,
                "performedAt", performedAt == null ? null : utc(performedAt),
                "publishedAt", publishedAt == null ? null : utc(publishedAt));
    }

    private String noticeUpdate(
            String status,
            String title,
            String summary,
            String bodyMarkdown,
            boolean pinned,
            String publishedAt,
            String expiresAt)
            throws Exception {
        return body(
                "status", status,
                "title", title,
                "summary", summary,
                "bodyMarkdown", bodyMarkdown,
                "pinned", pinned,
                "publishedAt", publishedAt,
                "expiresAt", expiresAt);
    }

    private String body(Object... pairs) throws Exception {
        return objectMapper.writeValueAsString(object(pairs));
    }

    private Map<String, Object> object(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key/value pairs are required");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private UUID id(HttpResponse<String> response) throws Exception {
        return UUID.fromString(json(response).get("id").asText());
    }

    private void assertStatus(HttpResponse<String> response, int expected) {
        assertEquals(expected, response.statusCode(), response.body());
    }

    private UUID scheduledEvent(UUID sourceId, PublicationEventKind kind, Instant boundary) {
        return jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM publishing_outbox
                WHERE source_id = ?
                  AND kind = ?
                  AND expected_boundary_at = ?
                """,
                UUID.class,
                sourceId,
                kind.name(),
                boundary.atOffset(ZoneOffset.UTC));
    }

    private void assertEvent(
            UUID eventId, PublicationState expectedState, PublicationResultCode expectedCode) {
        PublicationEventStatus status = stateService.findStatus(eventId).orElseThrow();
        assertEquals(expectedState, status.state());
        assertEquals(expectedCode, status.lastResultCode());
    }

    private long currentRevision() {
        return jdbcTemplate.queryForObject(
                "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1",
                Long.class);
    }

    private long currentGeneration() {
        return jdbcTemplate.queryForObject(
                "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1",
                Long.class);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM publishing_outbox", Integer.class);
    }

    private String homeHtml() throws Exception {
        return Files.readString(resolveSite(currentLink).resolve("index.html"));
    }

    private JsonNode releaseManifest(Path link) throws Exception {
        return objectMapper.readTree(
                Files.readString(resolveSite(link).getParent().resolve("release-manifest.json")));
    }

    private Path resolveSite(Path link) throws Exception {
        return link.getParent().resolve(Files.readSymbolicLink(link)).normalize();
    }

    private URI uri(String path) {
        return URI.create(requiredEnvironment("RHAOMI_ADMIN_BASE_URL") + path);
    }

    private String backendBaseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    private Path requiredAcceptanceRoot() throws Exception {
        var root = requiredPath("RHAOMI_PUBLICATION_ACCEPTANCE_ROOT");
        if (root.getParent() == null
                || root.equals(root.getRoot())
                || Files.isSymbolicLink(root)
                || !Files.isRegularFile(root.resolve(MARKER_FILE), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Invalid publication acceptance root");
        }
        return root;
    }

    private Path requiredMediaRoot() {
        var mediaRoot = requiredPath("RHAOMI_MEDIA_ROOT");
        if (!mediaRoot.startsWith(acceptanceRoot) || mediaRoot.equals(acceptanceRoot)) {
            throw new IllegalStateException("Acceptance media root must be task-owned");
        }
        return mediaRoot;
    }

    private Path requiredPath(String name) {
        return Path.of(requiredEnvironment(name)).toAbsolutePath().normalize();
    }

    private String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required acceptance environment is missing");
        }
        return value;
    }

    private String utc(Instant value) {
        return value.toString();
    }

    private String kst(Instant value) {
        return value.atOffset(ZoneOffset.ofHours(9)).toString();
    }

    private byte[] largeJpeg() throws Exception {
        var image = new BufferedImage(2_000, 1_200, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(244, 222, 197));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(88, 63, 46));
            graphics.fillOval(400, 150, 1_200, 900);
        } finally {
            graphics.dispose();
        }
        try (var output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "JPEG", output));
            return output.toByteArray();
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void clearContentFixtures() {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update("DELETE FROM gallery_items");
        jdbcTemplate.update("DELETE FROM notices");
        jdbcTemplate.update("DELETE FROM shop_settings");
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = 0 WHERE singleton_key = 1");
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
    }

    private static final class MutableClock extends Clock {

        private Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }

        private void set(Instant value) {
            this.value = value.truncatedTo(ChronoUnit.MICROS);
        }

        private void advance(Duration duration) {
            set(value.plus(duration));
        }
    }

    private record Session(HttpClient client, String csrfToken) {}

    private record Part(String name, String filename, String contentType, byte[] bytes) {}

    private record Fixtures(
            UUID largeMediaId,
            UUID pngMediaId,
            UUID heicMediaId,
            UUID secondBreedId,
            UUID secondServiceId,
            UUID draftGalleryId,
            UUID secondGalleryId,
            UUID stalePublishedAtEventId,
            UUID rescheduledPublishedAtEventId,
            UUID overduePublishedAtEventId,
            UUID closePublishedAtEventId,
            UUID closeExpiresAtEventId) {}
}
