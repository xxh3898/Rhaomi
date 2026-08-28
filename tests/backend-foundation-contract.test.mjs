import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

test("개발 Compose를 backend와 비공개 PostgreSQL로 제한한다", async () => {
  const compose = await source("compose.dev.yaml");

  assert.match(compose, /backend:\n/);
  assert.match(compose, /postgres:\n/);
  assert.match(compose, /127\.0\.0\.1:8080:8080/);
  assert.match(compose, /127\.0\.0\.1:3000:3000/);
  assert.equal((compose.match(/@sha256:/g) ?? []).length, 4);
  assert.match(
    compose,
    /eclipse-temurin:25\.0\.4_7-jdk-alpine-3\.23@sha256:b7c88ce22d575642650ec83cbf4e470a0c183a46871467180238e4b27ad9e20a/,
  );
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
