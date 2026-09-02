import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";

import { preparePublicationStaging } from "../src/build-orchestration/index.mts";

function argument(name: string): string {
  const index = process.argv.indexOf(name);
  const value = index >= 0 ? process.argv[index + 1] : undefined;
  if (value === undefined || value.length === 0) {
    throw new Error(`Missing required argument: ${name}`);
  }
  return value;
}

const expectedContentRevision = argument("--content-revision");
const expectedPublishGeneration = argument("--publish-generation");
const outputRoot = argument("--output");

const result = await preparePublicationStaging({
  publishGeneration: expectedPublishGeneration,
  outputRoot,
});

assert.equal(result.contentRevision, expectedContentRevision);
assert.equal(result.publishGeneration, expectedPublishGeneration);

const content = JSON.parse(
  await readFile(join(outputRoot, "src/generated/content.json"), "utf8"),
) as Record<string, unknown>;
const mediaManifest = JSON.parse(
  await readFile(
    join(outputRoot, "src/generated/media-manifest.json"),
    "utf8",
  ),
) as Record<string, unknown>;

for (const artifact of [content, mediaManifest]) {
  assert.equal(artifact.schemaVersion, 2);
  assert.equal(typeof artifact.contentRevision, "string");
  assert.equal(artifact.contentRevision, expectedContentRevision);
  assert.equal(typeof artifact.publishGeneration, "string");
  assert.equal(artifact.publishGeneration, expectedPublishGeneration);
}

process.stdout.write(
  `Lossless int64 staging validation passed: generation=${expectedPublishGeneration}\n`,
);
