import { describe, expect, it, vi } from "vitest";

import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminShopSettingsApi } from "./api";
import {
  buildShopSettingsRequest,
  EMPTY_SHOP_SETTINGS_DRAFT,
  isShopSettingsResponse,
  shopSettingsResponseToDraft,
  SHOP_SETTINGS_AUDIT_KEYS,
  SHOP_SETTINGS_MUTABLE_KEYS,
  type ShopSettingsResponse,
} from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";

function shopSettings(
  overrides: Partial<ShopSettingsResponse> = {},
): ShopSettingsResponse {
  return {
    shopName: "라오미펫",
    regionLabel: "용인 처인구",
    businessType: "애견미용실",
    phone: "031-123-4567",
    address: "경기도 용인시 처인구 테스트로 1",
    openingTime: "10:00",
    closingTime: "19:00",
    closedWeekday: "MONDAY",
    parkingAvailable: true,
    parkingNote: null,
    heroTitle: null,
    heroDescription: null,
    groomerName: null,
    groomerIntro: null,
    reservationNotice: null,
    heroImageId: null,
    heroImageAltText: null,
    groomerImageId: null,
    groomerImageAltText: null,
    ogImageId: null,
    instagramUrl: null,
    naverBlogUrl: null,
    naverMapUrl: null,
    kakaoMapUrl: null,
    naverTalktalkUrl: null,
    kakaoChannelUrl: null,
    createdAt: "2026-08-30T00:00:00Z",
    updatedAt: "2026-08-30T00:00:00.123456Z",
    createdBy: ACTOR_ID,
    updatedBy: ACTOR_ID,
    ...overrides,
  };
}

function createTransport(): AdminApiTransport {
  return {
    requestAuthenticatedJson: vi.fn().mockResolvedValue(shopSettings()),
    requestJsonMutation: vi.fn().mockResolvedValue(shopSettings()),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
  };
}

describe("AdminShopSettingsApi", () => {
  it("strict response shape와 audit UUID·Instant를 검증한다", () => {
    expect(isShopSettingsResponse(shopSettings())).toBe(true);
    expect(isShopSettingsResponse({ ...shopSettings(), storageKey: "private/path" })).toBe(
      false,
    );
    expect(isShopSettingsResponse({ ...shopSettings(), openingTime: "10:00:00" })).toBe(
      false,
    );
    expect(isShopSettingsResponse({ ...shopSettings(), updatedBy: "not-a-uuid" })).toBe(
      false,
    );
  });

  it("GET과 PUT을 exact relative singleton path로 보낸다", async () => {
    const transport = createTransport();
    const api = new AdminShopSettingsApi(transport);
    const request = buildShopSettingsRequest({
      ...EMPTY_SHOP_SETTINGS_DRAFT,
      shopName: "라오미펫",
      regionLabel: "용인 처인구",
      businessType: "애견미용실",
      phone: "031-123-4567",
      address: "경기도 용인시",
      openingTime: "10:00",
      closingTime: "19:00",
      parkingAvailable: true,
    });
    expect(request).not.toBeNull();

    await api.get();
    await api.put(request!);

    expect(transport.requestAuthenticatedJson).toHaveBeenCalledWith(
      "/api/admin/shop-settings",
      isShopSettingsResponse,
    );
    expect(transport.requestJsonMutation).toHaveBeenCalledWith(
      "/api/admin/shop-settings",
      "PUT",
      request,
      isShopSettingsResponse,
    );
  });

  it("full request에 mutable key 26개를 모두 넣고 audit key를 제외한다", () => {
    const request = buildShopSettingsRequest({
      ...EMPTY_SHOP_SETTINGS_DRAFT,
      shopName: " 라오미펫 ",
      regionLabel: "용인",
      businessType: "애견미용실",
      phone: "031-123-4567",
      address: "용인시",
      openingTime: "10:00",
      closingTime: "19:00",
      parkingAvailable: false,
      parkingNote: "   ",
      heroTitle: " 대표 문구 ",
    });

    expect(request).not.toBeNull();
    expect(Object.keys(request!).sort()).toEqual([...SHOP_SETTINGS_MUTABLE_KEYS].sort());
    expect(request?.parkingNote).toBeNull();
    expect(request?.heroTitle).toBe(" 대표 문구 ");
    for (const auditKey of SHOP_SETTINGS_AUDIT_KEYS) {
      expect(request).not.toHaveProperty(auditKey);
    }
  });

  it("image clear는 alt도 null로 만들고 parking 미선택은 request를 만들지 않는다", () => {
    expect(buildShopSettingsRequest(EMPTY_SHOP_SETTINGS_DRAFT)).toBeNull();
    const request = buildShopSettingsRequest({
      ...EMPTY_SHOP_SETTINGS_DRAFT,
      parkingAvailable: true,
      heroImageAltText: "남아 있으면 안 되는 설명",
      groomerImageAltText: "남아 있으면 안 되는 설명",
    });
    expect(request?.heroImageAltText).toBeNull();
    expect(request?.groomerImageAltText).toBeNull();
  });

  it("server canonical nullable response를 빈 form input으로 변환한다", () => {
    const draft = shopSettingsResponseToDraft(shopSettings());
    expect(draft.parkingNote).toBe("");
    expect(draft.heroTitle).toBe("");
    expect(draft.heroImageId).toBeNull();
    expect(draft.parkingAvailable).toBe(true);
  });
});
