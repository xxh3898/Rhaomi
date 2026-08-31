import { describe, expect, it } from "vitest";

import { snapshotFixture } from "../build-transformer/test-fixtures";
import type { PublicMediaManifestV2 } from "../build-transformer/transformer.mts";
import {
  mediaPathHash,
  parseGeneratedArtifactsV2,
} from "./contracts.mts";

const HASH = "a".repeat(64);

function generatedFixture() {
  const { mediaAssets, ...content } = snapshotFixture({
    shop: {
      ...snapshotFixture().shop,
      heroImageId: null,
      heroImageAltText: null,
      groomerImageId: null,
      groomerImageAltText: null,
    },
    galleryItems: [],
    mediaAssets: [],
  });
  void mediaAssets;
  const mediaManifest: PublicMediaManifestV2 = {
    schemaVersion: 2,
    contentRevision: content.contentRevision,
    publishGeneration: content.publishGeneration,
    items: [],
  };
  return { content, mediaManifest };
}

describe("parseGeneratedArtifactsV2", () => {
  it("preserves canonical int64 strings exactly", () => {
    const fixture = generatedFixture();
    const content = {
      ...fixture.content,
      contentRevision: "9007199254740993",
      publishGeneration: "9223372036854775807",
    };
    const manifest = {
      ...fixture.mediaManifest,
      contentRevision: content.contentRevision,
      publishGeneration: content.publishGeneration,
    };

    expect(parseGeneratedArtifactsV2(content, manifest)).toMatchObject({
      content: {
        contentRevision: "9007199254740993",
        publishGeneration: "9223372036854775807",
      },
    });
  });

  it("rejects mismatched artifact generations and numeric values", () => {
    const fixture = generatedFixture();
    expect(() =>
      parseGeneratedArtifactsV2(fixture.content, {
        ...fixture.mediaManifest,
        publishGeneration: "8",
      }),
    ).toThrowError(/invalid/i);
    expect(() =>
      parseGeneratedArtifactsV2(
        { ...fixture.content, publishGeneration: 7 },
        fixture.mediaManifest,
      ),
    ).toThrowError(/invalid/i);
  });

  it("rejects unknown manifest keys and unsafe public paths", () => {
    const fixture = generatedFixture();
    const mediaId = "00000000-0000-4000-8000-000000000041";
    const content = {
      ...fixture.content,
      shop: {
        ...fixture.content.shop,
        heroImageId: mediaId,
        heroImageAltText: "합성 Hero 이미지",
      },
    };
    const variant = {
      profile: "HERO",
      format: "jpeg",
      width: 768,
      height: 432,
      byteSize: 123,
      publicPath: `/generated/media/${HASH}.jpeg`,
    };
    const manifest = {
      ...fixture.mediaManifest,
      items: [{ mediaId, variants: [variant] }],
    };
    expect(parseGeneratedArtifactsV2(content, manifest).mediaManifest.items).toHaveLength(1);
    expect(() =>
      parseGeneratedArtifactsV2(content, {
        ...manifest,
        unknown: true,
      }),
    ).toThrowError(/invalid/i);
    expect(() =>
      parseGeneratedArtifactsV2(content, {
        ...manifest,
        items: [
          {
            mediaId,
            variants: [{ ...variant, publicPath: "/uploads/master.jpg" }],
          },
        ],
      }),
    ).toThrowError(/invalid/i);
  });
});

describe("mediaPathHash", () => {
  it("extracts only canonical generated media hashes", () => {
    expect(mediaPathHash(`/generated/media/${HASH}.avif`)).toBe(HASH);
    expect(() => mediaPathHash("/generated/media/not-a-hash.jpeg")).toThrow();
  });
});
