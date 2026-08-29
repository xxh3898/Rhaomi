package kr.co.rhaomi.backend.shop;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ShopSettingsRequest(
        @NotNull String shopName,
        @NotNull String regionLabel,
        @NotNull String businessType,
        @NotNull String phone,
        @NotNull String address,
        @NotNull @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$") String openingTime,
        @NotNull @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$") String closingTime,
        @Pattern(regexp = "^(?:MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)$")
                String closedWeekday,
        @NotNull Boolean parkingAvailable,
        String parkingNote,
        String heroTitle,
        String heroDescription,
        String groomerName,
        String groomerIntro,
        String reservationNotice,
        String instagramUrl,
        String naverBlogUrl,
        String naverMapUrl,
        String kakaoMapUrl,
        String naverTalktalkUrl,
        String kakaoChannelUrl) {}
