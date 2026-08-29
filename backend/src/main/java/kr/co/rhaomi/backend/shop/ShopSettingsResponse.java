package kr.co.rhaomi.backend.shop;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

public record ShopSettingsResponse(
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
        String kakaoChannelUrl,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy) {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    public static ShopSettingsResponse from(ShopSettings settings) {
        return new ShopSettingsResponse(
                settings.getShopName(),
                settings.getRegionLabel(),
                settings.getBusinessType(),
                settings.getPhone(),
                settings.getAddress(),
                TIME_FORMAT.format(settings.getOpeningTime()),
                TIME_FORMAT.format(settings.getClosingTime()),
                settings.getClosedWeekday() == null ? null : settings.getClosedWeekday().name(),
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
                settings.getKakaoChannelUrl(),
                settings.getCreatedAt(),
                settings.getUpdatedAt(),
                settings.getCreatedBy(),
                settings.getUpdatedBy());
    }
}
