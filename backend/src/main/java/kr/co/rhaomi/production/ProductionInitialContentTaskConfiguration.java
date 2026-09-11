package kr.co.rhaomi.production;

import jakarta.validation.Validator;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import kr.co.rhaomi.backend.breed.Breed;
import kr.co.rhaomi.backend.breed.BreedAdminService;
import kr.co.rhaomi.backend.breed.BreedRepository;
import kr.co.rhaomi.backend.gallery.GalleryAdminService;
import kr.co.rhaomi.backend.gallery.GalleryItem;
import kr.co.rhaomi.backend.gallery.GalleryRepository;
import kr.co.rhaomi.backend.media.MediaAdminService;
import kr.co.rhaomi.backend.media.MediaAsset;
import kr.co.rhaomi.backend.media.MediaAssetRepository;
import kr.co.rhaomi.backend.media.MediaProperties;
import kr.co.rhaomi.backend.notice.Notice;
import kr.co.rhaomi.backend.notice.NoticeAdminService;
import kr.co.rhaomi.backend.notice.NoticeRepository;
import kr.co.rhaomi.backend.publication.PublicationRecorder;
import kr.co.rhaomi.backend.service.GroomingService;
import kr.co.rhaomi.backend.service.GroomingServiceRepository;
import kr.co.rhaomi.backend.service.ServiceAdminService;
import kr.co.rhaomi.backend.shop.ShopSettings;
import kr.co.rhaomi.backend.shop.ShopSettingsAdminService;
import kr.co.rhaomi.backend.shop.ShopSettingsRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableConfigurationProperties(MediaProperties.class)
@EntityScan(basePackageClasses = {
    AdminUser.class,
    Breed.class,
    GroomingService.class,
    Notice.class,
    GalleryItem.class,
    ShopSettings.class,
    MediaAsset.class
})
@EnableJpaRepositories(basePackageClasses = {
    AdminUserRepository.class,
    BreedRepository.class,
    GroomingServiceRepository.class,
    NoticeRepository.class,
    GalleryRepository.class,
    ShopSettingsRepository.class,
    MediaAssetRepository.class
})
@Import({
    PublicationRecorder.class,
    BreedAdminService.class,
    ServiceAdminService.class,
    NoticeAdminService.class,
    GalleryAdminService.class,
    ShopSettingsAdminService.class
})
@ComponentScan(
        basePackages = "kr.co.rhaomi.backend.media",
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "kr\\.co\\.rhaomi\\.backend\\.media\\.(?:MediaAdminService|MediaStorage|MediaImageProcessor|MediaFormatDetector|HeifCodecProbe)"))
class ProductionInitialContentTaskConfiguration {

    static final Path INPUT_ROOT = Path.of("/run/rhaomi-initial-content");

    @Bean
    @ConditionalOnMissingBean(name = "initialContentInputRoot")
    Path initialContentInputRoot() {
        return INPUT_ROOT;
    }

    @Bean
    @ConditionalOnMissingBean(name = "initialContentOutput")
    PrintStream initialContentOutput() {
        return System.out;
    }

    @Bean
    @ConditionalOnMissingBean(name = "initialContentClock")
    Clock initialContentClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(InitialContentImportCheckpoint.class)
    InitialContentImportCheckpoint initialContentImportCheckpoint() {
        return InitialContentImportCheckpoint.noop();
    }

    @Bean
    InitialContentBundleReader initialContentBundleReader() {
        return new InitialContentBundleReader();
    }

    @Bean
    InitialContentImportLock initialContentImportLock(JdbcTemplate jdbcTemplate) {
        return new InitialContentImportLock(jdbcTemplate);
    }

    @Bean
    InitialContentImportService initialContentImportService(
            AdminUserRepository adminUserRepository,
            BreedRepository breedRepository,
            GroomingServiceRepository serviceRepository,
            NoticeRepository noticeRepository,
            GalleryRepository galleryRepository,
            ShopSettingsRepository shopSettingsRepository,
            MediaAssetRepository mediaAssetRepository,
            MediaAdminService mediaAdminService,
            BreedAdminService breedAdminService,
            ServiceAdminService serviceAdminService,
            NoticeAdminService noticeAdminService,
            GalleryAdminService galleryAdminService,
            ShopSettingsAdminService shopSettingsAdminService,
            PublicationRecorder publicationRecorder,
            InitialContentImportLock importLock,
            InitialContentImportCheckpoint checkpoint,
            Validator validator,
            JdbcTemplate jdbcTemplate,
            Clock initialContentClock,
            MediaProperties mediaProperties) {
        return new InitialContentImportService(
                adminUserRepository,
                breedRepository,
                serviceRepository,
                noticeRepository,
                galleryRepository,
                shopSettingsRepository,
                mediaAssetRepository,
                mediaAdminService,
                breedAdminService,
                serviceAdminService,
                noticeAdminService,
                galleryAdminService,
                shopSettingsAdminService,
                publicationRecorder,
                importLock,
                checkpoint,
                validator,
                jdbcTemplate,
                initialContentClock,
                mediaProperties);
    }

    @Bean
    InitialContentImportRunner initialContentImportRunner(
            Path initialContentInputRoot,
            InitialContentBundleReader bundleReader,
            InitialContentImportService importService,
            PrintStream initialContentOutput) {
        return new InitialContentImportRunner(
                initialContentInputRoot, bundleReader, importService, initialContentOutput);
    }
}
