package kr.co.rhaomi.production;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class InitialContentTestBundle {

    static final String SHOP_NAME = "라오미펫";
    static final String BREED_NAME = "말티푸";
    static final String SERVICE_NAME = "전체 미용";
    static final String NOTICE_TITLE = "첫 공지";
    static final String GALLERY_DOG_NAME = "라오미";
    static final String MEDIA_KEY = "cover";
    static final String PUBLISHED_AT = "2026-09-10T00:00:00.123456Z";

    private InitialContentTestBundle() {}

    static Path create(Path parent) throws IOException {
        Files.createDirectories(parent);
        var root = Files.createDirectory(parent.resolve("bundle"));
        var mediaRoot = Files.createDirectory(root.resolve("media"));
        try (var source = InitialContentTestBundle.class
                .getResourceAsStream("/media/synthetic-source.png")) {
            if (source == null) {
                throw new IllegalStateException("synthetic media fixture missing");
            }
            Files.copy(source, mediaRoot.resolve("cover.png"));
        }
        Files.writeString(root.resolve("content.json"), validContent(), StandardCharsets.UTF_8);
        rewriteManifest(root);
        return root;
    }

    static Path createWithFutureNoticeExpiry(Path parent) throws IOException {
        var root = create(parent);
        var content = root.resolve("content.json");
        Files.writeString(
                content,
                Files.readString(content, StandardCharsets.UTF_8)
                        .replace(
                                "\"expiresAt\": null",
                                "\"expiresAt\": \"2026-09-12T00:00:00Z\""),
                StandardCharsets.UTF_8);
        rewriteManifest(root);
        return root;
    }

    static void rewriteManifest(Path root) throws IOException {
        var content = root.resolve("content.json");
        var media = root.resolve("media/cover.png");
        var manifest = """
                {
                  "schemaVersion": 1,
                  "content": {
                    "path": "content.json",
                    "byteSize": %d,
                    "sha256": "%s"
                  },
                  "media": [
                    {
                      "key": "cover",
                      "path": "media/cover.png",
                      "contentType": "image/png",
                      "byteSize": %d,
                      "sha256": "%s"
                    }
                  ],
                  "counts": {
                    "shopSettings": 1,
                    "media": 1,
                    "breeds": 1,
                    "services": 1,
                    "notices": 1,
                    "galleryItems": 1
                  }
                }
                """.formatted(
                Files.size(content), sha256(content), Files.size(media), sha256(media));
        Files.writeString(root.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
    }

    static String validContent() {
        return """
                {
                  "schemaVersion": 1,
                  "shopSettings": {
                    "shopName": "라오미펫",
                    "regionLabel": "서울",
                    "businessType": "반려견 미용",
                    "phone": "02-1234-5678",
                    "address": "서울시 테스트구",
                    "openingTime": "10:00",
                    "closingTime": "19:00",
                    "closedWeekday": "MONDAY",
                    "parkingAvailable": true,
                    "parkingNote": "주차 가능",
                    "heroTitle": "건강한 미용",
                    "heroDescription": "반려견을 위한 소개",
                    "groomerName": "라오미 원장",
                    "groomerIntro": "차분한 미용",
                    "reservationNotice": "예약 후 방문해 주세요.",
                    "heroMediaKey": "cover",
                    "heroImageAltText": "미용을 마친 반려견",
                    "groomerMediaKey": null,
                    "groomerImageAltText": null,
                    "ogMediaKey": "cover",
                    "instagramUrl": "https://example.com/instagram",
                    "naverBlogUrl": null,
                    "naverMapUrl": null,
                    "kakaoMapUrl": null,
                    "naverTalktalkUrl": null,
                    "kakaoChannelUrl": null
                  },
                  "breeds": [
                    {
                      "key": "maltipoo",
                      "name": "말티푸",
                      "slug": "maltipoo",
                      "description": "말티즈와 푸들의 특징을 가진 견종",
                      "sortOrder": 10
                    }
                  ],
                  "services": [
                    {
                      "key": "full-grooming",
                      "name": "전체 미용",
                      "slug": "full-grooming",
                      "description": "목욕과 전체 미용",
                      "priceText": "상담 후 안내",
                      "sortOrder": 10
                    }
                  ],
                  "notices": [
                    {
                      "key": "first-notice",
                      "title": "첫 공지",
                      "slug": "first-notice",
                      "summary": "첫 운영 안내",
                      "bodyMarkdown": "첫 운영 안내 본문입니다.",
                      "pinned": true,
                      "publishedAt": "2026-09-10T00:00:00.123456Z",
                      "expiresAt": null
                    }
                  ],
                  "galleryItems": [
                    {
                      "key": "first-gallery",
                      "dogName": "라오미",
                      "breedKey": "maltipoo",
                      "primaryServiceKey": "full-grooming",
                      "coverMediaKey": "cover",
                      "beforeMediaKey": null,
                      "afterMediaKey": null,
                      "summary": "첫 미용 사례",
                      "altText": "미용을 마친 라오미",
                      "featured": true,
                      "sortOrder": 10,
                      "performedAt": "2026-09-09T00:00:00Z",
                      "publishedAt": "2026-09-10T00:00:00.123456Z"
                    }
                  ]
                }
                """;
    }

    static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
