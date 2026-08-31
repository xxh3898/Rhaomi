import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

test("production image가 exact decoder-only source와 runtime을 고정한다", async () => {
  const dockerfile = await source("backend/Dockerfile.production");

  assert.match(
    dockerfile,
    /LIBHEIF_TAG=v1\.23\.1[\s\S]*LIBHEIF_COMMIT=2c4bbb54c2738d4a5efbbe3e5fa1d5d76bb88eb0/,
  );
  assert.match(
    dockerfile,
    /LIBHEIF_ARCHIVE_SHA256=9fdb7410222a9fd12387f4332e3f93cf428c976ac16f1379fcd7f6415ebe03c0/,
  );
  assert.match(dockerfile, /LIBDE265_VERSION=1\.0\.16-r0/);
  assert.match(dockerfile, /OPENSSL_VERSION=3\.5\.8-r0/);
  assert.match(
    dockerfile,
    /eclipse-temurin:25\.0\.4_7-jre-alpine-3\.23@sha256:f8b38ad02cacf5a1618a329dd22915fb1e2f2914fe6242e6daaf5cc7d22d6677/,
  );
  assert.match(
    dockerfile,
    /node:24\.20\.0-alpine3\.23@sha256:0388af2af070cd4736a1567cfed02469ba117848845b4165d87a333edb53d2ca/,
  );
  assert.match(dockerfile, /WITH_LIBDE265=ON/);
  assert.match(dockerfile, /ENABLE_PLUGIN_LOADING=OFF/);
  assert.match(dockerfile, /WITH_X265=OFF/);
  assert.match(dockerfile, /BUILD_TESTING=OFF/);
  assert.match(dockerfile, /COPY --from=application-builder \/workspace\/backend\.jar/);
  assert.match(dockerfile, /COPY --from=publisher-builder \/workspace\/source/);
  assert.doesNotMatch(dockerfile, /COPY\s+\.\s/);
  assert.doesNotMatch(dockerfile, /Dockerfile\.dev|Dockerfile\.publisher-validation/);
});

test("production image acceptance가 image surface, actual media와 supply chain을 검증한다", async () => {
  const [entrypoint, cmakeValidator, mediaSmoke, sbomFinalizer, supplyChain] =
    await Promise.all([
      source("scripts/validate-production-image.sh"),
      source("scripts/validate-libheif-build-contract.sh"),
      source("scripts/validate-production-image-media.mjs"),
      source("scripts/finalize-production-sbom.mjs"),
      source("scripts/validate-production-supply-chain.mjs"),
    ]);

  assert.match(entrypoint, /backend\/Dockerfile\.production/);
  assert.match(entrypoint, /java -version/);
  assert.match(entrypoint, /node --version/);
  assert.match(entrypoint, /amd64 \| arm64/);
  assert.match(entrypoint, /apk info -e libde265/);
  assert.match(entrypoint, /libcrypto3-3\.5\.8-r0/);
  assert.match(entrypoint, /x265-libs/);
  assert.match(entrypoint, /ldd .*libheif/);
  assert.match(entrypoint, /validate-production-image-media\.mjs/);
  assert.match(
    entrypoint,
    /anchore\/syft:v1\.36\.0@sha256:6733fa6ba7fb102d5b8eecae0e9ee7ee7091e613b8ce8d1fc9e6641335ab3962/,
  );
  assert.match(
    entrypoint,
    /anchore\/grype:v0\.104\.1@sha256:e7d3cb36d2ebfb522141d83a5d0df9cda301f7e9f8747ee4af41f12c478fa77c/,
  );
  assert.match(cmakeValidator, /plugin_option/);
  assert.match(cmakeValidator, /WITH_LIBDE265/);
  assert.match(cmakeValidator, /X265/);
  assert.match(mediaSmoke, /synthetic-orientation-metadata\.heic/);
  assert.match(mediaSmoke, /synthetic-orientation-metadata\.heif/);
  assert.match(mediaSmoke, /synthetic-sequence-branded\.heic/);
  assert.match(mediaSmoke, /MEDIA_INVALID_IMAGE/);
  assert.match(mediaSmoke, /MEDIA_TYPE_UNSUPPORTED/);
  assert.match(sbomFinalizer, /CycloneDX/);
  assert.match(supplyChain, /libheif/);
  assert.match(supplyChain, /libde265/);
  assert.match(supplyChain, /imageio-heif/);
  assert.match(supplyChain, /x265/i);
});

test("Hosted Validate는 3-job과 read-only permission을 유지한다", async () => {
  const workflow = await source(".github/workflows/validate.yml");
  const jobs = [...workflow.matchAll(/^  [a-z0-9-]+:\s*$/gmu)].map((match) =>
    match[0].trim(),
  );

  assert.deepEqual(jobs, ["frontend:", "backend:", "compose-smoke:"]);
  assert.match(workflow, /permissions:\n  contents: read/);
  assert.match(workflow, /scripts\/validate-production-image\.sh/);
  assert.match(workflow, /actions\/upload-artifact@[0-9a-f]{40}/);
  assert.doesNotMatch(workflow, /packages:\s*write|ghcr\.io|docker\/login-action/i);
});

test("tracked component inventory는 runtime source와 license identity를 명시한다", async () => {
  const inventory = JSON.parse(
    await source("backend/production-image-components.json"),
  );

  assert.equal(inventory.schemaVersion, 1);
  assert.deepEqual(
    inventory.components.map((component) => component.name),
    ["libheif", "libde265", "imageio-heif", "eclipse-temurin-jre", "node"],
  );
  for (const component of inventory.components) {
    assert.match(component.version, /^\S+$/u);
    assert.match(component.sourceUrl, /^https:\/\//u);
    assert.match(component.license, /^\S+$/u);
  }

  const notice = await source("backend/production-image-NOTICE.md");
  for (const component of [
    "libheif",
    "libde265",
    "imageio-heif",
    "Eclipse Temurin",
    "Node.js",
  ]) {
    assert.match(notice, new RegExp(component, "u"));
  }
  assert.match(notice, /LGPL-3\.0-or-later/u);
});
