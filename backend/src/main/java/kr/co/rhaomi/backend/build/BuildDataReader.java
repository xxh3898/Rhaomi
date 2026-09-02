package kr.co.rhaomi.backend.build;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.co.rhaomi.backend.breed.Breed;
import kr.co.rhaomi.backend.breed.BreedRepository;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.gallery.GalleryItem;
import kr.co.rhaomi.backend.gallery.GalleryRepository;
import kr.co.rhaomi.backend.media.MediaAsset;
import kr.co.rhaomi.backend.media.MediaAssetRepository;
import kr.co.rhaomi.backend.notice.Notice;
import kr.co.rhaomi.backend.notice.NoticeRepository;
import kr.co.rhaomi.backend.service.GroomingService;
import kr.co.rhaomi.backend.service.GroomingServiceRepository;
import kr.co.rhaomi.backend.shop.ShopSettings;
import kr.co.rhaomi.backend.shop.ShopSettingsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BuildDataReader {

    private final JdbcTemplate jdbcTemplate;
    private final ShopSettingsRepository shopSettingsRepository;
    private final BreedRepository breedRepository;
    private final GroomingServiceRepository serviceRepository;
    private final GalleryRepository galleryRepository;
    private final NoticeRepository noticeRepository;
    private final MediaAssetRepository mediaRepository;

    public BuildDataReader(
            JdbcTemplate jdbcTemplate,
            ShopSettingsRepository shopSettingsRepository,
            BreedRepository breedRepository,
            GroomingServiceRepository serviceRepository,
            GalleryRepository galleryRepository,
            NoticeRepository noticeRepository,
            MediaAssetRepository mediaRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.shopSettingsRepository = shopSettingsRepository;
        this.breedRepository = breedRepository;
        this.serviceRepository = serviceRepository;
        this.galleryRepository = galleryRepository;
        this.noticeRepository = noticeRepository;
        this.mediaRepository = mediaRepository;
    }

    public boolean isActiveGeneration(long publishGeneration, Instant generatedAt) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM publishing_outbox
                    WHERE publish_generation = ?
                      AND state = 'PROCESSING'
                      AND lease_until > ?
                )
                """,
                Boolean.class,
                publishGeneration,
                offset(generatedAt)));
    }

    public long currentContentRevision() {
        var revision = jdbcTemplate.queryForObject(
                """
                SELECT content_revision
                FROM content_revision_state
                WHERE singleton_key = 1
                """,
                Long.class);
        if (revision == null) {
            throw new IllegalStateException("Content revision singleton is missing");
        }
        return revision;
    }

    public Optional<ShopSettings> shop() {
        return shopSettingsRepository.findBySingletonKeyTrue();
    }

    public List<Breed> publishedBreeds() {
        return breedRepository.findAllByStatusOrderBySortOrderAscNameAscIdAsc(
                ContentStatus.PUBLISHED);
    }

    public List<GroomingService> publishedServices() {
        return serviceRepository.findAllByStatusOrderBySortOrderAscNameAscIdAsc(
                ContentStatus.PUBLISHED);
    }

    public List<GalleryItem> eligibleGalleryItems(Instant generatedAt) {
        return galleryRepository.findAllForBuild(ContentStatus.PUBLISHED, generatedAt);
    }

    public List<Notice> eligibleNotices(Instant generatedAt) {
        return noticeRepository.findAllForBuild(ContentStatus.PUBLISHED, generatedAt);
    }

    public List<MediaAsset> mediaAssets(Set<UUID> ids) {
        return ids.isEmpty() ? List.of() : mediaRepository.findAllForBuild(ids);
    }

    public Optional<MediaAsset> mediaAsset(UUID id) {
        return mediaRepository.findById(id);
    }

    public boolean isMediaInCurrentPublicScope(UUID id, Instant generatedAt) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM shop_settings
                    WHERE hero_image_id = ?
                       OR groomer_image_id = ?
                       OR og_image_id = ?
                    UNION ALL
                    SELECT 1
                    FROM gallery_items gallery
                    JOIN breeds breed ON breed.id = gallery.breed_id
                    JOIN services service ON service.id = gallery.primary_service_id
                    WHERE gallery.status = 'published'
                      AND gallery.published_at <= ?
                      AND breed.status = 'published'
                      AND service.status = 'published'
                      AND (
                          gallery.cover_image_id = ?
                          OR gallery.before_image_id = ?
                          OR gallery.after_image_id = ?
                      )
                )
                """,
                Boolean.class,
                id,
                id,
                id,
                offset(generatedAt),
                id,
                id,
                id));
    }

    private OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
