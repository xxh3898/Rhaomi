import assert from "node:assert/strict";

const SEVERITIES = new Set([
  "Unknown", "Negligible", "Low", "Medium", "High", "Critical",
]);

function record(value) {
  assert(value && typeof value === "object" && !Array.isArray(value),
    "scanner record 형식이 올바르지 않습니다.");
  return value;
}

export function scanSeverityCounts(value) {
  const scan = record(value);
  const descriptor = record(scan.descriptor);
  assert.equal(descriptor.name, "grype", "Grype scanner descriptor가 아닙니다.");
  assert.equal(descriptor.version, "0.104.1", "고정 Grype version과 다릅니다.");
  assert.equal(record(record(descriptor.db).status).valid, true,
    "유효한 Grype database evidence가 없습니다.");
  assert(Array.isArray(scan.matches), "scanner matches 목록이 없습니다.");

  const counts = {};
  for (const value of scan.matches) {
    const match = record(value);
    const artifact = record(match.artifact);
    const vulnerability = record(match.vulnerability);
    assert(typeof artifact.name === "string" && artifact.name.trim().length > 0,
      "scanner artifact identity가 없습니다.");
    assert(typeof vulnerability.id === "string" && vulnerability.id.trim().length > 0,
      "scanner vulnerability identity가 없습니다.");
    assert(SEVERITIES.has(vulnerability.severity), "유효한 명시적 severity가 필요합니다.");
    counts[vulnerability.severity] = (counts[vulnerability.severity] ?? 0) + 1;
  }
  return Object.fromEntries(Object.entries(counts).sort(([left], [right]) =>
    left.localeCompare(right, "en"),
  ));
}

export function assertNoBlockingSeverities(value) {
  const counts = record(value);
  for (const [severity, count] of Object.entries(counts)) {
    assert(SEVERITIES.has(severity) && Number.isSafeInteger(count) && count >= 0,
      "severity count 형식이 올바르지 않습니다.");
  }
  assert.equal(counts.High ?? 0, 0, "High finding이 있어 image 검증을 차단합니다.");
  assert.equal(counts.Critical ?? 0, 0, "Critical finding이 있어 image 검증을 차단합니다.");
}
