import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const releaseSha = "a".repeat(40);
const manifestDigest = `sha256:${"b".repeat(64)}`;
const repository = "ghcr.io/xxh3898/rhaomi";
const source = "https://github.com/xxh3898/Rhaomi";
const platforms = [
  {
    key: "linux-amd64",
    platform: "linux/amd64",
    os: "linux",
    architecture: "amd64",
    digest: `sha256:${"c".repeat(64)}`,
    attestationDigest: `sha256:${"e".repeat(64)}`,
  },
  {
    key: "linux-arm64",
    platform: "linux/arm64",
    os: "linux",
    architecture: "arm64",
    digest: `sha256:${"d".repeat(64)}`,
    attestationDigest: `sha256:${"f".repeat(64)}`,
  },
];

async function writeJson(path, value) {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

async function createEvidenceFixture() {
  const root = await mkdtemp(join(tmpdir(), "rhaomi-release-evidence-"));
  const evidenceDir = join(root, "published");
  const validationSummary = join(root, "supply-chain-summary.json");
  const output = join(evidenceDir, "release-evidence.json");
  await mkdir(evidenceDir);

  await writeJson(join(evidenceDir, "manifest-index.json"), {
    schemaVersion: 2,
    mediaType: "application/vnd.oci.image.index.v1+json",
    digest: manifestDigest,
    manifests: platforms.flatMap((platform) => [
      {
        mediaType: "application/vnd.oci.image.manifest.v1+json",
        digest: platform.digest,
        size: 1234,
        platform: {
          architecture: platform.architecture,
          os: platform.os,
        },
      },
      {
        mediaType: "application/vnd.oci.image.manifest.v1+json",
        digest: platform.attestationDigest,
        size: 5678,
        annotations: {
          "vnd.docker.reference.digest": platform.digest,
          "vnd.docker.reference.type": "attestation-manifest",
        },
        platform: { architecture: "unknown", os: "unknown" },
      },
    ]),
  });

  for (const platform of platforms) {
    await writeJson(join(evidenceDir, `${platform.key}-image.json`), {
      architecture: platform.architecture,
      os: platform.os,
      config: {
        Labels: {
          "org.opencontainers.image.revision": releaseSha,
          "org.opencontainers.image.source": source,
        },
      },
    });
    await writeJson(join(evidenceDir, `${platform.key}-sbom.spdx.json`), {
      spdxVersion: "SPDX-2.3",
      dataLicense: "CC0-1.0",
      SPDXID: "SPDXRef-DOCUMENT",
      name: `${repository}@${platform.digest}`,
      documentNamespace: `https://example.invalid/${platform.architecture}`,
      creationInfo: { creators: ["Tool: buildkit"] },
      packages: [
        {
          SPDXID: "SPDXRef-Package-libheif",
          name: "libheif",
          versionInfo: "1.23.1",
        },
      ],
    });
    await writeJson(join(evidenceDir, `${platform.key}-provenance.json`), {
      _type: "https://in-toto.io/Statement/v0.1",
      predicateType: "https://slsa.dev/provenance/v0.2",
      predicate: {
        builder: { id: "https://github.com/docker/buildx" },
        buildType: "https://mobyproject.org/buildkit@v1",
        materials: [
          {
            uri: "pkg:docker/alpine@3.23?platform=linux",
            digest: { sha256: "2".repeat(64) },
          },
        ],
        invocation: { environment: { platform: platform.platform } },
      },
    });
    await writeJson(join(evidenceDir, `${platform.key}-grype.json`), {
      descriptor: { name: "grype", version: "0.104.1" },
      source: { type: "sbom", target: `${platform.key}-sbom.spdx.json` },
      matches: [],
    });
  }

  await writeJson(validationSummary, {
    schemaVersion: 1,
    imageId: `sha256:${"1".repeat(64)}`,
    gitHead: releaseSha,
    sbomComponentCount: 100,
    vulnerabilityMatchCount: 0,
    severityCounts: {},
    x265ComponentCount: 0,
    inventoryComponents: [{ name: "libheif", version: "1.23.1" }],
  });

  return { evidenceDir, validationSummary, output };
}

function verify({ evidenceDir, validationSummary, output }) {
  return spawnSync(
    process.execPath,
    [
      "scripts/verify-published-production-image.mjs",
      evidenceDir,
      validationSummary,
      output,
      repository,
      releaseSha,
      manifestDigest,
      "123456789",
    ],
    { cwd: process.cwd(), encoding: "utf8" },
  );
}

test("published OCI index의 두 platform과 attached evidence를 actual digest에 결합한다", async () => {
  const fixture = await createEvidenceFixture();
  const result = verify(fixture);
  assert.equal(result.status, 0, result.stderr);

  const evidence = JSON.parse(await readFile(fixture.output, "utf8"));
  assert.equal(evidence.manifestDigest, manifestDigest);
  assert.equal(evidence.sbomReference, manifestDigest);
  assert.equal(evidence.sbomReferenceType, "oci-index-with-attached-platform-sbom");
  assert.equal(evidence.scanActualPublishedArtifact, true);
  assert.equal(evidence.attestationsVerified, true);
  assert.deepEqual(
    evidence.platforms.map(({ platform, manifestDigest: digest }) => ({ platform, digest })),
    platforms.map(({ platform, digest }) => ({ platform, digest })),
  );
  assert.equal(evidence.prePublishValidation.scope, "auxiliary-validation-image-only");
});

test("platform attestation, OCI identity, SBOM 또는 provenance가 다르면 fail-closed한다", async (t) => {
  const cases = [
    ["attestation 누락", async ({ evidenceDir }) => {
      const indexPath = join(evidenceDir, "manifest-index.json");
      const index = JSON.parse(await readFile(indexPath, "utf8"));
      index.manifests = index.manifests.filter(
        ({ digest }) => digest !== platforms[0].attestationDigest,
      );
      await writeJson(indexPath, index);
    }],
    ["OCI revision 불일치", async ({ evidenceDir }) => {
      const path = join(evidenceDir, "linux-arm64-image.json");
      const image = JSON.parse(await readFile(path, "utf8"));
      image.config.Labels["org.opencontainers.image.revision"] = "0".repeat(40);
      await writeJson(path, image);
    }],
    ["SBOM package 부재", async ({ evidenceDir }) => {
      const path = join(evidenceDir, "linux-amd64-sbom.spdx.json");
      const sbom = JSON.parse(await readFile(path, "utf8"));
      sbom.packages = [];
      await writeJson(path, sbom);
    }],
    ["provenance build type 부재", async ({ evidenceDir }) => {
      const path = join(evidenceDir, "linux-arm64-provenance.json");
      const provenance = JSON.parse(await readFile(path, "utf8"));
      delete provenance.predicate.buildType;
      await writeJson(path, provenance);
    }],
  ];

  for (const [name, mutate] of cases) {
    await t.test(name, async () => {
      const fixture = await createEvidenceFixture();
      await mutate(fixture);
      const result = verify(fixture);
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, /PUBLISHED_IMAGE_EVIDENCE_INVALID/u);
    });
  }
});
