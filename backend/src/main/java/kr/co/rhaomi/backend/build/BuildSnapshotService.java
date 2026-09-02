package kr.co.rhaomi.backend.build;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.co.rhaomi.backend.breed.Breed;
import kr.co.rhaomi.backend.gallery.GalleryItem;
import kr.co.rhaomi.backend.media.MediaAsset;
import kr.co.rhaomi.backend.media.MediaStorage;
import kr.co.rhaomi.backend.media.MediaStorageException;
import kr.co.rhaomi.backend.notice.Notice;
import kr.co.rhaomi.backend.service.GroomingService;
import kr.co.rhaomi.backend.shop.ShopSettings;
import kr.co.rhaomi.backend.shop.ShopSettingsBuildValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildSnapshotService {

    private static final int SCHEMA_VERSION = 2;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final BuildDataReader reader;
    private final MediaStorage mediaStorage;
    private final Clock clock;

    public BuildSnapshotService(BuildDataReader reader, MediaStorage mediaStorage, Clock clock) {
        this.reader = reader;
        this.mediaStorage = mediaStorage;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BuildSnapshotResponse snapshot(long publishGeneration) {
        validateGeneration(publishGeneration);
        var generatedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        requireActiveGeneration(publishGeneration, generatedAt);

        var contentRevision = reader.currentContentRevision();
        var shop = reader.shop().orElseThrow(BuildSnapshotInvalidException::new);
        var breeds = reader.publishedBreeds();
        var services = reader.publishedServices();
        var galleryItems = reader.eligibleGalleryItems(generatedAt);
        var notices = reader.eligibleNotices(generatedAt);

        validateContent(shop, breeds, services, galleryItems, notices, generatedAt);
        var mediaAssets = verifiedMediaAssets(shop, galleryItems);

        return new BuildSnapshotResponse(
                SCHEMA_VERSION,
                Long.toString(contentRevision),
                Long.toString(publishGeneration),
                generatedAt,
                shop(shop),
                services.stream().map(this::service).toList(),
                breeds.stream().map(this::breed).toList(),
                galleryItems.stream().map(this::galleryItem).toList(),
                notices.stream().map(this::notice).toList(),
                mediaAssets.stream().map(this::mediaAsset).toList());
    }

    private void validateGeneration(long publishGeneration) {
        if (publishGeneration <= 0) {
            throw new BuildInvalidRequestException();
        }
    }

    private void requireActiveGeneration(long publishGeneration, Instant generatedAt) {
        if (!reader.isActiveGeneration(publishGeneration, generatedAt)) {
            throw new BuildGenerationNotActiveException();
        }
    }

    private void validateContent(
            ShopSettings shop,
            List<Breed> breeds,
            List<GroomingService> services,
            List<GalleryItem> galleryItems,
            List<Notice> notices,
            Instant generatedAt) {
        if (!ShopSettingsBuildValidator.isValid(shop)
                || breeds.stream().anyMatch(breed -> !BuildContentValidator.isValid(breed))
                || services.stream().anyMatch(service -> !BuildContentValidator.isValid(service))
                || galleryItems.stream()
                        .anyMatch(item -> !BuildContentValidator.isValid(item, generatedAt))
                || notices.stream()
                        .anyMatch(notice -> !BuildContentValidator.isValid(notice, generatedAt))) {
            throw new BuildSnapshotInvalidException();
        }

        var publishedBreedIds = breeds.stream().map(Breed::getId).collect(Collectors.toSet());
        var publishedServiceIds = services.stream()
                .map(GroomingService::getId)
                .collect(Collectors.toSet());
        if (galleryItems.stream().anyMatch(item ->
                !publishedBreedIds.contains(item.getBreedId())
                        || !publishedServiceIds.contains(item.getPrimaryServiceId()))) {
            throw new BuildSnapshotInvalidException();
        }
    }

    private List<MediaAsset> verifiedMediaAssets(
            ShopSettings shop, List<GalleryItem> galleryItems) {
        var referencedIds = new LinkedHashSet<UUID>();
        add(referencedIds, shop.getHeroImageId());
        add(referencedIds, shop.getGroomerImageId());
        add(referencedIds, shop.getOgImageId());
        galleryItems.forEach(item -> {
            add(referencedIds, item.getCoverImageId());
            add(referencedIds, item.getBeforeImageId());
            add(referencedIds, item.getAfterImageId());
        });

        var mediaAssets = reader.mediaAssets(referencedIds);
        var resolvedIds = new HashSet<UUID>();
        for (var media : mediaAssets) {
            if (!BuildContentValidator.isValid(media) || !resolvedIds.add(media.getId())) {
                throw new BuildSnapshotInvalidException();
            }
            try {
                mediaStorage.verifiedContent(media);
            } catch (MediaStorageException exception) {
                throw new BuildSnapshotInvalidException();
            }
        }
        if (!resolvedIds.equals(Set.copyOf(referencedIds))) {
            throw new BuildSnapshotInvalidException();
        }
        return mediaAssets;
    }

    private void add(Set<UUID> target, UUID id) {
        if (id != null) {
            target.add(id);
        }
    }

    private BuildSnapshotResponse.Shop shop(ShopSettings settings) {
        return new BuildSnapshotResponse.Shop(
                settings.getShopName(),
                settings.getRegionLabel(),
                settings.getBusinessType(),
                settings.getPhone(),
                settings.getAddress(),
                TIME_FORMAT.format(settings.getOpeningTime()),
                TIME_FORMAT.format(settings.getClosingTime()),
                settings.getClosedWeekday() == null
                        ? null
                        : settings.getClosedWeekday().name(),
                settings.isParkingAvailable(),
                settings.getParkingNote(),
                settings.getHeroTitle(),
                settings.getHeroDescription(),
                settings.getGroomerName(),
                settings.getGroomerIntro(),
                settings.getReservationNotice(),
                settings.getHeroImageId(),
                settings.getHeroImageAltText(),
                settings.getGroomerImageId(),
                settings.getGroomerImageAltText(),
                settings.getOgImageId(),
                settings.getInstagramUrl(),
                settings.getNaverBlogUrl(),
                settings.getNaverMapUrl(),
                settings.getKakaoMapUrl(),
                settings.getNaverTalktalkUrl(),
                settings.getKakaoChannelUrl());
    }

    private BuildSnapshotResponse.Breed breed(Breed breed) {
        return new BuildSnapshotResponse.Breed(
                breed.getId(),
                breed.getName(),
                breed.getSlug(),
                breed.getDescription(),
                breed.getSortOrder());
    }

    private BuildSnapshotResponse.Service service(GroomingService service) {
        return new BuildSnapshotResponse.Service(
                service.getId(),
                service.getName(),
                service.getSlug(),
                service.getDescription(),
                service.getPriceText(),
                service.getSortOrder());
    }

    private BuildSnapshotResponse.GalleryItem galleryItem(GalleryItem item) {
        return new BuildSnapshotResponse.GalleryItem(
                item.getId(),
                item.getDogName(),
                item.getBreedId(),
                item.getPrimaryServiceId(),
                item.getCoverImageId(),
                item.getBeforeImageId(),
                item.getAfterImageId(),
                item.getSummary(),
                item.getAltText(),
                item.isFeatured(),
                item.getSortOrder(),
                item.getPerformedAt(),
                item.getPublishedAt());
    }

    private BuildSnapshotResponse.Notice notice(Notice notice) {
        return new BuildSnapshotResponse.Notice(
                notice.getId(),
                notice.getTitle(),
                notice.getSlug(),
                notice.getSummary(),
                notice.getBodyMarkdown(),
                notice.isPinned(),
                notice.getPublishedAt(),
                notice.getExpiresAt());
    }

    private BuildSnapshotResponse.MediaAsset mediaAsset(MediaAsset media) {
        return new BuildSnapshotResponse.MediaAsset(
                media.getId(),
                media.getContentType(),
                media.getByteSize(),
                media.getWidth(),
                media.getHeight());
    }
}
