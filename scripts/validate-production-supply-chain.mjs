import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";

const [sbomPath, scanPath, inventoryPath, summaryPath, imageId, gitHead] =
  process.argv.slice(2);

assert(sbomPath && scanPath && inventoryPath && summaryPath, "공급망 검증 입력이 필요합니다.");
assert.match(imageId ?? "", /^sha256:[0-9a-f]{64}$/u);
assert.match(gitHead ?? "", /^[0-9a-f]{40}$/u);

const sbom = JSON.parse(await readFile(sbomPath, "utf8"));
const scan = JSON.parse(await readFile(scanPath, "utf8"));
const inventory = JSON.parse(await readFile(inventoryPath, "utf8"));

assert.equal(sbom.bomFormat, "CycloneDX");
assert(Array.isArray(sbom.components));
assert(Array.isArray(scan.matches), "Grype scan 결과 형식이 올바르지 않습니다.");
assert(scan.descriptor && typeof scan.descriptor === "object");
assert.equal(inventory.schemaVersion, 1);

const REQUIRED_RUNTIME_COMPONENTS = [
  "libheif",
  "libde265",
  "imageio-heif",
  "eclipse-temurin-jre",
  "node",
];
assert.deepEqual(
  inventory.components.map((component) => component.name),
  REQUIRED_RUNTIME_COMPONENTS,
  "tracked runtime component inventory가 변경됐습니다.",
);

const metadataProperties = new Map(
  (sbom.metadata?.properties ?? []).map(({ name, value }) => [name, value]),
);
assert.equal(metadataProperties.get("rhaomi:image-id"), imageId);
assert.equal(metadataProperties.get("rhaomi:git-head"), gitHead);

for (const expected of inventory.components) {
  const component = sbom.components.find(
    (candidate) =>
      candidate["bom-ref"] ===
      `rhaomi-runtime:${expected.name}@${expected.version}`,
  );
  assert(component, `${expected.name} runtime component가 SBOM에 없습니다.`);
  assert(
    component.licenses?.some((entry) => entry.expression === expected.license),
    `${expected.name} license expression이 SBOM과 일치하지 않습니다.`,
  );
  assert(
    component.externalReferences?.some(
      (reference) => reference.url === expected.sourceUrl,
    ),
    `${expected.name} source URL이 SBOM에 없습니다.`,
  );
}

function x265Identity(component) {
  return [component.name, component.purl, component["bom-ref"]]
    .filter(Boolean)
    .some((value) => /(^|[^a-z0-9])(?:lib)?x265([^a-z0-9]|$)/iu.test(value));
}

assert(
  !sbom.components.some(x265Identity),
  "금지된 x265 component가 SBOM에 포함됐습니다.",
);
assert(
  !scan.matches.some((match) => x265Identity(match.artifact ?? {})),
  "금지된 x265 component가 scanner 결과에 포함됐습니다.",
);

const severityCounts = {};
for (const match of scan.matches) {
  const severity = String(match.vulnerability?.severity ?? "Unknown");
  severityCounts[severity] = (severityCounts[severity] ?? 0) + 1;
}

const summary = {
  schemaVersion: 1,
  imageId,
  gitHead,
  sbomComponentCount: sbom.components.length,
  vulnerabilityMatchCount: scan.matches.length,
  severityCounts,
  x265ComponentCount: 0,
  inventoryComponents: inventory.components.map(({ name, version }) => ({
    name,
    version,
  })),
};

await writeFile(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
console.log(
  `Supply-chain validation passed: components=${summary.sbomComponentCount} matches=${summary.vulnerabilityMatchCount} high=${severityCounts.High ?? 0} critical=${severityCounts.Critical ?? 0}`,
);
