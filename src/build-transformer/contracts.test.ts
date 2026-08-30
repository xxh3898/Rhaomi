// @vitest-environment node

import { describe, expect, it } from "vitest";

import { parseBuildSnapshotV1 } from "./contracts.mts";
import { BuildTransformError } from "./errors.mts";
import {
  IDS,
  galleryItem,
  mediaAsset,
  snapshotFixture,
} from "./test-fixtures";

function expectInvalid(value: unknown): void {
  expect(() => parseBuildSnapshotV1(value)).toThrowError(
    expect.objectContaining<Partial<BuildTransformError>>({
      code: "SNAPSHOT_INVALID",
    }),
  );
}

describe("BuildSnapshotV1 strict validator", () => {
  it("exact v1 shape를 통과시키고 server array ordering을 보존한다", () => {
    const snapshot = parseBuildSnapshotV1(snapshotFixture());

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

    const snapshot = parseBuildSnapshotV1({
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

  it("Breed와 Service description의 nullable/nonblank canonical 계약을 유지한다", () => {
    const source = snapshotFixture();
    const nullableBreed = parseBuildSnapshotV1({
      ...source,
      breeds: source.breeds.map((item, index) =>
        index === 0 ? { ...item, description: null } : item,
      ),
    });

    expect(nullableBreed.breeds[0].description).toBeNull();
    for (const description of ["   ", " 정규화되지 않은 설명 "]) {
      expectInvalid({
        ...source,
        breeds: source.breeds.map((item, index) =>
          index === 0 ? { ...item, description } : item,
        ),
      });
    }
    expectInvalid({
      ...source,
      services: source.services.map((item, index) =>
        index === 0 ? { ...item, description: "\t\n" } : item,
      ),
    });
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

  it("future schemaVersion과 unsafe revision/generation을 거부한다", () => {
    expectInvalid({ ...snapshotFixture(), schemaVersion: 2 });
    expectInvalid({
      ...snapshotFixture(),
      contentRevision: Number.MAX_SAFE_INTEGER + 1,
    });
    expectInvalid({ ...snapshotFixture(), publishGeneration: 0 });
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
