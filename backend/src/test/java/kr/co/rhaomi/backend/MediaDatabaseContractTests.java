package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class MediaDatabaseContractTests {

    private static final String ADMIN_EMAIL = "media.database@example.com";
    private static final String ADMIN_PASSWORD = "local-media-database-password-123!";
    private static final String HASH = "a".repeat(64);

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
    void should_createV5MediaSchemaWithExactColumnsAndNamedConstraints_when_flywayMigrates() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        var columns = jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'media_assets'
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var constraints = jdbcTemplate.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'media_assets'::regclass
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var timestampPrecisions = jdbcTemplate.query(
                        """
                        SELECT column_name, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'media_assets'
                          AND column_name IN ('created_at', 'updated_at')
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
                WHERE schemaname = 'public' AND tablename = 'media_assets'
                """,
                String.class));

        assertTrue(versions.containsAll(Set.of("1", "2", "3", "4", "5")));
        assertEquals(
                Set.of(
                        "id",
                        "status",
                        "source_content_type",
                        "content_type",
                        "file_extension",
                        "storage_key",
                        "source_byte_size",
                        "byte_size",
                        "width",
                        "height",
                        "sha256",
                        "created_at",
                        "updated_at",
                        "created_by",
                        "updated_by"),
                columns);
        assertTrue(constraints.containsAll(Set.of(
                "pk_media_assets",
                "uk_media_assets_storage_key",
                "ck_media_assets_status",
                "ck_media_assets_source_content_type",
                "ck_media_assets_content_type",
                "ck_media_assets_file_extension",
                "ck_media_assets_type_consistency",
                "ck_media_assets_source_byte_size",
                "ck_media_assets_byte_size",
                "ck_media_assets_width",
                "ck_media_assets_height",
                "ck_media_assets_total_pixels",
                "ck_media_assets_sha256",
                "ck_media_assets_storage_key",
                "fk_media_assets_created_by",
                "fk_media_assets_updated_by")));
        assertEquals(Map.of("created_at", 6, "updated_at", 6), timestampPrecisions);
        assertTrue(indexes.contains("ix_media_assets_admin_order"));
    }

    @Test
    void should_applyActiveAndAuditDefaultsAndAllowDuplicateHash_when_validRowsAreInserted() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        insertWithDefaults(first, admin.getId());
        insert(
                second,
                "active",
                "image/jpeg",
                "image/jpeg",
                "jpg",
                100,
                100,
                4,
                3,
                HASH,
                storageKey(second, "jpg"),
                admin.getId(),
                admin.getId());

        assertEquals("active", jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, first));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT created_at = updated_at FROM media_assets WHERE id = ?", Boolean.class, first));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_assets WHERE sha256 = ?", Integer.class, HASH));
    }

    @Test
    void should_enforceEveryMediaInvariant_when_applicationValidationIsBypassed() {
        var validId = UUID.randomUUID();
        var validKey = storageKey(validId, "jpg");
        insert(
                validId,
                "active",
                "image/jpeg",
                "image/jpeg",
                "jpg",
                100,
                100,
                4,
                3,
                HASH,
                validKey,
                admin.getId(),
                admin.getId());

        var invalidRows = new java.util.ArrayList<InvalidRow>();
        invalidRows.add(row().withStatus("deleted"));
        invalidRows.add(row().withSourceContentType("image/gif"));
        invalidRows.add(row().withContentType("image/webp"));
        invalidRows.add(row().withFileExtension("jpeg"));
        invalidRows.add(row().withSourceContentType("image/png"));
        invalidRows.add(row().withSourceByteSize(0));
        invalidRows.add(row().withSourceByteSize(20L * 1024 * 1024 + 1));
        invalidRows.add(row().withByteSize(0));
        invalidRows.add(row().withByteSize(30L * 1024 * 1024 + 1));
        invalidRows.add(row().withWidth(0));
        invalidRows.add(row().withWidth(12001));
        invalidRows.add(row().withHeight(0));
        invalidRows.add(row().withHeight(12001));
        invalidRows.add(row().withDimensions(10000, 7000));
        invalidRows.add(row().withSha256("A".repeat(64)));
        invalidRows.add(row().withSha256("a".repeat(63)));
        invalidRows.add(row().withStorageKey("masters/../outside.jpg"));
        invalidRows.add(row().withCreatedBy(UUID.randomUUID()));
        invalidRows.add(row().withUpdatedBy(UUID.randomUUID()));
        invalidRows.add(row().withStorageKey(validKey));

        for (var invalid : invalidRows) {
            assertThrows(DataIntegrityViolationException.class, () -> insert(invalid));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM media_assets", Integer.class));
        }
    }

    private InvalidRow row() {
        var id = UUID.randomUUID();
        return new InvalidRow(
                id,
                "active",
                "image/jpeg",
                "image/jpeg",
                "jpg",
                100,
                100,
                4,
                3,
                HASH,
                storageKey(id, "jpg"),
                admin.getId(),
                admin.getId());
    }

    private void insert(InvalidRow row) {
        insert(
                row.id(),
                row.status(),
                row.sourceContentType(),
                row.contentType(),
                row.fileExtension(),
                row.sourceByteSize(),
                row.byteSize(),
                row.width(),
                row.height(),
                row.sha256(),
                row.storageKey(),
                row.createdBy(),
                row.updatedBy());
    }

    private void insertWithDefaults(UUID id, UUID actorId) {
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (
                    id, source_content_type, content_type, file_extension, storage_key,
                    source_byte_size, byte_size, width, height, sha256, created_by, updated_by
                ) VALUES (?, 'image/jpeg', 'image/jpeg', 'jpg', ?,
                          100, 100, 4, 3, ?, ?, ?)
                """,
                id,
                storageKey(id, "jpg"),
                HASH,
                actorId,
                actorId);
    }

    private void insert(
            UUID id,
            String status,
            String sourceContentType,
            String contentType,
            String fileExtension,
            long sourceByteSize,
            long byteSize,
            int width,
            int height,
            String sha256,
            String storageKey,
            UUID createdBy,
            UUID updatedBy) {
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (
                    id, status, source_content_type, content_type, file_extension, storage_key,
                    source_byte_size, byte_size, width, height, sha256,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                sourceContentType,
                contentType,
                fileExtension,
                storageKey,
                sourceByteSize,
                byteSize,
                width,
                height,
                sha256,
                createdBy,
                updatedBy);
    }

    private String storageKey(UUID id, String extension) {
        return "masters/" + id.toString().substring(0, 2) + "/" + id + "." + extension;
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM media_assets");
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private record InvalidRow(
            UUID id,
            String status,
            String sourceContentType,
            String contentType,
            String fileExtension,
            long sourceByteSize,
            long byteSize,
            int width,
            int height,
            String sha256,
            String storageKey,
            UUID createdBy,
            UUID updatedBy) {

        InvalidRow withStatus(String value) {
            return new InvalidRow(
                    id, value, sourceContentType, contentType, fileExtension, sourceByteSize,
                    byteSize, width, height, sha256, storageKey, createdBy, updatedBy);
        }

        InvalidRow withSourceContentType(String value) {
            return new InvalidRow(
                    id, status, value, contentType, fileExtension, sourceByteSize, byteSize,
                    width, height, sha256, storageKey, createdBy, updatedBy);
        }

        InvalidRow withContentType(String value) {
            return new InvalidRow(
                    id, status, sourceContentType, value, fileExtension, sourceByteSize,
                    byteSize, width, height, sha256, storageKey, createdBy, updatedBy);
        }

        InvalidRow withFileExtension(String value) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, value, sourceByteSize,
                    byteSize, width, height, sha256, storageKey, createdBy, updatedBy);
        }

        InvalidRow withSourceByteSize(long value) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, fileExtension, value, byteSize,
                    width, height, sha256, storageKey, createdBy, updatedBy);
        }

        InvalidRow withByteSize(long value) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, fileExtension, sourceByteSize,
                    value, width, height, sha256, storageKey, createdBy, updatedBy);
        }

        InvalidRow withWidth(int value) {
            return withDimensions(value, height);
        }

        InvalidRow withHeight(int value) {
            return withDimensions(width, value);
        }

        InvalidRow withDimensions(int newWidth, int newHeight) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, fileExtension, sourceByteSize,
                    byteSize, newWidth, newHeight, sha256, storageKey, createdBy, updatedBy);
        }

        InvalidRow withSha256(String value) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, fileExtension, sourceByteSize,
                    byteSize, width, height, value, storageKey, createdBy, updatedBy);
        }

        InvalidRow withStorageKey(String value) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, fileExtension, sourceByteSize,
                    byteSize, width, height, sha256, value, createdBy, updatedBy);
        }

        InvalidRow withCreatedBy(UUID value) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, fileExtension, sourceByteSize,
                    byteSize, width, height, sha256, storageKey, value, updatedBy);
        }

        InvalidRow withUpdatedBy(UUID value) {
            return new InvalidRow(
                    id, status, sourceContentType, contentType, fileExtension, sourceByteSize,
                    byteSize, width, height, sha256, storageKey, createdBy, value);
        }
    }
}
