package kr.co.rhaomi.backend.shop;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentAudit;

@Entity
@Table(name = "shop_settings")
public class ShopSettings {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "singleton_key", nullable = false, unique = true, updatable = false)
    private boolean singletonKey;

    @Column(name = "shop_name", nullable = false, length = 100)
    private String shopName;

    @Column(name = "region_label", nullable = false, length = 100)
    private String regionLabel;

    @Column(name = "business_type", nullable = false, length = 100)
    private String businessType;

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(name = "opening_time", nullable = false)
    private LocalTime openingTime;

    @Column(name = "closing_time", nullable = false)
    private LocalTime closingTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "closed_weekday", length = 9)
    private DayOfWeek closedWeekday;

    @Column(name = "parking_available", nullable = false)
    private boolean parkingAvailable;

    @Column(name = "parking_note", length = 300)
    private String parkingNote;

    @Column(name = "hero_title", length = 200)
    private String heroTitle;

    @Column(name = "hero_description", length = 1_000)
    private String heroDescription;

    @Column(name = "groomer_name", length = 100)
    private String groomerName;

    @Column(name = "groomer_intro", length = 2_000)
    private String groomerIntro;

    @Column(name = "reservation_notice", length = 4_000)
    private String reservationNotice;

    @Column(name = "hero_image_id")
    private UUID heroImageId;

    @Column(name = "hero_image_alt_text", length = 300)
    private String heroImageAltText;

    @Column(name = "groomer_image_id")
    private UUID groomerImageId;

    @Column(name = "groomer_image_alt_text", length = 300)
    private String groomerImageAltText;

    @Column(name = "og_image_id")
    private UUID ogImageId;

    @Column(name = "instagram_url", length = 2_048)
    private String instagramUrl;

    @Column(name = "naver_blog_url", length = 2_048)
    private String naverBlogUrl;

    @Column(name = "naver_map_url", length = 2_048)
    private String naverMapUrl;

    @Column(name = "kakao_map_url", length = 2_048)
    private String kakaoMapUrl;

    @Column(name = "naver_talktalk_url", length = 2_048)
    private String naverTalktalkUrl;

    @Column(name = "kakao_channel_url", length = 2_048)
    private String kakaoChannelUrl;

    @Embedded
    private ContentAudit audit;

    protected ShopSettings() {}

    private ShopSettings(ShopSettingsValues values, UUID actorId) {
        id = UUID.randomUUID();
        singletonKey = true;
        apply(values);
        audit = ContentAudit.create(actorId);
    }

    public static ShopSettings create(ShopSettingsValues values, UUID actorId) {
        return new ShopSettings(values, actorId);
    }

    public void update(ShopSettingsValues values, UUID actorId) {
        apply(values);
        audit.touch(actorId);
    }

    private void apply(ShopSettingsValues values) {
        shopName = values.shopName();
        regionLabel = values.regionLabel();
        businessType = values.businessType();
        phone = values.phone();
        address = values.address();
        openingTime = values.openingTime();
        closingTime = values.closingTime();
        closedWeekday = values.closedWeekday();
        parkingAvailable = values.parkingAvailable();
        parkingNote = values.parkingNote();
        heroTitle = values.heroTitle();
        heroDescription = values.heroDescription();
        groomerName = values.groomerName();
        groomerIntro = values.groomerIntro();
        reservationNotice = values.reservationNotice();
        heroImageId = values.heroImageId();
        heroImageAltText = values.heroImageAltText();
        groomerImageId = values.groomerImageId();
        groomerImageAltText = values.groomerImageAltText();
        ogImageId = values.ogImageId();
        instagramUrl = values.instagramUrl();
        naverBlogUrl = values.naverBlogUrl();
        naverMapUrl = values.naverMapUrl();
        kakaoMapUrl = values.kakaoMapUrl();
        naverTalktalkUrl = values.naverTalktalkUrl();
        kakaoChannelUrl = values.kakaoChannelUrl();
    }

    public String getShopName() {
        return shopName;
    }

    public String getRegionLabel() {
        return regionLabel;
    }

    public String getBusinessType() {
        return businessType;
    }

    public String getPhone() {
        return phone;
    }

    public String getAddress() {
        return address;
    }

    public LocalTime getOpeningTime() {
        return openingTime;
    }

    public LocalTime getClosingTime() {
        return closingTime;
    }

    public DayOfWeek getClosedWeekday() {
        return closedWeekday;
    }

    public boolean isParkingAvailable() {
        return parkingAvailable;
    }

    public String getParkingNote() {
        return parkingNote;
    }

    public String getHeroTitle() {
        return heroTitle;
    }

    public String getHeroDescription() {
        return heroDescription;
    }

    public String getGroomerName() {
        return groomerName;
    }

    public String getGroomerIntro() {
        return groomerIntro;
    }

    public String getReservationNotice() {
        return reservationNotice;
    }

    public UUID getHeroImageId() {
        return heroImageId;
    }

    public String getHeroImageAltText() {
        return heroImageAltText;
    }

    public UUID getGroomerImageId() {
        return groomerImageId;
    }

    public String getGroomerImageAltText() {
        return groomerImageAltText;
    }

    public UUID getOgImageId() {
        return ogImageId;
    }

    public String getInstagramUrl() {
        return instagramUrl;
    }

    public String getNaverBlogUrl() {
        return naverBlogUrl;
    }

    public String getNaverMapUrl() {
        return naverMapUrl;
    }

    public String getKakaoMapUrl() {
        return kakaoMapUrl;
    }

    public String getNaverTalktalkUrl() {
        return naverTalktalkUrl;
    }

    public String getKakaoChannelUrl() {
        return kakaoChannelUrl;
    }

    public Instant getCreatedAt() {
        return audit.getCreatedAt();
    }

    public Instant getUpdatedAt() {
        return audit.getUpdatedAt();
    }

    public UUID getCreatedBy() {
        return audit.getCreatedBy();
    }

    public UUID getUpdatedBy() {
        return audit.getUpdatedBy();
    }
}
