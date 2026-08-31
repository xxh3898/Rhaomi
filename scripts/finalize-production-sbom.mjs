import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";

const [rawPath, inventoryPath, outputPath, imageId, gitHead] = process.argv.slice(2);

assert(rawPath && inventoryPath && outputPath, "SBOM 입력 경로가 필요합니다.");
assert.match(imageId ?? "", /^sha256:[0-9a-f]{64}$/u, "image ID가 올바르지 않습니다.");
assert.match(gitHead ?? "", /^[0-9a-f]{40}$/u, "Git HEAD가 올바르지 않습니다.");

const raw = JSON.parse(await readFile(rawPath, "utf8"));
const inventory = JSON.parse(await readFile(inventoryPath, "utf8"));

assert.equal(raw.bomFormat, "CycloneDX", "Syft CycloneDX SBOM이 아닙니다.");
assert(Array.isArray(raw.components), "SBOM component 목록이 없습니다.");
assert.equal(inventory.schemaVersion, 1, "component inventory version이 올바르지 않습니다.");
assert(Array.isArray(inventory.components), "component inventory가 올바르지 않습니다.");

function declaredComponent(component) {
  assert.match(component.name, /^\S(?:.*\S)?$/u);
  assert.match(component.version, /^\S+$/u);
  assert.match(component.sourceUrl, /^https:\/\//u);
  assert.match(component.license, /^\S+$/u);
  assert.match(component.obligationStatus, /^[A-Z_]+$/u);

  const properties = [
    { name: "rhaomi:declared-runtime-component", value: "true" },
    { name: "rhaomi:obligation-status", value: component.obligationStatus },
  ];
  for (const [key, value] of Object.entries(component)) {
    if (
      !["name", "version", "sourceUrl", "license", "obligationStatus"].includes(key)
    ) {
      properties.push({ name: `rhaomi:${key}`, value: String(value) });
    }
  }

  return {
    type: "library",
    "bom-ref": `rhaomi-runtime:${component.name}@${component.version}`,
    name: component.name,
    version: component.version,
    licenses: [{ expression: component.license }],
    externalReferences: [{ type: "vcs", url: component.sourceUrl }],
    properties,
  };
}

const declared = inventory.components.map(declaredComponent);
const declaredRefs = new Set(declared.map((component) => component["bom-ref"]));
const discovered = raw.components.filter(
  (component) => !declaredRefs.has(component["bom-ref"]),
);
const components = [...discovered, ...declared].sort((left, right) => {
  const leftRef = left["bom-ref"] ?? `${left.name ?? ""}@${left.version ?? ""}`;
  const rightRef = right["bom-ref"] ?? `${right.name ?? ""}@${right.version ?? ""}`;
  return leftRef.localeCompare(rightRef, "en");
});

const metadataProperties = Array.isArray(raw.metadata?.properties)
  ? raw.metadata.properties.filter(
      (property) => !["rhaomi:image-id", "rhaomi:git-head"].includes(property.name),
    )
  : [];

const finalized = {
  ...raw,
  metadata: {
    ...(raw.metadata ?? {}),
    properties: [
      ...metadataProperties,
      { name: "rhaomi:image-id", value: imageId },
      { name: "rhaomi:git-head", value: gitHead },
    ].sort((left, right) => left.name.localeCompare(right.name, "en")),
  },
  components,
};

await writeFile(outputPath, `${JSON.stringify(finalized, null, 2)}\n`, "utf8");
console.log(
  `CycloneDX SBOM finalized: discovered=${discovered.length} declared=${declared.length}`,
);
