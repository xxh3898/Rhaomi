// @vitest-environment node

import { describe, expect, it } from "vitest";

import { parseBuildSnapshotV2 } from "./contracts.mts";
import { BuildTransformError } from "./errors.mts";
import {
  IDS,
  galleryItem,
  mediaAsset,
  snapshotFixture,
} from "./test-fixtures";

function expectInvalid(value: unknown): void {
  expect(() => parseBuildSnapshotV2(value)).toThrowError(
    expect.objectContaining<Partial<BuildTransformError>>({
      code: "SNAPSHOT_INVALID",
    }),
  );
}

describe("BuildSnapshotV2 strict validator", () => {
  it("exact v2 shape를 통과시키고 server array ordering을 보존한다", () => {
    const snapshot = parseBuildSnapshotV2(snapshotFixture());

    expect(snapshot.services.map((item) => item.id)).toEqual([
      IDS.serviceB,
      IDS.serviceA,
    ]);
    expect(snapshot.breeds.map((item) => item.id)).toEqual([
      IDS.breedB,
      IDS.breedA,
    ]);
    expect(snapshot.generatedAt).toBe("2026-08-30T00:00:00.123456Z");
  });

  it("Breed와 Service description에 backend에 없는 길이 제한을 추가하지 않는다", () => {
    const breedDescription = "견".repeat(10_001);
    const serviceDescription = "서".repeat(10_001);
    const source = snapshotFixture();

    const snapshot = parseBuildSnapshotV2({
      ...source,
      breeds: source.breeds.map((item, index) =>
        index === 0 ? { ...item, description: breedDescription } : item,
      ),
      services: source.services.map((item, index) =>
        index === 0 ? { ...item, description: serviceDescription } : item,
      ),
    });

    expect(Array.from(snapshot.breeds[0].description ?? "")).toHaveLength(
      10_001,
    );
    expect(Array.from(snapshot.services[0].description)).toHaveLength(10_001);
  });

  it("ContentFields가 Java strip에서 보존하는 separator를 exact snapshot에서 허용한다", () => {
    for (const separator of ["\u00a0", "\u2007", "\u202f", "\ufeff"]) {
      const source = snapshotFixture();
      const wrapped = `${separator}내용${separator}`;
      const snapshot = parseBuildSnapshotV2({
        ...source,
        breeds: source.breeds.map((item, index) =>
          index === 0
            ? { ...item, name: wrapped, description: wrapped }
            : item,
        ),
        services: source.services.map((item, index) =>
          index === 0
            ? {
                ...item,
                name: wrapped,
                description: wrapped,
                priceText: wrapped,
              }
            : item,
        ),
        notices: source.notices.map((item) => ({
          ...item,
          title: wrapped,
          summary: wrapped,
          bodyMarkdown: wrapped,
        })),
      });

      expect(snapshot.breeds[0].description).toBe(wrapped);
      expect(snapshot.services[0].description).toBe(wrapped);
      expect(snapshot.notices[0].bodyMarkdown).toBe(wrapped);
    }
  });

  it("Shop과 Gallery가 보존하는 U+FEFF edge text를 허용한다", () => {
    const source = snapshotFixture();
    const wrapped = "\ufeff내용\ufeff";
    const snapshot = parseBuildSnapshotV2({
      ...source,
      shop: {
        ...source.shop,
        shopName: wrapped,
        heroTitle: wrapped,
      },
      galleryItems: source.galleryItems.map((item) => ({
        ...item,
        dogName: wrapped,
        summary: wrapped,
        altText: wrapped,
      })),
    });

    expect(snapshot.shop.shopName).toBe(wrapped);
    expect(snapshot.galleryItems[0].altText).toBe(wrapped);
  });

  it("Shop과 Gallery의 custom Unicode whitespace edge를 noncanonical로 거부한다", () => {
    for (const separator of ["\u00a0", "\u2007", "\u202f", "\u001c"]) {
      const source = snapshotFixture();
      expectInvalid({
        ...source,
        shop: { ...source.shop, shopName: `${separator}내용` },
      });
      expectInvalid({
        ...source,
        galleryItems: source.galleryItems.map((item) => ({
          ...item,
          altText: `내용${separator}`,
        })),
      });
    }
  });

  it("Build API의 Java UTF-16 length와 Shop code-point length를 구분한다", () => {
    const source = snapshotFixture();
    expectInvalid({
      ...source,
      services: source.services.map((item, index) =>
        index === 0 ? { ...item, name: "🐶".repeat(51) } : item,
      ),
    });
    expectInvalid({
      ...source,
      galleryItems: source.galleryItems.map((item) => ({
        ...item,
        altText: "🐶".repeat(151),
      })),
    });

    const parsed = parseBuildSnapshotV2({
      ...source,
      shop: { ...source.shop, heroTitle: "🐶".repeat(200) },
    });
    expect(Array.from(parsed.shop.heroTitle ?? "")).toHaveLength(200);
  });

  it("Breed와 Service description의 nullable/nonblank canonical 계약을 유지한다", () => {
    const source = snapshotFixture();
    const nullableBreed = parseBuildSnapshotV2({
      ...source,
      breeds: source.breeds.map((item, index) =>
        index === 0 ? { ...item, description: null } : item,
      ),
    });

    expect(nullableBreed.breeds[0].description).toBeNull();
    for (const description of [
      "   ",
      " 정규화되지 않은 설명 ",
      "\u001c정규화되지 않은 설명",
      "정규화되지 않은 설명\u2003",
    ]) {
      expectInvalid({
        ...source,
        breeds: source.breeds.map((item, index) =>
          index === 0 ? { ...item, description } : item,
        ),
      });
    }
    for (const description of ["   ", "\t\n"]) {
      expectInvalid({
        ...source,
        services: source.services.map((item, index) =>
          index === 0 ? { ...item, description } : item,
        ),
      });
    }
  });

  it("unknown 또는 missing top-level/entity field를 거부한다", () => {
    const snapshot = snapshotFixture();
    const missing = Object.fromEntries(
      Object.entries(snapshot).filter(([key]) => key !== "notices"),
    );

    expectInvalid({ ...snapshot, storagePath: "/private/path" });
    expectInvalid(missing);
    expectInvalid({
      ...snapshot,
      services: [{ ...snapshot.services[0], createdBy: IDS.breedA }],
    });
  });

  it("canonical int64 string boundary를 full Java long 범위에서 검증한다", () => {
    for (const value of [
      "9007199254740991",
      "9007199254740992",
      "9007199254740993",
      "9223372036854775807",
    ]) {
      const parsed = parseBuildSnapshotV2({
        ...snapshotFixture(),
        contentRevision: value,
        publishGeneration: value,
      });
      expect(parsed.contentRevision).toBe(value);
      expect(parsed.publishGeneration).toBe(value);
    }

    expect(parseBuildSnapshotV2({
      ...snapshotFixture(),
      contentRevision: "0",
    }).contentRevision).toBe("0");
    expectInvalid({ ...snapshotFixture(), publishGeneration: "0" });
  });

  it.each([
    "",
    "00",
    "01",
    "+1",
    "-1",
    " 1",
    "1 ",
    "1.0",
    "1e3",
    "9223372036854775808",
  ])("noncanonical 또는 overflow int64 string %j을 거부한다", (value) => {
    expectInvalid({ ...snapshotFixture(), contentRevision: value });
    expectInvalid({ ...snapshotFixture(), publishGeneration: value });
  });

  it("V1 schema와 revision/generation JSON number를 거부한다", () => {
    expectInvalid({ ...snapshotFixture(), schemaVersion: 1 });
    expectInvalid({ ...snapshotFixture(), schemaVersion: 3 });
    expectInvalid({ ...snapshotFixture(), contentRevision: 14 });
    expectInvalid({ ...snapshotFixture(), publishGeneration: 7 });
  });

  it("collection duplicate id와 invalid UUID/sortOrder를 거부한다", () => {
    const snapshot = snapshotFixture();
    expectInvalid({
      ...snapshot,
      breeds: [snapshot.breeds[0], { ...snapshot.breeds[1], id: IDS.breedB }],
    });
    expectInvalid({
      ...snapshot,
      galleryItems: [{ ...snapshot.galleryItems[0], id: "not-a-uuid" }],
    });
    expectInvalid({
      ...snapshot,
      services: [{ ...snapshot.services[0], sortOrder: -1 }],
    });
  });

  it("Gallery broken breed/service 관계와 before=after를 거부한다", () => {
    const snapshot = snapshotFixture();
    expectInvalid({
      ...snapshot,
      galleryItems: [galleryItem({ breedId: IDS.mediaUnused })],
    });
    expectInvalid({
      ...snapshot,
      galleryItems: [galleryItem({ primaryServiceId: IDS.mediaUnused })],
    });
    expectInvalid({
      ...snapshot,
      galleryItems: [
        galleryItem({
          beforeImageId: IDS.mediaPng,
          afterImageId: IDS.mediaPng,
        }),
      ],
    });
  });

  it("Gallery cover/alt/published eligibility invariant를 거부한다", () => {
    const snapshot = snapshotFixture();
    expectInvalid({
      ...snapshot,
      galleryItems: [galleryItem({ coverImageId: "not-a-uuid" })],
    });
    expectInvalid({
      ...snapshot,
      galleryItems: [galleryItem({ altText: "   " })],
    });
    expectInvalid({
      ...snapshot,
      galleryItems: [
        galleryItem({ publishedAt: "2026-08-30T00:00:00.123457Z" }),
      ],
    });
  });

  it("Shop image-alt pair와 HTTPS URL invariant를 거부한다", () => {
    const snapshot = snapshotFixture();
    expectInvalid({
      ...snapshot,
      shop: { ...snapshot.shop, heroImageAltText: null },
    });
    expectInvalid({
      ...snapshot,
      shop: { ...snapshot.shop, instagramUrl: "http://example.com" },
    });
  });

  it("missing/unreferenced/duplicate media manifest drift를 거부한다", () => {
    const snapshot = snapshotFixture();
    expectInvalid({ ...snapshot, mediaAssets: [snapshot.mediaAssets[0]] });
    expectInvalid({
      ...snapshot,
      mediaAssets: [
        ...snapshot.mediaAssets,
        mediaAsset(IDS.mediaUnused, "image/jpeg", 100, 10, 10),
      ],
    });
    expectInvalid({
      ...snapshot,
      mediaAssets: [snapshot.mediaAssets[0], { ...snapshot.mediaAssets[1], id: IDS.mediaJpeg }],
    });
  });

  it("Notice blank body, malformed timestamp와 invalid window/eligibility를 거부한다", () => {
    const snapshot = snapshotFixture();
    const notice = snapshot.notices[0];
    expectInvalid({
      ...snapshot,
      notices: [{ ...notice, bodyMarkdown: "\t\n" }],
    });
    expectInvalid({
      ...snapshot,
      notices: [{ ...notice, publishedAt: "2026-08-29T11:00:00.123456789Z" }],
    });
    expectInvalid({
      ...snapshot,
      notices: [{ ...notice, expiresAt: notice.publishedAt }],
    });
    expectInvalid({
      ...snapshot,
      notices: [{ ...notice, publishedAt: "2026-08-30T00:00:00.123457Z" }],
    });
    expectInvalid({
      ...snapshot,
      notices: [{ ...notice, expiresAt: snapshot.generatedAt }],
    });
  });

  it("media type/size/dimension/pixel limit를 strict shape 단계에서 거부한다", () => {
    const snapshot = snapshotFixture();
    expectInvalid({
      ...snapshot,
      mediaAssets: [
        { ...snapshot.mediaAssets[0], contentType: "image/webp" },
        snapshot.mediaAssets[1],
      ],
    });
    expectInvalid({
      ...snapshot,
      mediaAssets: [
        { ...snapshot.mediaAssets[0], byteSize: 30 * 1024 * 1024 + 1 },
        snapshot.mediaAssets[1],
      ],
    });
    expectInvalid({
      ...snapshot,
      mediaAssets: [
        { ...snapshot.mediaAssets[0], width: 10_000, height: 7_000 },
        snapshot.mediaAssets[1],
      ],
    });
  });
});
