package kr.co.rhaomi.backend.build;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonPropertyOrder({
    "schemaVersion",
    "contentRevision",
    "publishGeneration",
    "generatedAt",
    "shop",
    "services",
    "breeds",
    "galleryItems",
    "notices",
    "mediaAssets"
})
public record BuildSnapshotResponse(
        int schemaVersion,
        long contentRevision,
        long publishGeneration,
        Instant generatedAt,
        Shop shop,
        List<Service> services,
        List<Breed> breeds,
        List<GalleryItem> galleryItems,
        List<Notice> notices,
        List<MediaAsset> mediaAssets) {

    public record Shop(
            String shopName,
            String regionLabel,
            String businessType,
            String phone,
            String address,
            String openingTime,
            String closingTime,
            String closedWeekday,
            boolean parkingAvailable,
            String parkingNote,
            String heroTitle,
            String heroDescription,
            String groomerName,
            String groomerIntro,
            String reservationNotice,
            UUID heroImageId,
            String heroImageAltText,
            UUID groomerImageId,
            String groomerImageAltText,
            UUID ogImageId,
            String instagramUrl,
            String naverBlogUrl,
            String naverMapUrl,
            String kakaoMapUrl,
            String naverTalktalkUrl,
            String kakaoChannelUrl) {}

    public record Breed(UUID id, String name, String slug, String description, int sortOrder) {}

    public record Service(
            UUID id,
            String name,
            String slug,
            String description,
            String priceText,
            int sortOrder) {}

    public record GalleryItem(
            UUID id,
            String dogName,
            UUID breedId,
            UUID primaryServiceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            String summary,
            String altText,
            boolean featured,
            int sortOrder,
            Instant performedAt,
            Instant publishedAt) {}

    public record Notice(
            UUID id,
            String title,
            String slug,
            String summary,
            String bodyMarkdown,
            boolean pinned,
            Instant publishedAt,
            Instant expiresAt) {}

    public record MediaAsset(UUID id, String contentType, long byteSize, int width, int height) {}
}
