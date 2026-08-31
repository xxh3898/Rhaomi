import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

test("개발 Compose를 same-origin gateway, backend와 비공개 PostgreSQL로 제한한다", async () => {
  const [compose, backendDockerfile] = await Promise.all([
    source("compose.dev.yaml"),
    source("backend/Dockerfile.dev"),
  ]);

  assert.match(compose, /backend:\n/);
  assert.match(compose, /postgres:\n/);
  assert.match(compose, /127\.0\.0\.1:8080:8080/);
  assert.match(compose, /127\.0\.0\.1:3000:3000/);
  assert.equal((compose.match(/@sha256:/g) ?? []).length, 5);
  assert.match(compose, /nginx:1\.31\.4-alpine3\.24@sha256:/);
  assert.match(
    backendDockerfile,
    /eclipse-temurin:25\.0\.4_7-jdk-alpine-3\.23@sha256:b7c88ce22d575642650ec83cbf4e470a0c183a46871467180238e4b27ad9e20a/,
  );
  assert.match(backendDockerfile, /libheif=\$\{LIBHEIF_VERSION\}/);
  assert.doesNotMatch(compose, /8055|DIRECTUS_|directus/i);

  const postgresBlock = compose.match(/\n  postgres:\n([\s\S]*?)\n  smoke:/)?.[1] ?? "";
  assert.doesNotMatch(postgresBlock, /\n    ports:/);
});

test("Java 25, Spring Boot와 Gradle Wrapper 버전을 고정한다", async () => {
  const build = await source("backend/build.gradle");
  const wrapper = await source("backend/gradle/wrapper/gradle-wrapper.properties");

  assert.match(build, /org\.springframework\.boot' version '4\.1\.1'/);
  assert.match(build, /JavaLanguageVersion\.of\(25\)/);
  assert.match(build, /RHAOMI_TEST_DATABASE_ALLOWED/);
  assert.match(wrapper, /gradle-9\.7\.1-bin\.zip/);
  assert.doesNotMatch(build, /websocket|oauth2-resource-server|jose|jwt/i);
});

test("Flyway와 Spring Security의 fail-closed 계약을 보존한다", async () => {
  const application = await source("backend/src/main/resources/application.yml");
  const migration = await source(
    "backend/src/main/resources/db/migration/V1__create_admin_users.sql",
  );
  const security = await source(
    "backend/src/main/java/kr/co/rhaomi/backend/config/SecurityConfig.java",
  );

  assert.match(application, /ddl-auto: validate/);
  assert.match(application, /http-only: true/);
  assert.match(application, /same-site: lax/);
  assert.match(migration, /CREATE TABLE admin_users/);
  assert.match(migration, /UNIQUE \(email\)/);
  assert.match(security, /csrfTokenRepository/);
  assert.match(security, /requestMatchers\("\/actuator\/\*\*"\)[\s\S]*?\.denyAll\(\)/);
  assert.match(security, /requestMatchers\("\/api\/admin\/\*\*"\)/);
  assert.match(security, /requestMatchers\("\/api\/\*\*"\)[\s\S]*?\.denyAll\(\)/);
  assert.doesNotMatch(security, /csrf\([^)]*disable/);
});

test("견종·서비스 V2와 제한된 관리자 API 계약을 고정한다", async () => {
  const [application, migration, breedController, serviceController] = await Promise.all([
    source("backend/src/main/resources/application.yml"),
    source("backend/src/main/resources/db/migration/V2__create_breeds_and_services.sql"),
    source("backend/src/main/java/kr/co/rhaomi/backend/breed/BreedAdminController.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/service/ServiceAdminController.java"),
  ]);

  assert.match(application, /fail-on-unknown-properties: true/);
  assert.match(migration, /CREATE TABLE breeds/);
  assert.match(migration, /CREATE TABLE services/);
  assert.match(migration, /CONSTRAINT uk_breeds_slug UNIQUE/);
  assert.match(migration, /CONSTRAINT uk_services_slug UNIQUE/);
  assert.match(migration, /CONSTRAINT ck_services_published_fields CHECK/);
  assert.match(migration, /REFERENCES admin_users\(id\) ON DELETE RESTRICT/);

  for (const controller of [breedController, serviceController]) {
    assert.match(controller, /@GetMapping/);
    assert.match(controller, /@PostMapping/);
    assert.match(controller, /@PutMapping/);
    assert.doesNotMatch(controller, /@(?:Patch|Delete)Mapping/);
  }
});

test("공지 V3와 게시·만료 관리자 API 계약을 고정한다", async () => {
  const [migration, controller, repository] = await Promise.all([
    source("backend/src/main/resources/db/migration/V3__create_notices.sql"),
    source("backend/src/main/java/kr/co/rhaomi/backend/notice/NoticeAdminController.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/notice/NoticeRepository.java"),
  ]);

  assert.match(migration, /CREATE TABLE notices/);
  assert.match(migration, /CONSTRAINT uk_notices_slug UNIQUE/);
  assert.match(migration, /CONSTRAINT ck_notices_published_fields CHECK/);
  assert.match(migration, /CONSTRAINT ck_notices_window CHECK/);
  assert.match(migration, /published_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /expires_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /created_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /updated_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /title ~ '\[\^\[:space:\]\]'/);
  assert.match(migration, /body_markdown ~ '\[\^\[:space:\]\]'/);
  assert.match(migration, /REFERENCES admin_users\(id\) ON DELETE RESTRICT/);
  assert.match(controller, /@GetMapping/);
  assert.match(controller, /@PostMapping/);
  assert.match(controller, /@PutMapping/);
  assert.doesNotMatch(controller, /@(?:Patch|Delete)Mapping/);
  assert.match(repository, /publishedAt DESC NULLS LAST/);
  assert.match(repository, /updatedAt DESC[\s\S]*?id ASC/);
});

test("매장정보 V4·V7과 private media relation 관리자 API 계약을 고정한다", async () => {
  const [migration, relationMigration, controller, service, response] = await Promise.all([
    source("backend/src/main/resources/db/migration/V4__create_shop_settings.sql"),
    source(
      "backend/src/main/resources/db/migration/V7__add_shop_settings_media_relations.sql",
    ),
    source("backend/src/main/java/kr/co/rhaomi/backend/shop/ShopSettingsAdminController.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/shop/ShopSettingsAdminService.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/shop/ShopSettingsResponse.java"),
  ]);

  assert.match(migration, /CREATE TABLE shop_settings/);
  assert.match(migration, /singleton_key BOOLEAN NOT NULL DEFAULT TRUE/);
  assert.match(migration, /CONSTRAINT uk_shop_settings_singleton_key UNIQUE/);
  assert.match(migration, /CONSTRAINT ck_shop_settings_singleton_key CHECK/);
  assert.match(migration, /opening_time TIME\(0\) WITHOUT TIME ZONE/);
  assert.match(migration, /closing_time TIME\(0\) WITHOUT TIME ZONE/);
  assert.match(migration, /created_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /updated_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /shop_name ~ '\[\^\[:space:\]\]'/);
  assert.match(migration, /address ~ '\[\^\[:space:\]\]'/);
  assert.match(migration, /REFERENCES admin_users\(id\) ON DELETE RESTRICT/);
  assert.match(relationMigration, /ADD COLUMN hero_image_id UUID/);
  assert.match(relationMigration, /ADD COLUMN hero_image_alt_text VARCHAR\(300\)/);
  assert.match(relationMigration, /ADD COLUMN groomer_image_id UUID/);
  assert.match(relationMigration, /ADD COLUMN groomer_image_alt_text VARCHAR\(300\)/);
  assert.match(relationMigration, /ADD COLUMN og_image_id UUID/);
  assert.equal(
    (relationMigration.match(/REFERENCES media_assets\(id\) ON DELETE RESTRICT/g) ?? [])
      .length,
    3,
  );
  assert.match(relationMigration, /CONSTRAINT ck_shop_settings_hero_image_alt_pair CHECK/);
  assert.match(
    relationMigration,
    /CONSTRAINT ck_shop_settings_groomer_image_alt_pair CHECK/,
  );
  assert.match(controller, /@RequestMapping\("\/api\/admin\/shop-settings"\)/);
  assert.match(controller, /@GetMapping/);
  assert.match(controller, /@PutMapping/);
  assert.doesNotMatch(controller, /@(?:Post|Patch|Delete)Mapping/);
  assert.match(service, /MediaAssetRepository/);
  assert.match(service, /MediaStatus\.ACTIVE/);
  assert.doesNotMatch(response, /\bUUID id\b|singletonKey|\bstatus\b/);
  assert.doesNotMatch(response, /storageKey|fileExtension|sha256|originalFilename/);
});

test("미디어 V5와 private HEIC 정규화 계약을 고정한다", async () => {
  const [
    application,
    build,
    migration,
    controller,
    response,
    compose,
    workflow,
    smoke,
  ] = await Promise.all([
    source("backend/src/main/resources/application.yml"),
    source("backend/build.gradle"),
    source("backend/src/main/resources/db/migration/V5__create_media_assets.sql"),
    source("backend/src/main/java/kr/co/rhaomi/backend/media/MediaAdminController.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/media/MediaResponse.java"),
    source("compose.dev.yaml"),
    source(".github/workflows/validate.yml"),
    source("scripts/validate-backend-media.mjs"),
  ]);

  assert.match(application, /max-file-size: 20MB/);
  assert.match(application, /max-source-bytes: 20971520/);
  assert.match(application, /max-stored-bytes: 31457280/);
  assert.match(application, /max-pixels: 60000000/);
  assert.match(application, /jpeg-quality: 92/);
  assert.match(build, /imageio-heif:1\.1\.0/);
  assert.match(build, /--enable-native-access=ALL-UNNAMED/);

  assert.match(migration, /CREATE TABLE media_assets/);
  assert.match(migration, /source_content_type IN \('image\/jpeg', 'image\/png', 'image\/heic', 'image\/heif'\)/);
  assert.match(migration, /source_byte_size > 0 AND source_byte_size <= 20971520/);
  assert.match(migration, /byte_size > 0 AND byte_size <= 31457280/);
  assert.match(migration, /width::BIGINT \* height::BIGINT <= 60000000/);
  assert.match(migration, /created_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /updated_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /ON DELETE RESTRICT/);

  assert.match(controller, /@RequestMapping\("\/api\/admin\/media"\)/);
  assert.match(controller, /@GetMapping/);
  assert.match(controller, /@PostMapping/);
  assert.match(controller, /@PutMapping/);
  assert.doesNotMatch(controller, /@(?:Patch|Delete)Mapping/);
  assert.doesNotMatch(response, /storageKey|originalFilename|sha256|fileExtension/);

  const frontendBlock = compose.match(/\n  frontend:\n([\s\S]*?)\n  gateway:/)?.[1] ?? "";
  const backendBlock = compose.match(/\n  backend:\n([\s\S]*?)\n  postgres:/)?.[1] ?? "";
  const smokeBlock = compose.match(/\n  smoke:\n([\s\S]*?)\nvolumes:/)?.[1] ?? "";
  assert.match(backendBlock, /backend-media-masters:\/var\/lib\/rhaomi\/media/);
  assert.doesNotMatch(frontendBlock, /backend-media-masters|\/var\/lib\/rhaomi\/media/);
  assert.doesNotMatch(smokeBlock, /backend-media-masters|\/var\/lib\/rhaomi\/media/);
  assert.match(workflow, /docker build[\s\S]*?backend\/Dockerfile\.dev/);
  assert.match(workflow, /RHAOMI_MEDIA_ROOT=\/tmp\/rhaomi-media-ci/);
  assert.match(smoke, /synthetic-orientation-metadata\.heic/);
  assert.match(smoke, /sourceContentType, "image\/heic"/);
  assert.match(smoke, /cache-control/);
});

test("갤러리 V6와 관계 상태 검증 관리자 API 계약을 고정한다", async () => {
  const [migration, controller, service, repository, response] = await Promise.all([
    source("backend/src/main/resources/db/migration/V6__create_gallery_items.sql"),
    source("backend/src/main/java/kr/co/rhaomi/backend/gallery/GalleryAdminController.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/gallery/GalleryAdminService.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/gallery/GalleryRepository.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/gallery/GalleryResponse.java"),
  ]);

  assert.match(migration, /CREATE TABLE gallery_items/);
  assert.match(migration, /CONSTRAINT ck_gallery_items_published_fields CHECK/);
  assert.match(migration, /CONSTRAINT ck_gallery_items_before_after_distinct CHECK/);
  assert.match(migration, /dog_name ~ '\[\^\[:space:\]\]'/);
  assert.match(migration, /summary ~ '\[\^\[:space:\]\]'/);
  assert.match(migration, /alt_text ~ '\[\^\[:space:\]\]'/);
  assert.match(migration, /performed_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /published_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.equal((migration.match(/REFERENCES media_assets\(id\) ON DELETE RESTRICT/g) ?? []).length, 3);
  assert.match(migration, /REFERENCES breeds\(id\) ON DELETE RESTRICT/);
  assert.match(migration, /REFERENCES services\(id\) ON DELETE RESTRICT/);

  assert.match(controller, /@RequestMapping\("\/api\/admin\/gallery-items"\)/);
  assert.match(controller, /@GetMapping/);
  assert.match(controller, /@PostMapping/);
  assert.match(controller, /@PutMapping/);
  assert.doesNotMatch(controller, /@(?:Patch|Delete)Mapping/);
  assert.match(service, /ContentStatus\.PUBLISHED/);
  assert.match(service, /MediaStatus\.ACTIVE/);
  assert.match(service, /validateRelations\(values\)[\s\S]*?item\.update/);
  assert.match(repository, /featured DESC/);
  assert.match(repository, /sortOrder ASC/);
  assert.match(repository, /publishedAt DESC NULLS LAST/);
  assert.match(repository, /id ASC/);
  assert.match(response, /UUID breedId/);
  assert.match(response, /UUID primaryServiceId/);
  assert.match(response, /UUID coverImageId/);
  assert.doesNotMatch(response, /storageKey|sha256|MediaAsset|\bBreed\b|GroomingService/);
});

test("V8 transactional revision과 typed publishing outbox producer를 고정한다", async () => {
  const [migration, recorder, eventKinds, noticeService, galleryService, mediaService] =
    await Promise.all([
      source(
        "backend/src/main/resources/db/migration/V8__create_content_revision_and_publishing_outbox.sql",
      ),
      source(
        "backend/src/main/java/kr/co/rhaomi/backend/publication/PublicationRecorder.java",
      ),
      source(
        "backend/src/main/java/kr/co/rhaomi/backend/publication/PublicationEventKind.java",
      ),
      source("backend/src/main/java/kr/co/rhaomi/backend/notice/NoticeAdminService.java"),
      source("backend/src/main/java/kr/co/rhaomi/backend/gallery/GalleryAdminService.java"),
      source("backend/src/main/java/kr/co/rhaomi/backend/media/MediaAdminService.java"),
    ]);

  assert.match(migration, /CREATE TABLE content_revision_state/);
  assert.match(migration, /CREATE TABLE publishing_outbox/);
  assert.match(migration, /content_revision BIGINT NOT NULL/);
  assert.match(migration, /available_at TIMESTAMP\(6\) WITH TIME ZONE NOT NULL/);
  assert.match(migration, /expected_boundary_at TIMESTAMP\(6\) WITH TIME ZONE/);
  assert.match(migration, /CONSTRAINT ck_publishing_outbox_boundary CHECK/);
  assert.match(migration, /CONSTRAINT ck_publishing_outbox_source_kind CHECK/);
  assert.match(migration, /ON publishing_outbox \(available_at, id\)/);
  assert.match(migration, /ON publishing_outbox \(content_revision\)/);
  assert.doesNotMatch(migration, /CREATE SEQUENCE|processing|claim_owner|publish_generation/i);

  assert.match(recorder, /Propagation\.MANDATORY/);
  assert.match(
    recorder,
    /SET content_revision = content_revision \+ 1[\s\S]*?RETURNING content_revision/,
  );
  assert.match(recorder, /CURRENT_TIMESTAMP, NULL/);
  assert.doesNotMatch(recorder, /EventListener|Controller/);
  assert.match(eventKinds, /NOTICE_PUBLISHED_AT_DUE/);
  assert.match(eventKinds, /NOTICE_EXPIRES_AT_DUE/);
  assert.match(eventKinds, /GALLERY_PUBLISHED_AT_DUE/);
  assert.match(noticeService, /changedBoundaries\(beforePublishedAt, beforeExpiresAt, saved\)/);
  assert.match(galleryService, /GALLERY_PUBLISHED_AT_DUE/);
  assert.match(
    mediaService,
    /mediaAssetRepository\.saveAndFlush[\s\S]*?publicationRecorder\.record/,
  );
});

test("V9 pending claim·lease·publishGeneration 상태 머신 기반을 고정한다", async () => {
  const [migration, service, state, resultCode] = await Promise.all([
    source(
      "backend/src/main/resources/db/migration/V9__add_publishing_claim_and_generation_state.sql",
    ),
    source(
      "backend/src/main/java/kr/co/rhaomi/backend/publication/PublicationStateService.java",
    ),
    source(
      "backend/src/main/java/kr/co/rhaomi/backend/publication/PublicationState.java",
    ),
    source(
      "backend/src/main/java/kr/co/rhaomi/backend/publication/PublicationResultCode.java",
    ),
  ]);

  assert.match(migration, /CREATE TABLE publish_generation_state/);
  assert.match(migration, /state VARCHAR\(16\) NOT NULL DEFAULT 'PENDING'/);
  assert.match(migration, /publish_generation BIGINT/);
  assert.match(migration, /attempt_count SMALLINT NOT NULL DEFAULT 0/);
  assert.match(migration, /CONSTRAINT ck_publishing_outbox_state_shape CHECK/);
  assert.match(migration, /UNIQUE \(publish_generation\)/);
  assert.match(migration, /FOREIGN KEY \(coalesced_into_generation\)/);
  assert.match(migration, /ON publishing_outbox \(state, available_at, id\)/);
  assert.match(migration, /ON publishing_outbox \(state, next_attempt_at, id\)/);
  assert.match(migration, /ON publishing_outbox \(state, lease_until, id\)/);
  assert.doesNotMatch(migration, /CREATE SEQUENCE|raw_exception|error_message/i);

  assert.match(
    service,
    /SET publish_generation = publish_generation \+ 1[\s\S]*?RETURNING publish_generation/,
  );
  assert.match(service, /FOR UPDATE SKIP LOCKED/);
  assert.match(service, /available_at <= \?/);
  assert.match(service, /next_attempt_at <= \?/);
  assert.match(service, /lease_until <= \?/);
  assert.match(service, /Duration\.ofMinutes\(1\)/);
  assert.match(service, /Duration\.ofMinutes\(5\)/);
  assert.match(service, /Duration\.ofMinutes\(15\)/);
  assert.doesNotMatch(
    service,
    /@Scheduled|@(?:Rest)?Controller|EventListener|ExecutorService|TaskExecutor/,
  );

  for (const value of [
    "PENDING",
    "PROCESSING",
    "RETRY_WAIT",
    "SUCCEEDED",
    "NOOP",
    "FAILED",
    "COALESCED",
  ]) {
    assert.match(state, new RegExp(`\\b${value}\\b`));
  }
  for (const value of [
    "SUCCESS",
    "STALE_TRIGGER",
    "NO_PUBLIC_CHANGE",
    "TRANSIENT_FAILURE",
    "RETRY_EXHAUSTED",
    "TERMINAL_FAILURE",
    "COALESCED",
    "LEASE_EXPIRED",
  ]) {
    assert.match(resultCode, new RegExp(`\\b${value}\\b`));
  }
});

test("internal read-only build API와 service credential 경계를 고정한다", async () => {
  const [
    application,
    compose,
    environmentExample,
    buildSecurity,
    buildProperties,
    buildFilter,
    snapshotService,
    mediaService,
    snapshotResponse,
    nginx,
  ] = await Promise.all([
    source("backend/src/main/resources/application.yml"),
    source("compose.dev.yaml"),
    source(".env.example"),
    source("backend/src/main/java/kr/co/rhaomi/backend/build/BuildSecurityConfig.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/build/BuildServiceProperties.java"),
    source(
      "backend/src/main/java/kr/co/rhaomi/backend/build/BuildServiceAuthenticationFilter.java",
    ),
    source("backend/src/main/java/kr/co/rhaomi/backend/build/BuildSnapshotService.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/build/BuildMediaService.java"),
    source("backend/src/main/java/kr/co/rhaomi/backend/build/BuildSnapshotResponse.java"),
    source("infra/nginx/dev.conf"),
  ]);

  assert.match(application, /build-service:\s*\n\s+token: \$\{RHAOMI_BUILD_SERVICE_TOKEN:\}/);
  assert.match(environmentExample, /RHAOMI_BUILD_SERVICE_TOKEN=/);
  assert.match(environmentExample, /openssl rand -hex 32/);
  assert.match(buildSecurity, /securityMatcher\("\/api\/build\/\*\*"\)/);
  assert.match(buildSecurity, /SessionCreationPolicy\.STATELESS/);
  assert.match(buildSecurity, /NullSecurityContextRepository/);
  assert.match(buildSecurity, /HttpMethod\.GET/);
  assert.match(buildSecurity, /\.anyRequest\(\)\s*\.denyAll\(\)/);
  assert.match(buildProperties, /\^\[0-9a-f\]\{64\}\$/);
  assert.match(buildProperties, /MessageDigest\.isEqual/);
  assert.doesNotMatch(buildProperties, /String token\(\)|getToken/);
  assert.doesNotMatch(buildFilter, /@Component/);
  assert.match(
    snapshotService,
    /@Transactional\(readOnly = true, isolation = Isolation\.REPEATABLE_READ\)/,
  );
  assert.match(mediaService, /MediaStorage/);
  assert.match(snapshotService, /SCHEMA_VERSION = 2/);
  assert.match(snapshotService, /Long\.toString\(contentRevision\)/);
  assert.match(snapshotService, /Long\.toString\(publishGeneration\)/);
  assert.match(snapshotResponse, /String contentRevision/);
  assert.match(snapshotResponse, /String publishGeneration/);
  assert.doesNotMatch(
    snapshotResponse,
    /createdBy|updatedBy|storageKey|fileExtension|sha256|sourceContentType|claimOwner|leaseUntil|eventId/,
  );
  assert.match(nginx, /location \^~ \/api\/build\/\s*\{\s*return 404;/);
  assert(
    nginx.indexOf("location ^~ /api/build/") < nginx.indexOf("location ^~ /api/ {"),
  );

  const frontendBlock = compose.match(/\n  frontend:\n([\s\S]*?)\n  gateway:/)?.[1] ?? "";
  const backendBlock = compose.match(/\n  backend:\n([\s\S]*?)\n  postgres:/)?.[1] ?? "";
  assert.doesNotMatch(frontendBlock, /RHAOMI_BUILD_SERVICE_TOKEN|NEXT_PUBLIC/i);
  assert.match(backendBlock, /RHAOMI_BUILD_SERVICE_TOKEN/);
});

test("frontend runtime과 dependency에서 local credential filesystem을 격리한다", async () => {
  const [compose, environmentExample, composeSmoke, isolationCheck] = await Promise.all([
    source("compose.dev.yaml"),
    source(".env.example"),
    source("scripts/validate-backend-compose.sh"),
    source("scripts/validate-frontend-credential-isolation.mjs"),
  ]);

  const frontendBlock = compose.match(/\n  frontend:\n([\s\S]*?)\n  gateway:/)?.[1] ?? "";
  const gatewayBlock = compose.match(/\n  gateway:\n([\s\S]*?)\n  backend:/)?.[1] ?? "";
  const backendBlock = compose.match(/\n  backend:\n([\s\S]*?)\n  postgres:/)?.[1] ?? "";
  const contractCheckBlock =
    compose.match(/\n  contract-check:\n([\s\S]*?)\n  publisher-validation:/)?.[1] ?? "";

  assert.doesNotMatch(frontendBlock, /- \.:\/workspace(?:\s|$)/);
  assert.doesNotMatch(frontendBlock, /\.env|\.\/backend|RHAOMI_BUILD_SERVICE_TOKEN/i);
  assert.match(frontendBlock, /\.\/src:\/workspace\/src:ro/);
  assert.match(frontendBlock, /\.\/package\.json:\/workspace\/package\.json:ro/);
  assert.match(
    frontendBlock,
    /validate-frontend-credential-isolation\.mjs:\/workspace\/scripts\/validate-frontend-credential-isolation\.mjs:ro/,
  );
  assert.doesNotMatch(gatewayBlock, /RHAOMI_BUILD_SERVICE_TOKEN|\.env/i);
  assert.match(backendBlock, /RHAOMI_BUILD_SERVICE_TOKEN/);

  assert.match(contractCheckBlock, /profiles: \["validation"\]/);
  assert.match(contractCheckBlock, /network_mode: "none"/);
  assert.match(contractCheckBlock, /\.\/backend\/src:\/workspace\/backend\/src:ro/);
  assert.match(contractCheckBlock, /\.\/tests:\/workspace\/tests:ro/);
  assert.doesNotMatch(contractCheckBlock, /- \.:\/workspace(?:\s|$)|\.env\.dev\.local/);
  assert.doesNotMatch(contractCheckBlock, /RHAOMI_BUILD_SERVICE_TOKEN|NEXT_PUBLIC/i);

  assert.match(environmentExample, /frontend container에는 이 파일을 mount하지 않습니다/);
  assert.match(composeSmoke, /backend_token_digest/);
  assert.match(composeSmoke, /validate-frontend-credential-isolation\.mjs/);
  assert.match(composeSmoke, /--header @-/);
  assert.match(isolationCheck, /Object\.hasOwn\(process\.env, "RHAOMI_BUILD_SERVICE_TOKEN"\)/);
  assert.match(isolationCheck, /entry\.name\.startsWith\("\.env"\)/);
  assert.match(isolationCheck, /createHash\("sha256"\)/);
  assert.doesNotMatch(isolationCheck, /console\.log\([^)]*match\[0\]/s);
});

test("build snapshot transformer dependency와 amd64/arm64 Compose smoke를 고정한다", async () => {
  const [packageJson, packageLock, composeSmoke, int64E2e, contracts, transformer] =
    await Promise.all([
      source("package.json"),
      source("package-lock.json"),
      source("scripts/validate-backend-compose.sh"),
      source("scripts/validate-build-int64-e2e.sh"),
      source("src/build-transformer/contracts.mts"),
      source("src/build-transformer/transformer.mts"),
    ]);

  const manifest = JSON.parse(packageJson);
  const lock = JSON.parse(packageLock);
  assert.equal(manifest.dependencies.sharp, "0.35.4");
  assert.equal(
    manifest.scripts["test:orchestration"],
    "vitest run src/build-orchestration",
  );
  assert.equal(manifest.scripts["test:transformer"], "vitest run src/build-transformer");
  assert.equal(lock.packages["node_modules/sharp"].version, "0.35.4");
  for (const platform of [
    "linux-x64",
    "linux-arm64",
    "linuxmusl-x64",
    "linuxmusl-arm64",
  ]) {
    assert.equal(
      lock.packages[`node_modules/@img/sharp-${platform}`].version,
      "0.35.4",
    );
    assert.equal(
      lock.packages[`node_modules/@img/sharp-libvips-${platform}`].version,
      "1.3.3",
    );
  }
  assert.match(composeSmoke, /architecture=\$\(uname -m\)/);
  assert.match(composeSmoke, /x86_64 \| aarch64/);
  assert.match(composeSmoke, /npm run test:orchestration/);
  assert.match(composeSmoke, /npm run test:transformer/);
  assert.match(composeSmoke, /validate-build-int64-e2e\.sh/);
  assert.match(int64E2e, /9007199254740993/);
  assert.match(int64E2e, /9223372036854775807/);
  assert.match(int64E2e, /RHAOMI_INT64_NODE_MODULES_VOLUME/);
  assert.match(int64E2e, /RHAOMI_INT64_GRADLE_CACHE_VOLUME/);
  assert.match(int64E2e, /RHAOMI_CLEANUP_TASK/);
  assert.match(int64E2e, /docker volume inspect/);
  assert.match(int64E2e, /io\.homeserver\.cleanup\.task=\$cleanup_task/);
  assert.doesNotMatch(int64E2e, /docker (?:volume rm|system prune|volume prune)/);
  assert.match(contracts, /export type BuildSnapshotV2/);
  assert.match(contracts, /schemaVersion: 2/);
  assert.match(contracts, /contentRevision: string/);
  assert.match(contracts, /publishGeneration: string/);
  assert.match(contracts, /BigInt\(value\)/);
  assert.match(transformer, /createHash\("sha256"\)/);
  assert.match(transformer, /PublicMediaManifestV2/);
  assert.match(transformer, /\/generated\/media\/\$\{filename\}/);
  assert.doesNotMatch(transformer, /RHAOMI_BUILD_SERVICE_TOKEN|Authorization/);
});

test("Build API HTTP adapter와 staging-only orchestration 경계를 고정한다", async () => {
  const [
    packageJson,
    environmentExample,
    httpClient,
    orchestrator,
    cli,
    publisherConfiguration,
    compose,
    nginx,
  ] = await Promise.all([
    source("package.json"),
    source(".env.example"),
    source("src/build-orchestration/http-client.mts"),
    source("src/build-orchestration/orchestrator.mts"),
    source("scripts/prepare-publication-staging.mts"),
    source("backend/src/main/java/kr/co/rhaomi/publisher/PublisherConfiguration.java"),
    source("compose.dev.yaml"),
    source("infra/nginx/dev.conf"),
  ]);
  const manifest = JSON.parse(packageJson);

  assert.equal(
    manifest.scripts["prepare:publication-staging"],
    "node scripts/prepare-publication-staging.mts",
  );
  assert.match(environmentExample, /BUILD_API_INTERNAL_URL=http:\/\/backend:8080/);
  assert.match(environmentExample, /BUILD_API_CREDENTIAL=/);
  assert.match(httpClient, /authorization: `Bearer \$\{config\.credential\}`/);
  assert.match(httpClient, /redirect: "manual"/);
  assert.match(httpClient, /credentials: "omit"/);
  assert.match(httpClient, /publishGeneration/);
  assert.match(httpClient, /parseBuildSnapshotV2/);
  assert.match(httpClient, /BigInt\(snapshot\.publishGeneration\)/);
  assert.match(httpClient, /new Map\(input\.assets\.map/);
  assert.match(httpClient, /this\.#content\.set\(mediaId, pending\)/);
  assert.match(orchestrator, /await mediaContentProvider\.prefetchAll\(\)/);
  assert.match(orchestrator, /transformBuildSnapshot/);
  assert.match(orchestrator, /contentRevision: string/);
  assert.match(orchestrator, /publishGeneration: string/);
  assert.doesNotMatch(orchestrator, /completeSuccess|completeNoop|PublicationBuildResult/);
  assert.match(cli, /--publish-generation/);
  assert.match(cli, /--output/);
  assert.doesNotMatch(cli, /--(?:url|credential|token)/i);
  assert.match(publisherConfiguration, /new NodePublicationBuildExecutor/);
  assert.doesNotMatch(publisherConfiguration, /unavailablePublicationBuildExecutor/);
  assert.doesNotMatch(compose, /\n  publisher:\n/);
  assert.match(nginx, /location \^~ \/api\/build\/\s*\{\s*return 404;/);
});

test("publisher control loop를 exact opt-in non-web process로 격리한다", async () => {
  const [backendApplication, publisherApplication, publisherConfiguration, compose, nginx, smoke] =
    await Promise.all([
      source("backend/src/main/java/kr/co/rhaomi/backend/BackendApplication.java"),
      source("backend/src/main/java/kr/co/rhaomi/publisher/PublisherApplication.java"),
      source("backend/src/main/java/kr/co/rhaomi/publisher/PublisherConfiguration.java"),
      source("compose.dev.yaml"),
      source("infra/nginx/dev.conf"),
      source("scripts/validate-backend-compose.sh"),
    ]);

  assert.match(backendApplication, /PublisherApplication\.hasModeArgument\(args\)/);
  assert.match(publisherApplication, /--rhaomi\.publisher\.mode=control-loop/);
  assert.match(publisherApplication, /setWebApplicationType\(WebApplicationType\.NONE\)/);
  assert.match(publisherConfiguration, /PublicationStateService\.class/);
  assert.doesNotMatch(publisherConfiguration, /ComponentScan|RestController|SecurityFilterChain/);
  assert.doesNotMatch(compose, /\n  publisher:\n/);
  assert.doesNotMatch(nginx, /publisher/i);
  assert.match(smoke, /--tests 'kr\.co\.rhaomi\.publisher\.\*'/);
  assert.match(smoke, /PublisherIsolationIntegrationTests/);
});

test("full static release executor와 validation-only multi-runtime 경계를 고정한다", async () => {
  const [
    packageJson,
    compose,
    validationDockerfile,
    releaseCli,
    releaseOrchestrator,
    releaseFilesystem,
    javaExecutor,
    smoke,
  ] = await Promise.all([
    source("package.json"),
    source("compose.dev.yaml"),
    source("backend/Dockerfile.publisher-validation"),
    source("scripts/publish-static-release.mts"),
    source("src/publication-release/orchestrator.mts"),
    source("src/publication-release/filesystem.mts"),
    source("backend/src/main/java/kr/co/rhaomi/publisher/NodePublicationBuildExecutor.java"),
    source("scripts/validate-backend-compose.sh"),
  ]);
  const manifest = JSON.parse(packageJson);
  const publisherBlock =
    compose.match(/\n  publisher-validation:\n([\s\S]*?)\nvolumes:/)?.[1] ?? "";

  assert.equal(
    manifest.scripts["publish:static-release"],
    "node scripts/publish-static-release.mts",
  );
  assert.match(validationDockerfile, /node:24\.20\.0-alpine3\.23@sha256:/);
  assert.match(validationDockerfile, /eclipse-temurin:25\.0\.4_7-jdk-alpine-3\.23@sha256:/);
  assert.match(validationDockerfile, /node --version/);
  assert.match(validationDockerfile, /java -version/);
  assert.match(publisherBlock, /profiles: \["publisher-validation"\]/);
  assert.match(publisherBlock, /FullPublicationPipelineIntegrationTests/);
  assert.doesNotMatch(publisherBlock, /\n\s+ports:|docker\.sock|\.env\.dev\.local/);
  assert.match(releaseCli, /--publish-generation/);
  assert.doesNotMatch(releaseCli, /--(?:credential|token|url)/i);
  assert.match(releaseOrchestrator, /preparePublicationStaging/);
  assert.match(releaseOrchestrator, /buildIsolatedNextExport/);
  assert.match(releaseOrchestrator, /validateStaticExport/);
  assert.match(releaseOrchestrator, /smokeStaticServingPath/);
  assert.match(releaseOrchestrator, /retentionStatus: "COMPLETE" \| "DEFERRED" \| "NOT_APPLICABLE"/);
  assert(
    releaseOrchestrator.indexOf("const switchResult") <
      releaseOrchestrator.indexOf("await pruneSuccessfulReleases"),
  );
  assert.match(releaseFilesystem, /compareGenerations/);
  assert.match(releaseFilesystem, /atomicSymlink/);
  assert.match(releaseFilesystem, /pruneSuccessfulReleases/);
  assert.match(javaExecutor, /ProcessBuilder/);
  assert.match(javaExecutor, /trackProcessTree/);
  assert.match(javaExecutor, /terminateProcessTree/);
  assert.match(javaExecutor, /"DEFERRED"\.equals\(retentionStatus\)/);
  assert.match(smoke, /--profile publisher-validation build publisher-validation/);
  assert.match(smoke, /--profile publisher-validation run --rm publisher-validation/);
});

test("실행 경로에 Directus 설정을 남기지 않는다", async () => {
  const runtimeFiles = await Promise.all([
    source("compose.dev.yaml"),
    source(".env.example"),
    source(".github/workflows/validate.yml"),
    source("package.json"),
  ]);

  for (const runtimeFile of runtimeFiles) {
    assert.doesNotMatch(runtimeFile, /Directus|DIRECTUS_|directus/i);
  }
});
