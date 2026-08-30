package kr.co.rhaomi.backend.shop;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class ShopSettingsBuildValidator {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private ShopSettingsBuildValidator() {}

    public static boolean isValid(ShopSettings settings) {
        if (settings == null) {
            return false;
        }
        try {
            var raw = new ShopSettingsValues(
                    settings.getShopName(),
                    settings.getRegionLabel(),
                    settings.getBusinessType(),
                    settings.getPhone(),
                    settings.getAddress(),
                    settings.getOpeningTime(),
                    settings.getClosingTime(),
                    settings.getClosedWeekday(),
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
            var validated = ShopSettingsValues.from(new ShopSettingsRequest(
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
                    settings.getKakaoChannelUrl()));
            return raw.equals(validated);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
