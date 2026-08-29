package kr.co.rhaomi.backend.shop;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Pattern;

record ShopSettingsValues(
        String shopName,
        String regionLabel,
        String businessType,
        String phone,
        String address,
        LocalTime openingTime,
        LocalTime closingTime,
        DayOfWeek closedWeekday,
        boolean parkingAvailable,
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
        String kakaoChannelUrl) {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern TIME_PATTERN = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+() -]+$");

    static ShopSettingsValues from(ShopSettingsRequest request) {
        if (request == null || request.parkingAvailable() == null) {
            throw new ShopSettingsInvalidRequestException();
        }

        var openingTime = parseTime(request.openingTime());
        var closingTime = parseTime(request.closingTime());
        if (!openingTime.isBefore(closingTime)) {
            throw new BusinessHoursInvalidException();
        }

        return new ShopSettingsValues(
                required(request.shopName(), 100),
                required(request.regionLabel(), 100),
                required(request.businessType(), 100),
                phone(request.phone()),
                required(request.address(), 300),
                openingTime,
                closingTime,
                weekday(request.closedWeekday()),
                request.parkingAvailable(),
                optional(request.parkingNote(), 300),
                optional(request.heroTitle(), 200),
                optional(request.heroDescription(), 1_000),
                optional(request.groomerName(), 100),
                optional(request.groomerIntro(), 2_000),
                optional(request.reservationNotice(), 4_000),
                url(request.instagramUrl()),
                url(request.naverBlogUrl()),
                url(request.naverMapUrl()),
                url(request.kakaoMapUrl()),
                url(request.naverTalktalkUrl()),
                url(request.kakaoChannelUrl()));
    }

    private static String required(String value, int maxLength) {
        if (value == null) {
            throw new ShopSettingsInvalidRequestException();
        }
        var normalized = stripUnicodeWhitespace(value);
        if (normalized.isEmpty() || length(normalized) > maxLength) {
            throw new ShopSettingsInvalidRequestException();
        }
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        var normalized = value == null ? null : stripUnicodeWhitespace(value);
        if (normalized != null && normalized.isEmpty()) {
            return null;
        }
        if (normalized != null && length(normalized) > maxLength) {
            throw new ShopSettingsInvalidRequestException();
        }
        return normalized;
    }

    private static String phone(String value) {
        if (value != null && value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ShopSettingsInvalidRequestException();
        }
        var normalized = required(value, 32);
        var digitCount = normalized.chars().filter(character -> character >= '0' && character <= '9').count();
        if (length(normalized) < 7 || digitCount < 7 || !PHONE_PATTERN.matcher(normalized).matches()) {
            throw new ShopSettingsInvalidRequestException();
        }
        return normalized;
    }

    private static String url(String value) {
        if (value != null && value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ShopSettingsInvalidRequestException();
        }
        var normalized = optional(value, 2_048);
        if (normalized == null) {
            return null;
        }
        try {
            var uri = new URI(normalized);
            if (!uri.isAbsolute()
                    || uri.getScheme() == null
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null) {
                throw new ShopSettingsInvalidRequestException();
            }
        } catch (URISyntaxException exception) {
            throw new ShopSettingsInvalidRequestException();
        }
        return normalized;
    }

    private static LocalTime parseTime(String value) {
        if (value == null || !TIME_PATTERN.matcher(value).matches()) {
            throw new ShopSettingsInvalidRequestException();
        }
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new ShopSettingsInvalidRequestException();
        }
    }

    private static DayOfWeek weekday(String value) {
        if (value == null) {
            return null;
        }
        try {
            return DayOfWeek.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ShopSettingsInvalidRequestException();
        }
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String stripUnicodeWhitespace(String value) {
        var start = 0;
        var end = value.length();
        while (start < end) {
            var codePoint = value.codePointAt(start);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            var codePoint = value.codePointBefore(end);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
