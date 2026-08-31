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
    RHAOMI_FLYWAY_VERSION: "9",
    RHAOMI_SBOM_REFERENCE: DIGEST,
  };
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
    const manifest = {
      schemaVersion: 1,
      releaseId: releaseIdFor("9007199254740993", "9223372036854775807", SHA),
      contentRevision: "9007199254740993",
      publishGeneration: "9223372036854775807",
      generatedAt: "2026-08-31T00:00:00.123456Z",
      codeSha: SHA,
      codeImageTag: `sha-${SHA}`,
      codeImageDigest: DIGEST,
      flywayVersion: "9",
      sbomReference: DIGEST,
      siteSha256: "c".repeat(64),
    };
    expect(parseReleaseManifest(manifest)).toEqual(manifest);
    expect(() =>
      parseReleaseManifest({ ...manifest, publishGeneration: 9_007_199_254_740_992 }),
    ).toThrowError(/invalid/i);
  });
});
