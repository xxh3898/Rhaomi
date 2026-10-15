import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const imageId = `sha256:${"a".repeat(64)}`;
const gitHead = "b".repeat(40);

function match(severity, id = "CVE-2099-0001") {
  return {
    artifact: { name: "fixture-package", version: "1.0.0" },
    vulnerability: { id, ...(severity === undefined ? {} : { severity }) },
  };
}

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), "rhaomi-supply-chain-"));
  t.after(() => rm(root, { recursive: true }));
  const inventory = JSON.parse(
    await readFile(join(projectRoot, "backend/production-image-components.json"), "utf8"),
  );
  const sbom = {
    bomFormat: "CycloneDX",
    metadata: {
      properties: [
        { name: "rhaomi:image-id", value: imageId },
        { name: "rhaomi:git-head", value: gitHead },
      ],
    },
    components: inventory.components.map((component) => ({
      "bom-ref": `rhaomi-runtime:${component.name}@${component.version}`,
      name: component.name,
      version: component.version,
      licenses: [{ expression: component.license }],
      externalReferences: [{ url: component.sourceUrl }],
    })),
  };
  const scan = {
    descriptor: { name: "grype", version: "0.104.1", db: { status: { valid: true } } },
    matches: [],
  };
  const paths = {
    sbom: join(root, "sbom.json"),
    scan: join(root, "scan.json"),
    inventory: join(root, "inventory.json"),
    summary: join(root, "summary.json"),
  };
  await writeFile(paths.inventory, JSON.stringify(inventory));
  return { sbom, scan, paths };
}

async function verify(input) {
  await writeFile(input.paths.sbom, JSON.stringify(input.sbom));
  await writeFile(input.paths.scan, input.rawScan ?? JSON.stringify(input.scan));
  return spawnSync(process.execPath, [
    join(projectRoot, "scripts/validate-production-supply-chain.mjs"),
    input.paths.sbom, input.paths.scan, input.paths.inventory, input.paths.summary,
    imageId, gitHead,
  ], { cwd: projectRoot, encoding: "utf8" });
}

test("pre-validator가 현재 High9/Critical1 finding을 실패시키고 counts를 보존한다", async (t) => {
  const input = await fixture(t);
  input.scan.matches = [
    ...Array.from({ length: 9 }, (_, index) => match("High", `CVE-2099-${index}`)),
    match("Critical"),
  ];
  const result = await verify(input);
  assert.notEqual(result.status, 0);
  assert.doesNotMatch(result.stdout, /validation passed/iu);
  const summary = JSON.parse(await readFile(input.paths.summary, "utf8"));
  assert.equal(summary.schemaVersion, 1);
  assert.equal(summary.gitHead, gitHead);
  assert.equal(summary.imageId, imageId);
  assert.equal(summary.vulnerabilityMatchCount, 10);
  assert.deepEqual(summary.severityCounts, { High: 9, Critical: 1 });
});

test("pre-validator가 High/Critical을 fix 유무와 관계없이 차단한다", async (t) => {
  for (const severity of ["High", "Critical"]) {
    for (const state of ["fixed", "not-fixed"]) {
      await t.test(`${severity}/${state}`, async (subtest) => {
        const input = await fixture(subtest);
        input.scan.matches = [match(severity)];
        input.scan.matches[0].vulnerability.fix = { state };
        const result = await verify(input);
        assert.notEqual(result.status, 0);
        assert.doesNotMatch(result.stdout, /validation passed/iu);
        const summary = JSON.parse(await readFile(input.paths.summary, "utf8"));
        assert.equal(summary.severityCounts[severity], 1);
      });
    }
  }
});

test("pre-validator가 empty 및 명시적 nonblocking severity evidence를 보존한다", async (t) => {
  for (const severities of [[], ["Unknown", "Negligible", "Low", "Medium"]]) {
    await t.test(severities.join("/") || "empty", async (subtest) => {
      const input = await fixture(subtest);
      input.scan.matches = severities.map((severity) => match(severity));
      const result = await verify(input);
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /validation passed/u);
      const summary = JSON.parse(await readFile(input.paths.summary, "utf8"));
      assert.deepEqual(summary.severityCounts,
        Object.fromEntries(severities.map((severity) => [severity, 1])));
    });
  }
});

test("pre-validator가 malformed scan에 success evidence를 만들지 않는다", async (t) => {
  const cases = [
    ["JSON 손상", (input) => { input.rawScan = "{"; }],
    ["descriptor 누락", (input) => { delete input.scan.descriptor; }],
    ["descriptor 배열", (input) => { input.scan.descriptor = []; }],
    ["scanner 이름 불일치", (input) => { input.scan.descriptor.name = "syft"; }],
    ["scanner version 불일치", (input) => { input.scan.descriptor.version = "unknown"; }],
    ["scanner version 누락", (input) => { delete input.scan.descriptor.version; }],
    ["DB descriptor 누락", (input) => { delete input.scan.descriptor.db; }],
    ["DB invalid", (input) => { input.scan.descriptor.db.status.valid = false; }],
    ["matches 누락", (input) => { delete input.scan.matches; }],
    ["matches 객체", (input) => { input.scan.matches = {}; }],
    ["match null", (input) => { input.scan.matches = [null]; }],
    ["vulnerability 누락", (input) => { input.scan.matches = [{ artifact: { name: "fixture" } }]; }],
    ["artifact 누락", (input) => { input.scan.matches = [{ vulnerability: { id: "fixture", severity: "Low" } }]; }],
    ...[undefined, null, "", 1, "high", "HIGH", "Other"].map((severity) => [
      `severity ${String(severity)}`, (input) => { input.scan.matches = [match(severity)]; },
    ]),
  ];
  for (const [name, mutate] of cases) {
    await t.test(name, async (subtest) => {
      const input = await fixture(subtest);
      mutate(input);
      const result = await verify(input);
      assert.notEqual(result.status, 0);
      assert.doesNotMatch(result.stdout, /validation passed/iu);
      await assert.rejects(readFile(input.paths.summary, "utf8"), { code: "ENOENT" });
    });
  }
});

test("pre-validator가 image/Git/license/x265 계약을 계속 차단한다", async (t) => {
  const cases = [
    ["image 불일치", (input) => { input.sbom.metadata.properties[0].value = `sha256:${"c".repeat(64)}`; }],
    ["Git 불일치", (input) => { input.sbom.metadata.properties[1].value = "d".repeat(40); }],
    ["license 불일치", (input) => { input.sbom.components[0].licenses = []; }],
    ["x265 SBOM", (input) => { input.sbom.components.push({ name: "x265-libs" }); }],
    ["x265 scan", (input) => { input.scan.matches = [match("Low")]; input.scan.matches[0].artifact.name = "x265-libs"; }],
  ];
  for (const [name, mutate] of cases) {
    await t.test(name, async (subtest) => {
      const input = await fixture(subtest);
      mutate(input);
      const result = await verify(input);
      assert.notEqual(result.status, 0);
      assert.doesNotMatch(result.stdout, /validation passed/iu);
    });
  }
});
