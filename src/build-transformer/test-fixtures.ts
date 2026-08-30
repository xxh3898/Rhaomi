import type {
  BuildGalleryItemV1,
  BuildMediaAssetV1,
  BuildSnapshotV2,
} from "./contracts.mts";

export const IDS = {
  breedA: "00000000-0000-4000-8000-000000000001",
  breedB: "00000000-0000-4000-8000-000000000002",
  serviceA: "00000000-0000-4000-8000-000000000011",
  serviceB: "00000000-0000-4000-8000-000000000012",
  galleryA: "00000000-0000-4000-8000-000000000021",
  galleryB: "00000000-0000-4000-8000-000000000022",
  noticeA: "00000000-0000-4000-8000-000000000031",
  mediaJpeg: "00000000-0000-4000-8000-000000000041",
  mediaPng: "00000000-0000-4000-8000-000000000042",
  mediaUnused: "00000000-0000-4000-8000-000000000043",
} as const;

export function mediaAsset(
  id: string,
  contentType: BuildMediaAssetV1["contentType"],
  byteSize: number,
  width: number,
  height: number,
): BuildMediaAssetV1 {
  return { id, contentType, byteSize, width, height };
}

export function galleryItem(
  overrides: Partial<BuildGalleryItemV1> = {},
): BuildGalleryItemV1 {
  return {
    id: IDS.galleryA,
    dogName: "라오미",
    breedId: IDS.breedA,
    primaryServiceId: IDS.serviceA,
    coverImageId: IDS.mediaJpeg,
    beforeImageId: IDS.mediaPng,
    afterImageId: null,
    summary: "합성 갤러리 요약",
    altText: "미용을 마친 강아지 합성 이미지",
    featured: true,
    sortOrder: 0,
    performedAt: "2026-08-29T09:00:00.123456Z",
    publishedAt: "2026-08-29T10:00:00.123456Z",
    ...overrides,
  };
}

export function snapshotFixture(
  overrides: Partial<BuildSnapshotV2> = {},
): BuildSnapshotV2 {
  return {
    schemaVersion: 2,
    contentRevision: "14",
    publishGeneration: "7",
    generatedAt: "2026-08-30T00:00:00.123456Z",
    shop: {
      shopName: "라오미펫",
      regionLabel: "서울",
      businessType: "반려견 미용",
      phone: "02-123-4567",
      address: "서울시 테스트구 합성로 1",
      openingTime: "10:00",
      closingTime: "19:00",
      closedWeekday: "MONDAY",
      parkingAvailable: true,
      parkingNote: "합성 주차 안내",
      heroTitle: "라오미펫",
      heroDescription: "합성 Hero 설명",
      groomerName: "은총쌤",
      groomerIntro: "합성 소개",
      reservationNotice: "합성 예약 안내",
      heroImageId: IDS.mediaJpeg,
      heroImageAltText: "라오미펫 Hero 합성 이미지",
      groomerImageId: IDS.mediaPng,
      groomerImageAltText: "미용사 합성 이미지",
      ogImageId: null,
      instagramUrl: "https://example.com/instagram",
      naverBlogUrl: null,
      naverMapUrl: "https://example.com/naver-map",
      kakaoMapUrl: null,
      naverTalktalkUrl: null,
      kakaoChannelUrl: null,
    },
    services: [
      {
        id: IDS.serviceB,
        name: "스파",
        slug: "spa",
        description: "합성 스파 설명",
        priceText: "상담 후 안내",
        sortOrder: 1,
      },
      {
        id: IDS.serviceA,
        name: "전체 미용",
        slug: "full-grooming",
        description: "합성 전체 미용 설명",
        priceText: "50,000원부터",
        sortOrder: 0,
      },
    ],
    breeds: [
      {
        id: IDS.breedB,
        name: "푸들",
        slug: "poodle",
        description: null,
        sortOrder: 1,
      },
      {
        id: IDS.breedA,
        name: "말티즈",
        slug: "maltese",
        description: "합성 견종 설명",
        sortOrder: 0,
      },
    ],
    galleryItems: [galleryItem()],
    notices: [
      {
        id: IDS.noticeA,
        title: "합성 공지",
        slug: "synthetic-notice",
        summary: "합성 공지 요약",
        bodyMarkdown: "**합성 Markdown 본문**",
        pinned: true,
        publishedAt: "2026-08-29T11:00:00.123456Z",
        expiresAt: "2026-08-31T11:00:00.123456Z",
      },
    ],
    mediaAssets: [
      mediaAsset(IDS.mediaJpeg, "image/jpeg", 1_024, 1_920, 1_080),
      mediaAsset(IDS.mediaPng, "image/png", 2_048, 640, 480),
    ],
    ...overrides,
  };
}
