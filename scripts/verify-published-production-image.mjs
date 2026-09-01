import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const EXPECTED_REPOSITORY = "ghcr.io/xxh3898/rhaomi";
const EXPECTED_SOURCE = "https://github.com/xxh3898/Rhaomi";
const DIGEST_PATTERN = /^sha256:[0-9a-f]{64}$/u;
const PLATFORMS = [
  { key: "linux-amd64", platform: "linux/amd64", os: "linux", architecture: "amd64" },
  { key: "linux-arm64", platform: "linux/arm64", os: "linux", architecture: "arm64" },
];

function record(value) {
  assert(value && typeof value === "object" && !Array.isArray(value));
  return value;
}

async function readJson(path) {
  const bytes = await readFile(path);
  return { bytes, value: JSON.parse(bytes.toString("utf8")) };
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function positiveInteger(value) {
  assert(Number.isSafeInteger(value) && value > 0);
  return value;
}

function x265Identity(value) {
  return typeof value === "string" && /(^|[^a-z0-9])(?:lib)?x265([^a-z0-9]|$)/iu.test(value);
}

function sbomContainsX265(sbom) {
  return sbom.packages.some((packageValue) => {
    const packageRecord = record(packageValue);
    const references = Array.isArray(packageRecord.externalRefs)
      ? packageRecord.externalRefs
      : [];
    return [packageRecord.name, packageRecord.SPDXID, ...references.map((reference) =>
      record(reference).referenceLocator,
    )].some(x265Identity);
  });
}

function scanContainsX265(scan) {
  return scan.matches.some((matchValue) => {
    const match = record(matchValue);
    const artifact = record(match.artifact ?? {});
    return [artifact.name, artifact.purl, artifact.id].some(x265Identity);
  });
}

function severityCounts(matches) {
  const counts = {};
  for (const matchValue of matches) {
    const match = record(matchValue);
    const vulnerability = record(match.vulnerability ?? {});
    const severity =
      typeof vulnerability.severity === "string" && vulnerability.severity.length > 0
        ? vulnerability.severity
        : "Unknown";
    counts[severity] = (counts[severity] ?? 0) + 1;
  }
  return Object.fromEntries(Object.entries(counts).sort(([left], [right]) =>
    left.localeCompare(right, "en"),
  ));
}

function findPlatformDescriptors(index) {
  assert.equal(index.schemaVersion, 2);
  assert.equal(index.mediaType, "application/vnd.oci.image.index.v1+json");
  assert.match(index.digest, DIGEST_PATTERN);
  assert(Array.isArray(index.manifests));
  assert.equal(index.manifests.length, PLATFORMS.length * 2);

  const descriptors = [];
  const consumedDigests = new Set();
  for (const expected of PLATFORMS) {
    const imageCandidates = index.manifests.filter((candidateValue) => {
      const candidate = record(candidateValue);
      return candidate.platform?.os === expected.os &&
        candidate.platform?.architecture === expected.architecture;
    });
    assert.equal(imageCandidates.length, 1);
    const image = record(imageCandidates[0]);
    assert.equal(image.mediaType, "application/vnd.oci.image.manifest.v1+json");
    assert.match(image.digest, DIGEST_PATTERN);
    positiveInteger(image.size);

    const attestationCandidates = index.manifests.filter((candidateValue) => {
      const candidate = record(candidateValue);
      return candidate.annotations?.["vnd.docker.reference.type"] ===
          "attestation-manifest" &&
        candidate.annotations?.["vnd.docker.reference.digest"] === image.digest;
    });
    assert.equal(attestationCandidates.length, 1);
    const attestation = record(attestationCandidates[0]);
    assert.equal(attestation.mediaType, "application/vnd.oci.image.manifest.v1+json");
    assert.equal(attestation.platform?.os, "unknown");
    assert.equal(attestation.platform?.architecture, "unknown");
    assert.match(attestation.digest, DIGEST_PATTERN);
    positiveInteger(attestation.size);
    assert(!consumedDigests.has(image.digest));
    assert(!consumedDigests.has(attestation.digest));
    consumedDigests.add(image.digest);
    consumedDigests.add(attestation.digest);
    descriptors.push({ ...expected, image, attestation });
  }
  assert.equal(consumedDigests.size, index.manifests.length);
  return descriptors;
}

async function verifyPlatform(evidenceDir, descriptor, releaseSha) {
  const imageFile = await readJson(resolve(evidenceDir, `${descriptor.key}-image.json`));
  const sbomFile = await readJson(resolve(evidenceDir, `${descriptor.key}-sbom.spdx.json`));
  const provenanceFile = await readJson(
    resolve(evidenceDir, `${descriptor.key}-provenance.json`),
  );
  const scanFile = await readJson(resolve(evidenceDir, `${descriptor.key}-grype.json`));

  const image = record(imageFile.value);
  assert.equal(image.os, descriptor.os);
  assert.equal(image.architecture, descriptor.architecture);
  const labels = record(record(image.config).Labels);
  assert.equal(labels["org.opencontainers.image.source"], EXPECTED_SOURCE);
  assert.equal(labels["org.opencontainers.image.revision"], releaseSha);

  const sbom = record(sbomFile.value);
  assert.match(sbom.spdxVersion, /^SPDX-2\.[23]$/u);
  assert.equal(sbom.dataLicense, "CC0-1.0");
  assert.equal(sbom.SPDXID, "SPDXRef-DOCUMENT");
  assert(typeof sbom.documentNamespace === "string" && sbom.documentNamespace.length > 0);
  record(sbom.creationInfo);
  assert(Array.isArray(sbom.packages) && sbom.packages.length > 0);
  assert.equal(sbomContainsX265(sbom), false);

  const provenanceEnvelope = record(provenanceFile.value);
  const provenance = record(provenanceEnvelope.predicate ?? provenanceEnvelope);
  assert.equal(provenance.buildType, "https://mobyproject.org/buildkit@v1");
  record(provenance.builder);
  assert(Array.isArray(provenance.materials) && provenance.materials.length > 0);
  assert.equal(
    record(record(provenance.invocation).environment).platform,
    descriptor.platform,
  );

  const scan = record(scanFile.value);
  assert.equal(record(scan.descriptor).name, "grype");
  assert.equal(record(scan.source).type, "sbom");
  assert(Array.isArray(scan.matches));
  assert.equal(scanContainsX265(scan), false);
  const platformSeverityCounts = severityCounts(scan.matches);
  assert.equal(platformSeverityCounts.High ?? 0, 0);
  assert.equal(platformSeverityCounts.Critical ?? 0, 0);

  return {
    platform: descriptor.platform,
    manifestDigest: descriptor.image.digest,
    attestationManifestDigest: descriptor.attestation.digest,
    ociSource: EXPECTED_SOURCE,
    ociRevision: releaseSha,
    sbomSha256: sha256(sbomFile.bytes),
    sbomPackageCount: sbom.packages.length,
    provenanceSha256: sha256(provenanceFile.bytes),
    scanSha256: sha256(scanFile.bytes),
    vulnerabilityMatchCount: scan.matches.length,
    severityCounts: platformSeverityCounts,
    x265ComponentCount: 0,
  };
}

async function main() {
  const [
    evidenceDir,
    validationSummaryPath,
    outputPath,
    repository,
    releaseSha,
    manifestDigest,
    workflowRunId,
  ] = process.argv.slice(2);
  assert.equal(process.argv.length, 9);
  assert.equal(repository, EXPECTED_REPOSITORY);
  assert.match(releaseSha, /^[0-9a-f]{40}$/u);
  assert.match(manifestDigest, DIGEST_PATTERN);
  assert.match(workflowRunId, /^[1-9][0-9]*$/u);
  assert.equal(resolve(outputPath), resolve(evidenceDir, "release-evidence.json"));

  const indexFile = await readJson(resolve(evidenceDir, "manifest-index.json"));
  const index = record(indexFile.value);
  assert.equal(index.digest, manifestDigest);
  const descriptors = findPlatformDescriptors(index);
  const verifiedPlatforms = [];
  for (const descriptor of descriptors) {
    verifiedPlatforms.push(await verifyPlatform(evidenceDir, descriptor, releaseSha));
  }

  const validationFile = await readJson(validationSummaryPath);
  const validation = record(validationFile.value);
  assert.equal(validation.schemaVersion, 1);
  assert.equal(validation.gitHead, releaseSha);
  assert.equal(validation.x265ComponentCount, 0);
  positiveInteger(validation.sbomComponentCount);
  assert(Number.isSafeInteger(validation.vulnerabilityMatchCount) &&
    validation.vulnerabilityMatchCount >= 0);
  record(validation.severityCounts);
  assert(Array.isArray(validation.inventoryComponents) &&
    validation.inventoryComponents.length > 0);

  const evidence = {
    schemaVersion: 2,
    releaseSha,
    imageTag: `${repository}:${releaseSha}`,
    imageReference: `${repository}@${manifestDigest}`,
    manifestDigest,
    manifestIndexSha256: sha256(indexFile.bytes),
    sbomReference: manifestDigest,
    sbomReferenceType: "oci-index-with-attached-platform-sbom",
    attestationsVerified: true,
    scanActualPublishedArtifact: true,
    platforms: verifiedPlatforms,
    prePublishValidation: {
      scope: "auxiliary-validation-image-only",
      sha256: sha256(validationFile.bytes),
      gitHead: validation.gitHead,
      sbomComponentCount: validation.sbomComponentCount,
      vulnerabilityMatchCount: validation.vulnerabilityMatchCount,
      severityCounts: validation.severityCounts,
      x265ComponentCount: validation.x265ComponentCount,
    },
    flywayTarget: "9",
    workflowRunId,
  };
  await writeFile(outputPath, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
}

try {
  await main();
  console.log("published production image evidence: PASS");
} catch {
  console.error("PUBLISHED_IMAGE_EVIDENCE_INVALID");
  process.exitCode = 1;
}
