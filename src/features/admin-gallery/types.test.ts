import { describe, expect, it } from "vitest";

import {
  buildGalleryCreateRequest,
  buildGalleryUpdateRequest,
  EMPTY_GALLERY_DRAFT,
  galleryItemToDraft,
  instantToLocalDateTimeValue,
  isGalleryItem,
  localDateTimeValueToInstant,
  type GalleryItem,
} from "./types";

const GALLERY: GalleryItem = {
  id: "d64047ee-93fe-4f87-949f-493d47ad6ee4",
  status: "draft",
  dogName: "보리",
  breedId: "1252ef09-6758-4af2-963d-9a65d0f369cf",
  primaryServiceId: "6c3cf849-26f4-44f8-b51b-899ba7937b4a",
  coverImageId: "429c206f-59af-40c4-9252-ced9b352f1fc",
  beforeImageId: null,
  afterImageId: null,
  summary: "여름 미용",
  altText: "미용을 마친 보리",
  featured: true,
  sortOrder: 10,
  performedAt: "2026-08-30T01:02:03.123456Z",
  publishedAt: "2026-09-01T04:05:06.654321Z",
  createdAt: "2026-08-30T00:00:00Z",
  updatedAt: "2026-08-30T00:00:00.000001Z",
  createdBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  updatedBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
};

describe("Gallery types", () => {
  it("exact response shape와 nullable UUID·Instant·normalized text를 검증한다", () => {
    expect(isGalleryItem(GALLERY)).toBe(true);
    expect(isGalleryItem({ ...GALLERY, storageKey: "private/path" })).toBe(false);
    expect(isGalleryItem({ ...GALLERY, breedId: "not-a-uuid" })).toBe(false);
    expect(isGalleryItem({ ...GALLERY, publishedAt: "2026-08-30 00:00" })).toBe(
      false,
    );
    expect(isGalleryItem({ ...GALLERY, dogName: " 보리" })).toBe(false);
    expect(isGalleryItem({ ...GALLERY, sortOrder: -1 })).toBe(false);
  });

  it("microsecond canonical Instant를 native local control과 unchanged full PUT 사이에서 보존한다", () => {
    const instant = "2026-08-30T01:02:03.123456Z";
    const draft = galleryItemToDraft({ ...GALLERY, performedAt: instant });

    expect(draft.performedAt).toMatch(/\.123$/);
    expect(buildGalleryUpdateRequest(draft)?.performedAt).toBe(instant);
    expect(localDateTimeValueToInstant(draft.performedAt)).toMatch(/\.123000Z$/);
    expect(instantToLocalDateTimeValue(null)).toBe("");
    expect(localDateTimeValueToInstant("")).toBe(null);
    expect(localDateTimeValueToInstant("2026-02-30T12:00")).toBeUndefined();
  });

  it("create request에서 status·audit을 제외하고 blank와 future publishedAt을 정규화한다", () => {
    const futureInstant = "2099-12-31T23:59:59.123000Z";
    const draft = {
      ...EMPTY_GALLERY_DRAFT,
      dogName: "  보리  ",
      summary: "   ",
      featured: false,
      sortOrder: "",
      publishedAt: instantToLocalDateTimeValue(futureInstant),
    };

    const request = buildGalleryCreateRequest(draft);

    expect(request).toEqual({
      dogName: "보리",
      breedId: null,
      primaryServiceId: null,
      coverImageId: null,
      beforeImageId: null,
      afterImageId: null,
      summary: null,
      altText: null,
      featured: false,
      sortOrder: null,
      performedAt: null,
      publishedAt: futureInstant,
    });
    expect(request).not.toHaveProperty("status");
    expect(request).not.toHaveProperty("id");
    expect(request).not.toHaveProperty("updatedAt");
  });

  it("update request를 exact full mutable representation으로 만들고 before=after를 거부한다", () => {
    const draft = galleryItemToDraft({ ...GALLERY, status: "published" });

    expect(buildGalleryUpdateRequest(draft)).toEqual({
      status: "published",
      dogName: GALLERY.dogName,
      breedId: GALLERY.breedId,
      primaryServiceId: GALLERY.primaryServiceId,
      coverImageId: GALLERY.coverImageId,
      beforeImageId: null,
      afterImageId: null,
      summary: GALLERY.summary,
      altText: GALLERY.altText,
      featured: true,
      sortOrder: 10,
      performedAt: GALLERY.performedAt,
      publishedAt: GALLERY.publishedAt,
    });
    expect(
      buildGalleryUpdateRequest({
        ...draft,
        beforeImageId: GALLERY.coverImageId,
        afterImageId: GALLERY.coverImageId,
      }),
    ).toBeNull();
    expect(buildGalleryUpdateRequest({ ...draft, sortOrder: "" })).toBeNull();
    expect(
      buildGalleryUpdateRequest({ ...draft, performedAt: "invalid-local-time" }),
    ).toBeNull();
  });
});
