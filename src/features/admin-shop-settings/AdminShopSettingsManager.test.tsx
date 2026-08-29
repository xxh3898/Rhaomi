import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";
import type { MediaItem } from "@/features/admin-media/types";

import { AdminShopSettingsManager } from "./AdminShopSettingsManager";
import {
  SHOP_SETTINGS_AUDIT_KEYS,
  SHOP_SETTINGS_MUTABLE_KEYS,
  type ShopSettingsResponse,
} from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const ACTIVE_ID = "d64047ee-93fe-4f87-949f-493d47ad6ee4";
const ARCHIVED_ID = "1252ef09-6758-4af2-963d-9a65d0f369cf";
const MISSING_ID = "6c3cf849-26f4-44f8-b51b-899ba7937b4a";

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
    heroTitle: "반려견을 위한 편안한 미용",
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

function mediaItem(overrides: Partial<MediaItem> = {}): MediaItem {
  return {
    id: ACTIVE_ID,
    status: "active",
    sourceContentType: "image/heic",
    contentType: "image/jpeg",
    sourceByteSize: 2_048,
    byteSize: 1_024,
    width: 1_200,
    height: 900,
    createdAt: "2026-08-30T00:00:00Z",
    updatedAt: "2026-08-30T00:00:00Z",
    createdBy: ACTOR_ID,
    updatedBy: ACTOR_ID,
    ...overrides,
  };
}

function createTransport(
  overrides: Partial<AdminApiTransport> = {},
): AdminApiTransport {
  return {
    requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
      path === "/api/admin/shop-settings"
        ? Promise.resolve(shopSettings())
        : Promise.resolve([]),
    ),
    requestJsonMutation: vi.fn().mockResolvedValue(shopSettings()),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi
      .fn()
      .mockResolvedValue(new Blob(["image"], { type: "image/jpeg" })),
    ...overrides,
  };
}

function fillRequiredForm() {
  fireEvent.change(screen.getByLabelText(/매장명/), {
    target: { value: "라오미펫" },
  });
  fireEvent.change(screen.getByLabelText(/지역 표시/), {
    target: { value: "용인 처인구" },
  });
  fireEvent.change(screen.getByLabelText(/업종/), {
    target: { value: "애견미용실" },
  });
  fireEvent.change(screen.getByLabelText(/전화번호/), {
    target: { value: "031-123-4567" },
  });
  fireEvent.change(screen.getByLabelText(/주소/), {
    target: { value: "경기도 용인시" },
  });
  fireEvent.change(screen.getByLabelText(/영업 시작/), {
    target: { value: "10:00" },
  });
  fireEvent.change(screen.getByLabelText(/영업 종료/), {
    target: { value: "19:00" },
  });
  fireEvent.click(screen.getByLabelText("불가"));
}

let createObjectUrl: ReturnType<typeof vi.fn>;
let revokeObjectUrl: ReturnType<typeof vi.fn>;

beforeEach(() => {
  createObjectUrl = vi.fn(() => "blob:shop-settings-preview");
  revokeObjectUrl = vi.fn();
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: createObjectUrl,
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: revokeObjectUrl,
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AdminShopSettingsManager", () => {
  it("GET loading 뒤 200 canonical response로 full form을 채운다", async () => {
    let resolveShop: ((value: ShopSettingsResponse) => void) | undefined;
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) => {
      if (path === "/api/admin/shop-settings") {
        return new Promise<ShopSettingsResponse>((resolve) => {
          resolveShop = resolve;
        });
      }
      return Promise.resolve([]);
    });

    render(
      <AdminShopSettingsManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(screen.getByText("매장정보를 불러오고 있습니다.")).toHaveAttribute(
      "role",
      "status",
    );
    await waitFor(() =>
      expect(requestAuthenticatedJson).toHaveBeenCalledWith(
        "/api/admin/shop-settings",
        expect.any(Function),
      ),
    );
    resolveShop?.(shopSettings());

    expect(await screen.findByLabelText(/매장명/)).toHaveValue("라오미펫");
    expect(screen.getByLabelText(/영업 시작/)).toHaveValue("10:00");
    expect(screen.getByLabelText(/정기 휴무일/)).toHaveValue("MONDAY");
    expect(screen.getByLabelText("가능")).toBeChecked();
    expect(screen.getByLabelText(/Instagram/)).toHaveValue("");
  });

  it("404를 미초기화 empty form으로 처리하고 최초 full PUT 결과를 canonical state로 쓴다", async () => {
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) =>
      path === "/api/admin/shop-settings"
        ? Promise.reject(new AdminApiError("not-found"))
        : Promise.resolve([]),
    );
    const requestJsonMutation = vi.fn().mockResolvedValue(
      shopSettings({
        shopName: "라오미펫 정규화",
        closedWeekday: null,
        parkingAvailable: false,
      }),
    );
    render(
      <AdminShopSettingsManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByText(/아직 매장정보가 등록되지 않았습니다/)).toBeInTheDocument();
    expect(screen.getByLabelText(/매장명/)).toHaveValue("");
    fillRequiredForm();
    fireEvent.change(screen.getByLabelText(/주차 안내/), {
      target: { value: "   " },
    });
    await userEvent.setup().click(screen.getByRole("button", { name: "매장정보 저장" }));

    await waitFor(() => expect(requestJsonMutation).toHaveBeenCalledTimes(1));
    const [path, method, body] = requestJsonMutation.mock.calls[0]!;
    expect(path).toBe("/api/admin/shop-settings");
    expect(method).toBe("PUT");
    expect(Object.keys(body).sort()).toEqual([...SHOP_SETTINGS_MUTABLE_KEYS].sort());
    expect(body).toMatchObject({
      parkingAvailable: false,
      parkingNote: null,
      closedWeekday: null,
    });
    for (const auditKey of SHOP_SETTINGS_AUDIT_KEYS) {
      expect(body).not.toHaveProperty(auditKey);
    }
    expect(await screen.findByLabelText(/매장명/)).toHaveValue("라오미펫 정규화");
    expect(screen.getByRole("status")).toHaveTextContent("매장정보를 저장했습니다.");
  });

  it("generic GET error에서 form을 숨기고 explicit retry로 복구한다", async () => {
    let shopAttempt = 0;
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) => {
      if (path !== "/api/admin/shop-settings") {
        return Promise.resolve([]);
      }
      shopAttempt += 1;
      return shopAttempt === 1
        ? Promise.reject(new Error("repository detail"))
        : Promise.resolve(shopSettings());
    });
    const user = userEvent.setup();
    render(
      <AdminShopSettingsManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "매장정보를 불러오지 못했습니다.",
    );
    expect(screen.queryByLabelText(/매장명/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByLabelText(/매장명/)).toHaveValue("라오미펫");
    expect(requestAuthenticatedJson).toHaveBeenCalledTimes(3);
  });

  it("후속 save pending에서 중복 submit과 field 변경을 막고 server canonical response를 적용한다", async () => {
    let resolveSave: ((value: ShopSettingsResponse) => void) | undefined;
    const requestJsonMutation = vi.fn().mockReturnValue(
      new Promise<ShopSettingsResponse>((resolve) => {
        resolveSave = resolve;
      }),
    );
    const transport = createTransport({ requestJsonMutation });
    const user = userEvent.setup();
    render(
      <AdminShopSettingsManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const shopName = await screen.findByLabelText(/매장명/);
    await user.clear(shopName);
    await user.type(shopName, "  수정 이름  ");
    const saveButton = screen.getByRole("button", { name: "매장정보 저장" });
    await user.click(saveButton);
    fireEvent.submit(saveButton.closest("form")!);

    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "저장 중" })).toBeDisabled();
    expect(shopName).toBeDisabled();

    await act(async () => {
      resolveSave?.(shopSettings({ shopName: "수정 이름" }));
    });
    expect(await screen.findByLabelText(/매장명/)).toHaveValue("수정 이름");
    expect(transport.requestAuthenticatedJson).toHaveBeenCalledTimes(2);
  });

  it.each([
    [new AdminApiError("invalid-request"), "입력 형식을 확인해 주세요."],
    [
      new AdminApiError("business-hours-invalid"),
      "영업 종료 시간은 시작 시간보다 늦어야 합니다.",
    ],
    [
      new AdminApiError("shop-media-relation-invalid"),
      "Hero·미용사 이미지 설명과 선택한 미디어의 활성 상태를 확인해 주세요.",
    ],
    [
      new AdminApiError("forbidden"),
      "보안 요청을 확인할 수 없습니다. 다시 저장해 주세요.",
    ],
    [
      new Error("SQL constraint detail"),
      "매장정보를 저장하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.",
    ],
  ])("저장 실패를 frontend-owned message로 표시한다", async (error, message) => {
    const requestJsonMutation = vi.fn().mockRejectedValue(error);
    render(
      <AdminShopSettingsManager
        transport={createTransport({ requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await userEvent.setup().click(
      await screen.findByRole("button", { name: "매장정보 저장" }),
    );
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(alert).not.toHaveTextContent("SQL constraint detail");
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("active media 하나를 Hero·미용사·OG에 재사용하고 OG alt field는 만들지 않는다", async () => {
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) =>
      path === "/api/admin/shop-settings"
        ? Promise.resolve(shopSettings())
        : Promise.resolve([mediaItem()]),
    );
    const requestJsonMutation = vi.fn().mockResolvedValue(shopSettings());
    const user = userEvent.setup();
    render(
      <AdminShopSettingsManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await screen.findByLabelText(/매장명/);
    for (const [groupName, hasAlt] of [
      ["Hero 이미지", true],
      ["미용사 이미지", true],
      ["OG 이미지", false],
    ] as const) {
      const relation = screen.getByRole("group", { name: groupName });
      await user.click(within(relation).getByRole("button", { name: "미디어 선택" }));
      const picker = screen
        .getByRole("heading", { name: `${groupName} 선택` })
        .closest("section")!;
      await user.click(within(picker).getByRole("button", { name: "이 미디어 선택" }));
      if (hasAlt) {
        const input = within(
          screen.getByRole("group", { name: groupName }),
        ).getByLabelText(/대체텍스트/);
        await user.type(input, `${groupName} 설명`);
      }
    }
    expect(document.querySelector('[name="ogImageAltText"]')).toBeNull();

    await user.click(screen.getByRole("button", { name: "매장정보 저장" }));
    const body = requestJsonMutation.mock.calls[0]?.[2];
    expect(body).toMatchObject({
      heroImageId: ACTIVE_ID,
      heroImageAltText: "Hero 이미지 설명",
      groomerImageId: ACTIVE_ID,
      groomerImageAltText: "미용사 이미지 설명",
      ogImageId: ACTIVE_ID,
    });
  });

  it("Hero image 선택 뒤 alt blank면 PUT 전에 pair 오류를 안내한다", async () => {
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) =>
      path === "/api/admin/shop-settings"
        ? Promise.resolve(shopSettings())
        : Promise.resolve([mediaItem()]),
    );
    const requestJsonMutation = vi.fn();
    const user = userEvent.setup();
    render(
      <AdminShopSettingsManager
        transport={createTransport({ requestAuthenticatedJson, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const hero = await screen.findByRole("group", { name: "Hero 이미지" });
    await user.click(within(hero).getByRole("button", { name: "미디어 선택" }));
    await user.click(screen.getByRole("button", { name: "이 미디어 선택" }));
    fireEvent.submit(screen.getByRole("button", { name: "매장정보 저장" }).closest("form")!);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Hero 이미지의 대체텍스트를 입력해 주세요.",
    );
    expect(requestJsonMutation).not.toHaveBeenCalled();
  });

  it("301 code-point alt를 PUT 전에 거부한다", async () => {
    const requestJsonMutation = vi.fn();
    render(
      <AdminShopSettingsManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
            path === "/api/admin/shop-settings"
              ? Promise.resolve(
                  shopSettings({ heroImageId: ACTIVE_ID, heroImageAltText: "기존 설명" }),
                )
              : Promise.resolve([mediaItem()]),
          ),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    fireEvent.change(await screen.findByLabelText(/대체텍스트/), {
      target: { value: "🐶".repeat(301) },
    });
    fireEvent.submit(screen.getByRole("button", { name: "매장정보 저장" }).closest("form")!);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "이미지 대체텍스트는 300자 이하여야 합니다.",
    );
    expect(requestJsonMutation).not.toHaveBeenCalled();
  });

  it("archived current relation을 숨기지 않고 active 교체 전 저장을 막는다", async () => {
    const requestJsonMutation = vi.fn().mockResolvedValue(shopSettings());
    const user = userEvent.setup();
    render(
      <AdminShopSettingsManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
            path === "/api/admin/shop-settings"
              ? Promise.resolve(
                  shopSettings({
                    heroImageId: ARCHIVED_ID,
                    heroImageAltText: "보관된 사진 설명",
                  }),
                )
              : Promise.resolve([
                  mediaItem({ id: ARCHIVED_ID, status: "archived" }),
                  mediaItem(),
                ]),
          ),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const relation = await screen.findByRole("group", { name: "Hero 이미지" });
    expect(within(relation).getByText(ARCHIVED_ID)).toBeInTheDocument();
    expect(within(relation).getByRole("alert")).toHaveTextContent("보관된 미디어");
    fireEvent.submit(screen.getByRole("button", { name: "매장정보 저장" }).closest("form")!);
    expect(await screen.findByText(/보관됐거나 찾을 수 없는 미디어 관계/)).toHaveTextContent(
      "보관됐거나 찾을 수 없는 미디어 관계",
    );
    expect(requestJsonMutation).not.toHaveBeenCalled();

    await user.click(within(relation).getByRole("button", { name: "미디어 선택" }));
    const picker = screen.getByRole("heading", { name: "Hero 이미지 선택" }).closest("section")!;
    expect(within(picker).queryByText(ARCHIVED_ID)).not.toBeInTheDocument();
    await user.click(within(picker).getByRole("button", { name: "이 미디어 선택" }));
    const alt = screen.getByLabelText(/대체텍스트/);
    expect(alt).toHaveValue("");
    await user.type(alt, "새 Hero 이미지 설명");
    await user.click(screen.getByRole("button", { name: "매장정보 저장" }));
    expect(requestJsonMutation.mock.calls[0]?.[2]).toMatchObject({
      heroImageId: ACTIVE_ID,
      heroImageAltText: "새 Hero 이미지 설명",
    });
  });

  it("missing current relation을 표시하고 선택 해제로 null 저장한다", async () => {
    const requestJsonMutation = vi.fn().mockResolvedValue(shopSettings());
    const user = userEvent.setup();
    render(
      <AdminShopSettingsManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
            path === "/api/admin/shop-settings"
              ? Promise.resolve(shopSettings({ ogImageId: MISSING_ID }))
              : Promise.resolve([]),
          ),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const relation = await screen.findByRole("group", { name: "OG 이미지" });
    expect(within(relation).getByRole("alert")).toHaveTextContent(
      "목록에서 찾을 수 없는 미디어",
    );
    await user.click(within(relation).getByRole("button", { name: "선택 해제" }));
    await user.click(screen.getByRole("button", { name: "매장정보 저장" }));
    expect(requestJsonMutation.mock.calls[0]?.[2]).toMatchObject({ ogImageId: null });
  });

  it("shop GET 401에서 form을 만들지 않고 session expiry callback을 호출한다", async () => {
    const onSessionExpired = vi.fn();
    render(
      <AdminShopSettingsManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
            path === "/api/admin/shop-settings"
              ? Promise.reject(new AdminApiError("session-expired"))
              : Promise.resolve([]),
          ),
        })}
        onBack={vi.fn()}
        onSessionExpired={onSessionExpired}
      />,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByLabelText(/매장명/)).not.toBeInTheDocument();
  });
});
