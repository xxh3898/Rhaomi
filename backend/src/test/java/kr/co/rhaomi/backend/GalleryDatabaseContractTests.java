package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GalleryDatabaseContractTests {

    private static final String ADMIN_EMAIL = "gallery.database@example.com";
    private static final String ADMIN_PASSWORD = "local-gallery-database-password-123!";
    private static final String HASH = "b".repeat(64);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AdminUser admin;

    @BeforeEach
    void setUpFixture() {
        clearFixtures();
        admin = adminUserRepository.saveAndFlush(
                AdminUser.create(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    @AfterEach
    void clearFixtureAfterTest() {
        clearFixtures();
    }

    @Test
    void should_createV6GallerySchemaWithExactColumnsConstraintsAndIndex_when_flywayMigrates() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        var columns = jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'gallery_items'
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var constraints = jdbcTemplate.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'gallery_items'::regclass
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var timestampPrecisions = jdbcTemplate.query(
                        """
                        SELECT column_name, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'gallery_items'
                          AND column_name IN (
                              'performed_at', 'published_at', 'created_at', 'updated_at'
                          )
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("column_name"),
                                resultSet.getInt("datetime_precision")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'gallery_items'
                """,
                String.class));

        assertTrue(versions.containsAll(Set.of("1", "2", "3", "4", "5", "6")));
        assertEquals(
                Set.of(
                        "id",
                        "status",
                        "dog_name",
                        "breed_id",
                        "primary_service_id",
                        "cover_image_id",
                        "before_image_id",
                        "after_image_id",
                        "summary",
                        "alt_text",
                        "featured",
                        "sort_order",
                        "performed_at",
                        "published_at",
                        "created_at",
                        "updated_at",
                        "created_by",
                        "updated_by"),
                columns);
        assertTrue(constraints.containsAll(Set.of(
                "pk_gallery_items",
                "ck_gallery_items_status",
                "ck_gallery_items_dog_name_not_blank",
                "ck_gallery_items_summary_not_blank",
                "ck_gallery_items_alt_text_not_blank",
                "ck_gallery_items_sort_order",
                "ck_gallery_items_published_fields",
                "ck_gallery_items_before_after_distinct",
                "fk_gallery_items_breed",
                "fk_gallery_items_primary_service",
                "fk_gallery_items_cover_image",
                "fk_gallery_items_before_image",
                "fk_gallery_items_after_image",
                "fk_gallery_items_created_by",
                "fk_gallery_items_updated_by")));
        assertEquals(
                Map.of(
                        "performed_at", 6,
                        "published_at", 6,
                        "created_at", 6,
                        "updated_at", 6),
                timestampPrecisions);
        assertTrue(indexes.contains("ix_gallery_items_admin_order"));
    }

    @Test
    void should_applyDraftFeaturedSortAndAuditDefaults_when_databaseInsertOmitsDefaults() {
        var id = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO gallery_items (id, created_by, updated_by)
                VALUES (?, ?, ?)
                """,
                id,
                admin.getId(),
                admin.getId());

        var defaults = jdbcTemplate.queryForMap(
                """
                SELECT status, featured, sort_order, created_at = updated_at AS audit_equal
                FROM gallery_items
                WHERE id = ?
                """,
                id);
        assertEquals("draft", defaults.get("status"));
        assertEquals(false, defaults.get("featured"));
        assertEquals(100, defaults.get("sort_order"));
        assertEquals(true, defaults.get("audit_equal"));
    }

    @Test
    void should_enforceScalarAndPublishedInvariants_when_applicationValidationIsBypassed() {
        var breed = insertBreed("published", "gallery-db-breed");
        var service = insertService("published", "gallery-db-service");
        var cover = insertMedia("active");
        var before = insertMedia("active");

        assertInvalid(row().withStatus("deleted"));
        assertInvalid(row().withSortOrder(-1));
        assertInvalid(row().withDogName("\t\n"));
        assertInvalid(row().withSummary("\t\n"));
        assertInvalid(row().withAltText("\t\n"));

        var published = row()
                .withStatus("published")
                .withBreedId(breed)
                .withServiceId(service)
                .withCoverImageId(cover)
                .withAltText("미용 완료 사진")
                .withPublishedAt(OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        assertInvalid(published.withBreedId(null));
        assertInvalid(published.withServiceId(null));
        assertInvalid(published.withCoverImageId(null));
        assertInvalid(published.withAltText(null));
        assertInvalid(published.withAltText("\t\n"));
        assertInvalid(published.withPublishedAt(null));
        assertInvalid(row().withBeforeImageId(before).withAfterImageId(before));
    }

    @Test
    void should_enforceEveryRelationAndActorForeignKey_when_applicationValidationIsBypassed() {
        var missing = UUID.randomUUID();

        assertInvalid(row().withBreedId(missing));
        assertInvalid(row().withServiceId(missing));
        assertInvalid(row().withCoverImageId(missing));
        assertInvalid(row().withBeforeImageId(missing));
        assertInvalid(row().withAfterImageId(missing));
        assertInvalid(row().withCreatedBy(missing));
        assertInvalid(row().withUpdatedBy(missing));
    }

    @Test
    void should_restrictHardDeleteOfEveryReferencedTarget_when_galleryRelationExists() {
        var breed = insertBreed("published", "gallery-delete-breed");
        var service = insertService("published", "gallery-delete-service");
        var cover = insertMedia("active");
        var before = insertMedia("active");
        var after = insertMedia("active");
        insert(row()
                .withBreedId(breed)
                .withServiceId(service)
                .withCoverImageId(cover)
                .withBeforeImageId(before)
                .withAfterImageId(after));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM breeds WHERE id = ?", breed));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM services WHERE id = ?", service));
        for (var mediaId : new UUID[] {cover, before, after}) {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", mediaId));
        }
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM admin_users WHERE id = ?", admin.getId()));
    }

    private void assertInvalid(GalleryRow row) {
        assertThrows(DataIntegrityViolationException.class, () -> insert(row));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gallery_items", Integer.class));
    }

    private GalleryRow row() {
        return new GalleryRow(
                UUID.randomUUID(),
                "draft",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                100,
                null,
                null,
                admin.getId(),
                admin.getId());
    }

    private void insert(GalleryRow row) {
        jdbcTemplate.update(
                """
                INSERT INTO gallery_items (
                    id, status, dog_name, breed_id, primary_service_id,
                    cover_image_id, before_image_id, after_image_id,
                    summary, alt_text, featured, sort_order, performed_at, published_at,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                row.id(),
                row.status(),
                row.dogName(),
                row.breedId(),
                row.serviceId(),
                row.coverImageId(),
                row.beforeImageId(),
                row.afterImageId(),
                row.summary(),
                row.altText(),
                row.featured(),
                row.sortOrder(),
                row.performedAt(),
                row.publishedAt(),
                row.createdBy(),
                row.updatedBy());
    }

    private UUID insertBreed(String status, String slug) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO breeds (
                    id, status, name, slug, description, sort_order,
                    created_by, updated_by
                ) VALUES (?, ?, '테스트 견종', ?, NULL, 100, ?, ?)
                """,
                id,
                status,
                slug,
                admin.getId(),
                admin.getId());
        return id;
    }

    private UUID insertService(String status, String slug) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO services (
                    id, status, name, slug, description, price_text, sort_order,
                    created_by, updated_by
                ) VALUES (?, ?, '테스트 서비스', ?, '설명', '상담 후 안내', 100, ?, ?)
                """,
                id,
                status,
                slug,
                admin.getId(),
                admin.getId());
        return id;
    }

    private UUID insertMedia(String status) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (
                    id, status, source_content_type, content_type, file_extension,
                    storage_key, source_byte_size, byte_size, width, height, sha256,
                    created_by, updated_by
                ) VALUES (?, ?, 'image/jpeg', 'image/jpeg', 'jpg', ?,
                          100, 100, 4, 3, ?, ?, ?)
                """,
                id,
                status,
                "masters/" + id.toString().substring(0, 2) + "/" + id + ".jpg",
                HASH,
                admin.getId(),
                admin.getId());
        return id;
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM gallery_items");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private record GalleryRow(
            UUID id,
            String status,
            String dogName,
            UUID breedId,
            UUID serviceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            String summary,
            String altText,
            boolean featured,
            int sortOrder,
            OffsetDateTime performedAt,
            OffsetDateTime publishedAt,
            UUID createdBy,
            UUID updatedBy) {

        GalleryRow withStatus(String value) {
            return copy(value, dogName, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withDogName(String value) {
            return copy(status, value, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withBreedId(UUID value) {
            return copy(status, dogName, value, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withServiceId(UUID value) {
            return copy(status, dogName, breedId, value, coverImageId, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withCoverImageId(UUID value) {
            return copy(status, dogName, breedId, serviceId, value, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withBeforeImageId(UUID value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, value, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withAfterImageId(UUID value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, beforeImageId, value,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withSummary(String value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    value, altText, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withAltText(String value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, value, sortOrder, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withSortOrder(int value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, altText, value, performedAt, publishedAt, createdBy, updatedBy);
        }

        GalleryRow withPublishedAt(OffsetDateTime value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, value, createdBy, updatedBy);
        }

        GalleryRow withCreatedBy(UUID value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, value, updatedBy);
        }

        GalleryRow withUpdatedBy(UUID value) {
            return copy(status, dogName, breedId, serviceId, coverImageId, beforeImageId, afterImageId,
                    summary, altText, sortOrder, performedAt, publishedAt, createdBy, value);
        }

        private GalleryRow copy(
                String newStatus,
                String newDogName,
                UUID newBreedId,
                UUID newServiceId,
                UUID newCoverImageId,
                UUID newBeforeImageId,
                UUID newAfterImageId,
                String newSummary,
                String newAltText,
                int newSortOrder,
                OffsetDateTime newPerformedAt,
                OffsetDateTime newPublishedAt,
                UUID newCreatedBy,
                UUID newUpdatedBy) {
            return new GalleryRow(
                    id,
                    newStatus,
                    newDogName,
                    newBreedId,
                    newServiceId,
                    newCoverImageId,
                    newBeforeImageId,
                    newAfterImageId,
                    newSummary,
                    newAltText,
                    featured,
                    newSortOrder,
                    newPerformedAt,
                    newPublishedAt,
                    newCreatedBy,
                    newUpdatedBy);
        }
    }
}
