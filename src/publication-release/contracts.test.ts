import { describe, expect, it } from "vitest";

import {
  compareGenerations,
  loadPublicationReleaseConfig,
  parseReleaseManifest,
  releaseIdFor,
} from "./contracts.mts";

const SHA = "a".repeat(40);
const DIGEST = `sha256:${"b".repeat(64)}`;

function environment() {
  return {
    RHAOMI_PUBLISHER_SOURCE_ROOT: "/workspace",
    RHAOMI_PUBLISHER_WORK_ROOT: "/state/publisher",
    RHAOMI_PUBLIC_RELEASE_ROOT: "/public/releases",
    RHAOMI_PUBLIC_CURRENT_LINK: "/public/current",
    RHAOMI_PUBLIC_PREVIOUS_LINK: "/public/previous",
    PUBLIC_SITE_URL: "https://site.example/",
    RHAOMI_CODE_SHA: SHA,
    RHAOMI_CODE_IMAGE_TAG: `sha-${SHA}`,
    RHAOMI_CODE_IMAGE_DIGEST: DIGEST,
    RHAOMI_FLYWAY_VERSION: "10",
    RHAOMI_SBOM_REFERENCE: DIGEST,
  };
}

function manifest(generatedAt = "2026-08-31T00:00:00.123456Z") {
  return {
    schemaVersion: 1,
    releaseId: releaseIdFor("9007199254740993", "9223372036854775807", SHA),
    contentRevision: "9007199254740993",
    publishGeneration: "9223372036854775807",
    generatedAt,
    codeSha: SHA,
    codeImageTag: `sha-${SHA}`,
    codeImageDigest: DIGEST,
    flywayVersion: "10",
    sbomReference: DIGEST,
    siteSha256: "c".repeat(64),
  } as const;
}

describe("publication release contracts", () => {
  it("loads bounded non-secret release settings", () => {
    expect(loadPublicationReleaseConfig(environment())).toMatchObject({
      releaseRoot: "/public/releases",
      currentLink: "/public/current",
      buildTimeoutMs: 600_000,
      releaseRetention: 5,
    });
  });

  it("rejects non-https domains and non-sibling switch links", () => {
    expect(() =>
      loadPublicationReleaseConfig({
        ...environment(),
        PUBLIC_SITE_URL: "http://site.example/",
      }),
    ).toThrowError(/invalid/i);
    expect(() =>
      loadPublicationReleaseConfig({
        ...environment(),
        RHAOMI_RELEASE_RETENTION: "0",
      }),
    ).toThrowError(/invalid/i);
    expect(() =>
      loadPublicationReleaseConfig({
        ...environment(),
        RHAOMI_PUBLIC_CURRENT_LINK: "/other/current",
      }),
    ).toThrowError(/invalid/i);
  });

  it("orders full positive-long generations only with BigInt", () => {
    expect(compareGenerations("9007199254740993", "9007199254740992")).toBe(1);
    expect(
      compareGenerations("9223372036854775807", "9223372036854775807"),
    ).toBe(0);
  });

  it("keeps manifest int64 strings exact and rejects numeric fields", () => {
    const value = manifest();
    expect(parseReleaseManifest(value)).toEqual(value);
    expect(() =>
      parseReleaseManifest({ ...value, publishGeneration: 9_007_199_254_740_992 }),
    ).toThrowError(/invalid/i);
  });

  it.each([
    "2026-02-31T00:00:00Z",
    "2026-04-31T00:00:00Z",
    "2026-01-01T24:00:00Z",
    "2026-01-01T00:60:00Z",
    "2026-01-01T00:00:60Z",
  ])("rejects non-existent manifest instant %s", (generatedAt) => {
    expect(() => parseReleaseManifest(manifest(generatedAt))).toThrowError(
      /invalid/i,
    );
  });

  it.each([
    "2028-02-29T23:59:59Z",
    "2028-02-29T23:59:59.123456Z",
  ])("accepts strict manifest instant %s", (generatedAt) => {
    expect(parseReleaseManifest(manifest(generatedAt)).generatedAt).toBe(
      generatedAt,
    );
  });
});
