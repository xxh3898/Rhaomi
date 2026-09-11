import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

test("initial-content tracked schema가 exact manifest와 complete public content shape를 고정한다", async () => {
  const manifest = JSON.parse(
    await source("contracts/initial-content/manifest-v1.schema.json"),
  );
  const content = JSON.parse(
    await source("contracts/initial-content/content-v1.schema.json"),
  );

  assert.equal(manifest.additionalProperties, false);
  assert.deepEqual(manifest.required, ["schemaVersion", "content", "media", "counts"]);
  assert.equal(manifest.properties.schemaVersion.const, 1);
  assert.equal(manifest.properties.content.properties.path.const, "content.json");
  assert.equal(manifest.properties.media.minItems, 1);
  assert.equal(manifest.properties.media.items.additionalProperties, false);
  assert.deepEqual(manifest.properties.counts.required, [
    "shopSettings",
    "media",
    "breeds",
    "services",
    "notices",
    "galleryItems",
  ]);
  assert.equal(manifest.properties.counts.properties.shopSettings.const, 1);
  for (const collection of ["media", "breeds", "services", "notices", "galleryItems"]) {
    assert.equal(manifest.properties.counts.properties[collection].minimum, 1);
  }

  assert.equal(content.additionalProperties, false);
  assert.equal(content.properties.schemaVersion.const, 1);
  assert.deepEqual(content.required, [
    "schemaVersion",
    "shopSettings",
    "breeds",
    "services",
    "notices",
    "galleryItems",
  ]);
  assert.equal(content.$defs.shopSettings.additionalProperties, false);
  assert.equal(content.$defs.shopSettings.required.length, 26);
  for (const collection of ["breeds", "services", "notices", "galleryItems"]) {
    assert.equal(content.properties[collection].minItems, 1);
  }
  for (const type of ["breed", "service", "notice", "galleryItem"]) {
    assert.equal(content.$defs[type].additionalProperties, false);
    assert.ok(content.$defs[type].required.includes("key"));
  }
});

test("initial-content authority가 fixed non-web task와 application validation 경계에만 존재한다", async () => {
  const [taskApplication, configuration, reader, service, compose] = await Promise.all([
    source("backend/src/main/java/kr/co/rhaomi/production/ProductionDatabaseTaskApplication.java"),
    source("backend/src/main/java/kr/co/rhaomi/production/ProductionInitialContentTaskConfiguration.java"),
    source("backend/src/main/java/kr/co/rhaomi/production/InitialContentBundleReader.java"),
    source("backend/src/main/java/kr/co/rhaomi/production/InitialContentImportService.java"),
    source("compose.production.yaml"),
  ]);

  assert.match(taskApplication, /--rhaomi\.production-task=initial-content/u);
  assert.match(taskApplication, /WebApplicationType\.NONE/u);
  assert.match(configuration, /INPUT_ROOT = Path\.of\("\/run\/rhaomi-initial-content"\)/u);
  assert.doesNotMatch(configuration, /RestController|Controller|PublisherControlLoop/u);
  assert.match(reader, /STRICT_DUPLICATE_DETECTION/u);
  assert.match(reader, /unix:nlink/u);
  assert.match(reader, /INITIAL_CONTENT_CHECKSUM_INVALID/u);
  assert.match(service, /importLock\.acquire\(\)/u);
  assert.match(service, /hasExistingMediaState\(\)/u);
  assert.match(service, /uploadForInitialImport/u);
  assert.match(service, /PublicationSourceType\.SHOP_SETTINGS/u);
  assert.doesNotMatch(service, /INSERT INTO|UPDATE .* SET|DELETE FROM/iu);
  assert.match(compose, /--rhaomi\.production-task=initial-content/u);
  assert.match(compose, /source: \/private\/var\/lib\/rhaomi\/state\/initial-content[\s\S]*read_only: true/u);
});
