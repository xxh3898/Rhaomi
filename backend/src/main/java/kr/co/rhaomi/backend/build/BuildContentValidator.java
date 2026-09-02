package kr.co.rhaomi.backend.build;

import java.time.Instant;
import java.util.regex.Pattern;
import kr.co.rhaomi.backend.breed.Breed;
import kr.co.rhaomi.backend.content.ContentFields;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.gallery.GalleryItem;
import kr.co.rhaomi.backend.media.MediaAsset;
import kr.co.rhaomi.backend.media.MediaStatus;
import kr.co.rhaomi.backend.notice.Notice;
import kr.co.rhaomi.backend.service.GroomingService;

final class BuildContentValidator {

    private static final Pattern SLUG_PATTERN = Pattern.compile(ContentFields.SLUG_PATTERN);

    private BuildContentValidator() {}

    static boolean isValid(Breed breed) {
        return breed != null
                && breed.getStatus() == ContentStatus.PUBLISHED
                && hasText(breed.getName())
                && breed.getName().length() <= 100
                && validSlug(breed.getSlug(), 120)
                && (breed.getDescription() == null || hasText(breed.getDescription()))
                && breed.getSortOrder() >= 0;
    }

    static boolean isValid(GroomingService service) {
        return service != null
                && service.getStatus() == ContentStatus.PUBLISHED
                && hasText(service.getName())
                && service.getName().length() <= 100
                && validSlug(service.getSlug(), 120)
                && hasText(service.getDescription())
                && hasText(service.getPriceText())
                && service.getPriceText().length() <= 100
                && service.getSortOrder() >= 0;
    }

    static boolean isValid(GalleryItem item, Instant generatedAt) {
        return item != null
                && item.getStatus() == ContentStatus.PUBLISHED
                && optionalWithin(item.getDogName(), 100)
                && item.getBreedId() != null
                && item.getPrimaryServiceId() != null
                && item.getCoverImageId() != null
                && optionalWithin(item.getSummary(), 1_000)
                && hasText(item.getAltText())
                && item.getAltText().length() <= 300
                && item.getSortOrder() >= 0
                && item.getPublishedAt() != null
                && !item.getPublishedAt().isAfter(generatedAt)
                && (item.getBeforeImageId() == null
                        || !item.getBeforeImageId().equals(item.getAfterImageId()));
    }

    static boolean isValid(Notice notice, Instant generatedAt) {
        return notice != null
                && notice.getStatus() == ContentStatus.PUBLISHED
                && hasText(notice.getTitle())
                && notice.getTitle().length() <= 200
                && validSlug(notice.getSlug(), 160)
                && optionalWithin(notice.getSummary(), 300)
                && hasText(notice.getBodyMarkdown())
                && notice.getBodyMarkdown().length() <= 50_000
                && notice.getPublishedAt() != null
                && !notice.getPublishedAt().isAfter(generatedAt)
                && (notice.getExpiresAt() == null
                        || (notice.getExpiresAt().isAfter(generatedAt)
                                && notice.getExpiresAt().isAfter(notice.getPublishedAt())));
    }

    static boolean isValid(MediaAsset media) {
        return media != null
                && media.getStatus() == MediaStatus.ACTIVE
                && ("image/jpeg".equals(media.getContentType())
                        || "image/png".equals(media.getContentType()))
                && media.getByteSize() > 0
                && media.getByteSize() <= 31_457_280
                && media.getWidth() > 0
                && media.getWidth() <= 12_000
                && media.getHeight() > 0
                && media.getHeight() <= 12_000
                && (long) media.getWidth() * media.getHeight() <= 60_000_000;
    }

    private static boolean validSlug(String value, int maxLength) {
        return value != null
                && value.length() <= maxLength
                && SLUG_PATTERN.matcher(value).matches();
    }

    private static boolean optionalWithin(String value, int maxLength) {
        return value == null || (hasText(value) && value.length() <= maxLength);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
