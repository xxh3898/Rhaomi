package kr.co.rhaomi.production;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

record InitialContentBundle(
        String manifestSha256,
        Shop shopSettings,
        List<MediaFile> media,
        List<Breed> breeds,
        List<Service> services,
        List<Notice> notices,
        List<GalleryItem> galleryItems) {

    record Shop(
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
            String heroMediaKey,
            String heroImageAltText,
            String groomerMediaKey,
            String groomerImageAltText,
            String ogMediaKey,
            String instagramUrl,
            String naverBlogUrl,
            String naverMapUrl,
            String kakaoMapUrl,
            String naverTalktalkUrl,
            String kakaoChannelUrl) {}

    record MediaFile(
            String key,
            Path path,
            String contentType,
            long byteSize,
            String sha256) {}

    record Breed(
            String key, String name, String slug, String description, int sortOrder) {}

    record Service(
            String key,
            String name,
            String slug,
            String description,
            String priceText,
            int sortOrder) {}

    record Notice(
            String key,
            String title,
            String slug,
            String summary,
            String bodyMarkdown,
            boolean pinned,
            Instant publishedAt,
            Instant expiresAt) {}

    record GalleryItem(
            String key,
            String dogName,
            String breedKey,
            String primaryServiceKey,
            String coverMediaKey,
            String beforeMediaKey,
            String afterMediaKey,
            String summary,
            String altText,
            boolean featured,
            int sortOrder,
            Instant performedAt,
            Instant publishedAt) {}
}
