import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";
import type { Breed } from "@/features/admin-breed/types";
import type { MediaItem } from "@/features/admin-media/types";
import type { GroomingService } from "@/features/admin-service/types";

import { AdminGalleryManager } from "./AdminGalleryManager";
import {
  instantToLocalDateTimeValue,
  type GalleryItem,
  type UpdateGalleryRequest,
} from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const GALLERY_ID = "d64047ee-93fe-4f87-949f-493d47ad6ee4";
const SECOND_GALLERY_ID = "1252ef09-6758-4af2-963d-9a65d0f369cf";
const DRAFT_BREED_ID = "6c3cf849-26f4-44f8-b51b-899ba7937b4a";
const PUBLISHED_BREED_ID = "429c206f-59af-40c4-9252-ced9b352f1fc";
const ARCHIVED_SERVICE_ID = "75c680f0-2393-46da-a7ca-8fe72f7ba991";
const PUBLISHED_SERVICE_ID = "5dd64572-a6b5-4476-843e-f33d953089ca";
const ARCHIVED_MEDIA_ID = "2c65fbb2-c58c-4bb3-9539-d150f19231fd";
const ACTIVE_MEDIA_ID = "902082c8-71f2-4e73-ab42-722c7d98469d";
const SECOND_ACTIVE_MEDIA_ID = "4a830d93-108d-45ab-b527-e1b043daf0e3";

function gallery(overrides: Partial<GalleryItem> = {}): GalleryItem {
  return {
    id: GALLERY_ID,
    status: "draft",
    dogName: "보리",
    breedId: DRAFT_BREED_ID,
    primaryServiceId: ARCHIVED_SERVICE_ID,
    coverImageId: ARCHIVED_MEDIA_ID,
    beforeImageId: null,
    afterImageId: null,
    summary: "여름 미용",
    altText: null,
    featured: false,
    sortOrder: 100,
    performedAt: null,
    publishedAt: null,
    createdAt: "2026-08-30T00:00:00Z",
    updatedAt: "2026-08-30T00:00:00Z",
    createdBy: ACTOR_ID,
    updatedBy: ACTOR_ID,
    ...overrides,
  };
}

function breed(overrides: Partial<Breed> = {}): Breed {
  return {
    id: DRAFT_BREED_ID,
    status: "draft",
    name: "비숑",
    slug: "bichon",
    description: null,
    sortOrder: 10,
    createdAt: "2026-08-30T00:00:00Z",
    updatedAt: "2026-08-30T00:00:00Z",
    createdBy: ACTOR_ID,
    updatedBy: ACTOR_ID,
    ...overrides,
  };
}

function service(overrides: Partial<GroomingService> = {}): GroomingService {
  return {
    id: ARCHIVED_SERVICE_ID,
    status: "archived",
    name: "기본 미용",
    slug: "basic-grooming",
    description: "기본 미용 설명",
    priceText: "상담 후 안내",
    sortOrder: 10,
    createdAt: "2026-08-30T00:00:00Z",
    updatedAt: "2026-08-30T00:00:00Z",
    createdBy: ACTOR_ID,
    updatedBy: ACTOR_ID,
    ...overrides,
  };
}

function media(overrides: Partial<MediaItem> = {}): MediaItem {
  return {
    id: ARCHIVED_MEDIA_ID,
    status: "archived",
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

const BREEDS = [
  breed(),
  breed({ id: PUBLISHED_BREED_ID, status: "published", name: "푸들", slug: "poodle" }),
];
const SERVICES = [
  service(),
  service({
    id: PUBLISHED_SERVICE_ID,
    status: "published",
    name: "스포팅",
    slug: "sporting",
  }),
];
const MEDIA = [
  media(),
  media({ id: ACTIVE_MEDIA_ID, status: "active" }),
  media({ id: SECOND_ACTIVE_MEDIA_ID, status: "active" }),
];

function createTransport(
  options: Readonly<{
    galleries?: readonly GalleryItem[];
    galleryList?: (path: string) => Promise<readonly GalleryItem[]>;
    breeds?: readonly Breed[];
    services?: readonly GroomingService[];
    mediaItems?: readonly MediaItem[];
    requestJsonMutation?: AdminApiTransport["requestJsonMutation"];
    requestAuthenticatedJson?: AdminApiTransport["requestAuthenticatedJson"];
  }> = {},
): AdminApiTransport {
  const requestAuthenticatedJson =
    options.requestAuthenticatedJson ??
    vi.fn().mockImplementation((path: string) => {
      if (path === "/api/admin/gallery-items") {
        return options.galleryList
          ? options.galleryList(path)
          : Promise.resolve(options.galleries ?? []);
      }
      if (path === "/api/admin/breeds") return Promise.resolve(options.breeds ?? BREEDS);
      if (path === "/api/admin/services") {
        return Promise.resolve(options.services ?? SERVICES);
      }
      if (path === "/api/admin/media") return Promise.resolve(options.mediaItems ?? MEDIA);
      return Promise.reject(new AdminApiError("unavailable"));
    });
  return {
    requestAuthenticatedJson,
    requestJsonMutation:
      options.requestJsonMutation ?? vi.fn().mockResolvedValue(gallery()),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi
      .fn()
      .mockResolvedValue(new Blob(["image"], { type: "image/jpeg" })),
  };
}

async function openCreate(user: ReturnType<typeof userEvent.setup>) {
  const trigger = await screen.findByRole("button", { name: "새 갤러리 항목" });
  trigger.focus();
  await user.keyboard("{Enter}");
  expect(screen.getByLabelText("반려견 이름")).toHaveFocus();
  return trigger;
}

async function selectMedia(
  user: ReturnType<typeof userEvent.setup>,
  slotLabel: string,
  id: string,
) {
  const trigger = screen.getByRole("button", { name: `${slotLabel} 미디어 선택` });
  await user.click(trigger);
  expect(screen.getByRole("button", { name: "선택 닫기" })).toHaveFocus();
  const picker = screen.getByRole("region", { name: `${slotLabel} 선택` });
  const card = within(picker).getByText(id).closest("li");
  expect(card).not.toBeNull();
  await user.click(within(card!).getByRole("button", { name: /이 미디어 선택|현재 선택됨/ }));
  expect(trigger).toHaveFocus();
}

let createObjectUrl: ReturnType<typeof vi.fn>;
let revokeObjectUrl: ReturnType<typeof vi.fn>;

beforeEach(() => {
  createObjectUrl = vi.fn(() => "blob:gallery-preview");
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

describe("AdminGalleryManager", () => {
  it("Gallery 목록과 독립적인 relation catalog loading을 표시하고 준비 전 편집을 제한한다", async () => {
    let resolveServices: ((items: readonly GroomingService[]) => void) | undefined;
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) => {
      if (path === "/api/admin/gallery-items") return Promise.resolve([gallery()]);
      if (path === "/api/admin/breeds") return Promise.resolve(BREEDS);
      if (path === "/api/admin/media") return Promise.resolve(MEDIA);
      if (path === "/api/admin/services") {
        return new Promise<readonly GroomingService[]>((resolve) => {
          resolveServices = resolve;
        });
      }
      return Promise.reject(new Error("unexpected path"));
    });

    render(
      <AdminGalleryManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("heading", { name: "보리" })).toBeInTheDocument();
    expect(
      screen.getByText("견종·서비스·미디어 관계 목록을 불러오고 있습니다."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새 갤러리 항목" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "보리 수정" })).toBeDisabled();

    resolveServices?.(SERVICES);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "보리 수정" })).toBeEnabled(),
    );
  });

  it("non-null cover 관계를 media catalog pending 동안 없음이 아닌 loading으로 유지한다", async () => {
    let resolveMedia: ((items: readonly MediaItem[]) => void) | undefined;
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) => {
      if (path === "/api/admin/gallery-items") return Promise.resolve([gallery()]);
      if (path === "/api/admin/breeds") return Promise.resolve(BREEDS);
      if (path === "/api/admin/services") return Promise.resolve(SERVICES);
      if (path === "/api/admin/media") {
        return new Promise<readonly MediaItem[]>((resolve) => {
          resolveMedia = resolve;
        });
      }
      return Promise.reject(new Error("unexpected path"));
    });

    render(
      <AdminGalleryManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("heading", { name: "보리" })).toBeInTheDocument();
    const relationStatus = screen.getByText("대표 이미지 관계 확인 중");
    expect(relationStatus).toHaveAttribute("role", "status");
    expect(relationStatus).toHaveAttribute("aria-live", "polite");
    expect(screen.queryByText("대표 이미지 없음")).not.toBeInTheDocument();

    resolveMedia?.(MEDIA);
    expect(
      await screen.findByRole("img", { name: "보리 대표 이미지 미리보기" }),
    ).toBeInTheDocument();
  });

  it("media catalog failure를 unavailable로 표시하고 retry 성공 뒤 private cover preview를 복구한다", async () => {
    const user = userEvent.setup();
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) => {
      if (path === "/api/admin/gallery-items") return Promise.resolve([gallery()]);
      if (path === "/api/admin/breeds") return Promise.resolve(BREEDS);
      if (path === "/api/admin/services") return Promise.resolve(SERVICES);
      if (path === "/api/admin/media") {
        const mediaCalls = requestAuthenticatedJson.mock.calls.filter(
          ([calledPath]) => calledPath === "/api/admin/media",
        ).length;
        return mediaCalls === 1
          ? Promise.reject(new AdminApiError("unavailable"))
          : Promise.resolve(MEDIA);
      }
      return Promise.reject(new Error("unexpected path"));
    });
    const transport = createTransport({ requestAuthenticatedJson });

    render(
      <AdminGalleryManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("heading", { name: "보리" })).toBeInTheDocument();
    expect(
      await screen.findByText("대표 이미지 관계 정보를 확인할 수 없음"),
    ).toHaveAttribute("role", "alert");
    expect(screen.queryByText("대표 이미지 없음")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "보리 수정" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "관계 목록 다시 시도" }));
    expect(
      await screen.findByRole("img", { name: "보리 대표 이미지 미리보기" }),
    ).toBeInTheDocument();
    expect(transport.requestAuthenticatedBlob).toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "보리 수정" })).toBeEnabled();
  });

  it("ready catalog의 미해결 cover 관계를 실제 null 관계와 구분한다", async () => {
    const user = userEvent.setup();
    const unresolvedCoverId = "a1cb940e-4ca2-4271-a020-4d1ee31f30f4";
    render(
      <AdminGalleryManager
        transport={createTransport({
          galleries: [gallery({ coverImageId: unresolvedCoverId })],
          mediaItems: MEDIA.filter((item) => item.id !== unresolvedCoverId),
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(
      await screen.findByText("대표 이미지 관계를 목록에서 찾을 수 없음"),
    ).toHaveAttribute("role", "alert");
    expect(screen.queryByText("대표 이미지 없음")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "보리 수정" }));
    expect(
      screen.getByText("선택된 미디어를 목록에서 찾을 수 없음"),
    ).toHaveAttribute("role", "alert");
  });

  it("coverImageId가 null일 때만 대표 이미지 없음을 표시한다", async () => {
    render(
      <AdminGalleryManager
        transport={createTransport({ galleries: [gallery({ coverImageId: null })] })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByText("대표 이미지 없음")).toBeInTheDocument();
    expect(screen.queryByText(/대표 이미지 관계/)).not.toBeInTheDocument();
  });

  it("선택 relation의 media catalog loading을 alert가 아닌 status로 알린다", async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    const onSessionExpired = vi.fn();
    const view = render(
      <AdminGalleryManager
        transport={createTransport({ galleries: [gallery()] })}
        onBack={onBack}
        onSessionExpired={onSessionExpired}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "보리 수정" }));
    let resolveMedia: ((items: readonly MediaItem[]) => void) | undefined;
    const loadingTransport = createTransport({
      requestAuthenticatedJson: vi.fn().mockImplementation((path: string) => {
        if (path === "/api/admin/gallery-items") return Promise.resolve([gallery()]);
        if (path === "/api/admin/breeds") return Promise.resolve(BREEDS);
        if (path === "/api/admin/services") return Promise.resolve(SERVICES);
        if (path === "/api/admin/media") {
          return new Promise<readonly MediaItem[]>((resolve) => {
            resolveMedia = resolve;
          });
        }
        return Promise.reject(new Error("unexpected path"));
      }),
    });

    view.rerender(
      <AdminGalleryManager
        transport={loadingTransport}
        onBack={onBack}
        onSessionExpired={onSessionExpired}
      />,
    );

    const selectedStatus = await screen.findByText("선택된 미디어 상태 확인 중");
    expect(selectedStatus).toHaveAttribute("role", "status");
    expect(selectedStatus).toHaveAttribute("aria-live", "polite");
    expect(selectedStatus).not.toHaveAttribute("role", "alert");

    resolveMedia?.(MEDIA);
    await waitFor(() =>
      expect(screen.queryByText("선택된 미디어 상태 확인 중")).not.toBeInTheDocument(),
    );
    expect(screen.getByText(/보관된 미디어 선택됨/)).not.toHaveAttribute("role", "alert");

    const errorTransport = createTransport({
      requestAuthenticatedJson: vi.fn().mockImplementation((path: string) => {
        if (path === "/api/admin/gallery-items") return Promise.resolve([gallery()]);
        if (path === "/api/admin/breeds") return Promise.resolve(BREEDS);
        if (path === "/api/admin/services") return Promise.resolve(SERVICES);
        if (path === "/api/admin/media") {
          return Promise.reject(new AdminApiError("unavailable"));
        }
        return Promise.reject(new Error("unexpected path"));
      }),
    });
    view.rerender(
      <AdminGalleryManager
        transport={errorTransport}
        onBack={onBack}
        onSessionExpired={onSessionExpired}
      />,
    );
    expect(
      await screen.findByText("선택된 미디어 상태를 확인할 수 없음"),
    ).toHaveAttribute("role", "alert");
  });

  it("loading/error/retry와 server array ordering을 client comparator 없이 보존한다", async () => {
    const user = userEvent.setup();
    const serverOrdered = [
      gallery({ id: SECOND_GALLERY_ID, dogName: "나중 이름", featured: false, sortOrder: 90 }),
      gallery({ dogName: "가장 이름", featured: true, sortOrder: 1 }),
    ];
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) => {
      if (path === "/api/admin/gallery-items") {
        const galleryCalls = requestAuthenticatedJson.mock.calls.filter(
          ([calledPath]) => calledPath === "/api/admin/gallery-items",
        ).length;
        return galleryCalls === 1
          ? Promise.reject(new AdminApiError("unavailable"))
          : Promise.resolve(serverOrdered);
      }
      if (path === "/api/admin/breeds") return Promise.resolve(BREEDS);
      if (path === "/api/admin/services") return Promise.resolve(SERVICES);
      if (path === "/api/admin/media") return Promise.resolve(MEDIA);
      return Promise.reject(new Error("unexpected path"));
    });

    render(
      <AdminGalleryManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(screen.getByText("갤러리 목록을 불러오고 있습니다.")).toHaveAttribute(
      "role",
      "status",
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("갤러리 목록을 불러오지 못했습니다");
    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    const list = await screen.findByRole("list", { name: "갤러리 목록" });
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => within(item).getByRole("heading", { level: 3 }).textContent),
    ).toEqual(serverOrdered.map((item) => item.dogName));
  });

  it("draft create에서 draft/archived relation과 archived media, future publishedAt을 허용하고 canonical GET 뒤 focus를 복귀한다", async () => {
    const user = userEvent.setup();
    const futureInstant = "2099-12-31T23:59:59.123000Z";
    let resolveCanonical: ((items: readonly GalleryItem[]) => void) | undefined;
    const created = gallery({ publishedAt: futureInstant });
    const galleryList = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(
        new Promise<readonly GalleryItem[]>((resolve) => {
          resolveCanonical = resolve;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(created);
    const transport = createTransport({ galleryList, requestJsonMutation });

    render(
      <AdminGalleryManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    const trigger = await openCreate(user);

    await user.type(screen.getByLabelText("반려견 이름"), " 보리 ");
    await user.selectOptions(screen.getByLabelText("견종"), DRAFT_BREED_ID);
    await user.selectOptions(screen.getByLabelText("대표 서비스"), ARCHIVED_SERVICE_ID);
    await selectMedia(user, "대표 이미지", ARCHIVED_MEDIA_ID);
    fireEvent.change(screen.getByLabelText("게시 시각"), {
      target: { value: instantToLocalDateTimeValue(futureInstant) },
    });
    await user.click(screen.getByRole("button", { name: "갤러리 항목 생성" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      "/api/admin/gallery-items",
      "POST",
      {
        dogName: "보리",
        breedId: DRAFT_BREED_ID,
        primaryServiceId: ARCHIVED_SERVICE_ID,
        coverImageId: ARCHIVED_MEDIA_ID,
        beforeImageId: null,
        afterImageId: null,
        summary: null,
        altText: null,
        featured: false,
        sortOrder: null,
        performedAt: null,
        publishedAt: futureInstant,
      },
      expect.any(Function),
    );
    const sentBody = vi.mocked(requestJsonMutation).mock.calls[0]?.[2];
    expect(sentBody).not.toHaveProperty("status");
    await waitFor(() => expect(galleryList).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    resolveCanonical?.([created]);
    await waitFor(() => expect(trigger).toBeEnabled());
    expect(trigger).toHaveFocus();
  });

  it("published target의 invalid relation을 표시하고 active/published 교체 뒤 exact full PUT과 cover=before를 허용한다", async () => {
    const user = userEvent.setup();
    const publishedAt = "2099-01-01T00:00:00.001000Z";
    const updated = gallery({
      status: "published",
      breedId: PUBLISHED_BREED_ID,
      primaryServiceId: PUBLISHED_SERVICE_ID,
      coverImageId: ACTIVE_MEDIA_ID,
      beforeImageId: ACTIVE_MEDIA_ID,
      altText: "미용을 마친 보리",
      publishedAt,
    });
    const galleryList = vi.fn().mockResolvedValueOnce([gallery()]).mockResolvedValueOnce([updated]);
    const requestJsonMutation = vi.fn().mockResolvedValue(updated);

    render(
      <AdminGalleryManager
        transport={createTransport({ galleryList, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "보리 수정" }));
    await user.selectOptions(screen.getByLabelText("상태"), "published");
    expect(screen.getByText(/게시하려면 게시됨 견종으로 교체/)).toBeInTheDocument();
    expect(screen.getByText(/게시하려면 게시됨 서비스로 교체/)).toBeInTheDocument();
    expect(screen.getByText(/게시하려면 활성 미디어로 교체/)).toHaveAttribute(
      "role",
      "alert",
    );
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText(/게시 상태의 필수값과 관계 상태/)).toBeInTheDocument();
    expect(requestJsonMutation).not.toHaveBeenCalled();

    await user.selectOptions(screen.getByLabelText("견종"), PUBLISHED_BREED_ID);
    await user.selectOptions(screen.getByLabelText("대표 서비스"), PUBLISHED_SERVICE_ID);
    await selectMedia(user, "대표 이미지", ACTIVE_MEDIA_ID);
    await selectMedia(user, "시술 전 이미지", ACTIVE_MEDIA_ID);
    await user.type(screen.getByLabelText("대체텍스트"), "미용을 마친 보리");
    fireEvent.change(screen.getByLabelText("게시 시각"), {
      target: { value: instantToLocalDateTimeValue(publishedAt) },
    });
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      `/api/admin/gallery-items/${GALLERY_ID}`,
      "PUT",
      expect.objectContaining({
        status: "published",
        dogName: "보리",
        breedId: PUBLISHED_BREED_ID,
        primaryServiceId: PUBLISHED_SERVICE_ID,
        coverImageId: ACTIVE_MEDIA_ID,
        beforeImageId: ACTIVE_MEDIA_ID,
        afterImageId: null,
        summary: "여름 미용",
        altText: "미용을 마친 보리",
        featured: false,
        sortOrder: 100,
        performedAt: null,
        publishedAt,
      } satisfies UpdateGalleryRequest),
      expect.any(Function),
    );
    expect(await screen.findByText("게시됨")).toBeInTheDocument();
  });

  it("before=after를 상태와 무관하게 막고 cover=after는 허용한다", async () => {
    const user = userEvent.setup();
    const updated = gallery({
      coverImageId: ACTIVE_MEDIA_ID,
      afterImageId: ACTIVE_MEDIA_ID,
    });
    const requestJsonMutation = vi.fn().mockResolvedValue(updated);
    const galleryList = vi.fn().mockResolvedValueOnce([gallery()]).mockResolvedValueOnce([updated]);
    render(
      <AdminGalleryManager
        transport={createTransport({ galleryList, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "보리 수정" }));
    await selectMedia(user, "대표 이미지", ACTIVE_MEDIA_ID);
    await selectMedia(user, "시술 전 이미지", SECOND_ACTIVE_MEDIA_ID);
    await selectMedia(user, "시술 후 이미지", SECOND_ACTIVE_MEDIA_ID);
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("시술 전과 후 이미지는 서로 달라야");
    expect(requestJsonMutation).not.toHaveBeenCalled();

    await selectMedia(user, "시술 전 이미지", ACTIVE_MEDIA_ID);
    await selectMedia(user, "시술 후 이미지", ACTIVE_MEDIA_ID);
    await user.click(screen.getByRole("button", { name: "시술 전 이미지 선택 해제" }));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      `/api/admin/gallery-items/${GALLERY_ID}`,
      "PUT",
      expect.objectContaining({
        coverImageId: ACTIVE_MEDIA_ID,
        beforeImageId: null,
        afterImageId: ACTIVE_MEDIA_ID,
      }),
      expect.any(Function),
    );
  });

  it("slot 인접 picker를 하나만 열고 open·close·selection focus와 Blob revoke를 유지한다", async () => {
    const user = userEvent.setup();
    render(
      <AdminGalleryManager
        transport={createTransport()}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await openCreate(user);

    const coverTrigger = screen.getByRole("button", { name: "대표 이미지 미디어 선택" });
    coverTrigger.focus();
    await user.keyboard("{Enter}");
    expect(screen.getAllByText("대표 이미지 선택")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "선택 닫기" })).toHaveFocus();

    const beforeTrigger = screen.getByRole("button", { name: "시술 전 이미지 미디어 선택" });
    await user.click(beforeTrigger);
    expect(screen.queryByText("대표 이미지 선택")).not.toBeInTheDocument();
    expect(screen.getAllByText("시술 전 이미지 선택")).toHaveLength(1);
    await waitFor(() => expect(revokeObjectUrl).toHaveBeenCalled());
    await user.click(screen.getByRole("button", { name: "선택 닫기" }));
    expect(beforeTrigger).toHaveFocus();

    await selectMedia(user, "시술 후 이미지", ARCHIVED_MEDIA_ID);
    expect(screen.getByText(/보관된 미디어 선택됨/)).toBeInTheDocument();
    expect(createObjectUrl).toHaveBeenCalled();
  });

  it("relation catalog 실패가 Gallery row를 지우지 않고 explicit retry 뒤 편집을 허용한다", async () => {
    const user = userEvent.setup();
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) => {
      if (path === "/api/admin/gallery-items") return Promise.resolve([gallery()]);
      if (path === "/api/admin/breeds") return Promise.resolve(BREEDS);
      if (path === "/api/admin/media") return Promise.resolve(MEDIA);
      if (path === "/api/admin/services") {
        const calls = requestAuthenticatedJson.mock.calls.filter(
          ([calledPath]) => calledPath === "/api/admin/services",
        ).length;
        return calls === 1
          ? Promise.reject(new AdminApiError("unavailable"))
          : Promise.resolve(SERVICES);
      }
      return Promise.reject(new Error("unexpected path"));
    });
    render(
      <AdminGalleryManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("heading", { name: "보리" })).toBeInTheDocument();
    expect(screen.getByText(/서비스 관계 정보를 확인할 수 없음/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "보리 수정" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "관계 목록 다시 시도" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "보리 수정" })).toBeEnabled());
    expect(screen.getByText(/기본 미용 · 보관됨/)).toBeInTheDocument();
  });

  it("mutation 성공 뒤 canonical GET 실패를 저장 실패로 오인하지 않고 warning·focus·refresh로 복구한다", async () => {
    const user = userEvent.setup();
    const created = gallery();
    const galleryList = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new AdminApiError("unavailable"))
      .mockResolvedValueOnce([created]);
    const requestJsonMutation = vi.fn().mockResolvedValue(created);
    render(
      <AdminGalleryManager
        transport={createTransport({ galleryList, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    const trigger = await openCreate(user);
    await user.type(screen.getByLabelText("반려견 이름"), "보리");
    await user.click(screen.getByRole("button", { name: "갤러리 항목 생성" }));

    expect(await screen.findByText("갤러리 항목을 생성했습니다.")).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "저장은 완료됐지만 목록 순서를 새로고침하지 못했습니다",
    );
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/생성하지 못했습니다/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "새로고침" }));
    await waitFor(() => expect(galleryList).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("pending create submit을 중복 전송하지 않는다", async () => {
    let resolveCreate: ((item: GalleryItem) => void) | undefined;
    const requestJsonMutation = vi.fn().mockReturnValue(
      new Promise<GalleryItem>((resolve) => {
        resolveCreate = resolve;
      }),
    );
    render(
      <AdminGalleryManager
        transport={createTransport({ requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    const user = userEvent.setup();
    await openCreate(user);
    const form = screen.getByRole("button", { name: "갤러리 항목 생성" }).closest("form");
    expect(form).not.toBeNull();

    fireEvent.submit(form!);
    fireEvent.submit(form!);
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);

    resolveCreate?.(gallery());
    expect(await screen.findByText("갤러리 항목을 생성했습니다.")).toBeInTheDocument();
  });

  it("update canonical GET resolve/reject 뒤 ready인 item trigger에 focus를 복귀한다", async () => {
    const user = userEvent.setup();
    let rejectCanonical: ((reason?: unknown) => void) | undefined;
    const updated = gallery({ dogName: "보리 최신" });
    const galleryList = vi
      .fn()
      .mockResolvedValueOnce([gallery()])
      .mockReturnValueOnce(
        new Promise<readonly GalleryItem[]>((_, reject) => {
          rejectCanonical = reject;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(updated);
    render(
      <AdminGalleryManager
        transport={createTransport({ galleryList, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const trigger = await screen.findByRole("button", { name: "보리 수정" });
    await user.click(trigger);
    await user.clear(screen.getByLabelText("반려견 이름"));
    await user.type(screen.getByLabelText("반려견 이름"), "보리 최신");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    await waitFor(() => expect(galleryList).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    rejectCanonical?.(new AdminApiError("unavailable"));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "보리 최신 수정" })).toHaveFocus(),
    );
  });

  it("mutation 이전 stale GET이 post-mutation canonical 목록을 덮지 않는다", async () => {
    const user = userEvent.setup();
    let resolveStale: ((items: readonly GalleryItem[]) => void) | undefined;
    const staleTransport = createTransport({
      galleryList: () =>
        new Promise<readonly GalleryItem[]>((resolve) => {
          resolveStale = resolve;
        }),
    });
    const current = gallery();
    const updated = gallery({ dogName: "서버 최신 갤러리" });
    const galleryList = vi.fn().mockResolvedValueOnce([current]).mockResolvedValueOnce([updated]);
    const currentTransport = createTransport({
      galleryList,
      requestJsonMutation: vi.fn().mockResolvedValue(updated),
    });
    const onBack = vi.fn();
    const onSessionExpired = vi.fn();
    const view = render(
      <AdminGalleryManager
        transport={staleTransport}
        onBack={onBack}
        onSessionExpired={onSessionExpired}
      />,
    );
    await waitFor(() =>
      expect(staleTransport.requestAuthenticatedJson).toHaveBeenCalledWith(
        "/api/admin/gallery-items",
        expect.any(Function),
      ),
    );
    view.rerender(
      <AdminGalleryManager
        transport={currentTransport}
        onBack={onBack}
        onSessionExpired={onSessionExpired}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "보리 수정" }));
    await user.clear(screen.getByLabelText("반려견 이름"));
    await user.type(screen.getByLabelText("반려견 이름"), "서버 최신 갤러리");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("서버 최신 갤러리")).toBeInTheDocument();

    resolveStale?.([gallery({ dogName: "오래된 갤러리" })]);
    await waitFor(() =>
      expect(screen.queryByText("오래된 갤러리")).not.toBeInTheDocument(),
    );
  });

  it.each([
    ["gallery-item-not-found", "목록을 새로고침해 주세요"],
    ["gallery-relation-invalid", "선택한 관계의 존재 여부와 게시 상태"],
    ["gallery-publish-invalid", "게시 필수값과 시술 전·후 이미지"],
    ["forbidden", "보안 요청을 확인할 수 없습니다"],
  ] as const)("update %s를 frontend 고정 문구로 표시하고 자동 retry하지 않는다", async (kind, message) => {
    const user = userEvent.setup();
    const requestJsonMutation = vi.fn().mockRejectedValue(new AdminApiError(kind));
    render(
      <AdminGalleryManager
        transport={createTransport({ galleries: [gallery()], requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "보리 수정" }));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("401은 session expiry callback으로 위임한다", async () => {
    const onSessionExpired = vi.fn();
    const requestAuthenticatedJson = vi.fn().mockImplementation((path: string) =>
      path === "/api/admin/gallery-items"
        ? Promise.reject(new AdminApiError("session-expired"))
        : Promise.resolve([]),
    );
    render(
      <AdminGalleryManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={onSessionExpired}
      />,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
  });

  it("mutation 401도 session expiry callback으로 위임하고 자동 재전송하지 않는다", async () => {
    const user = userEvent.setup();
    const onSessionExpired = vi.fn();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValue(new AdminApiError("session-expired"));
    render(
      <AdminGalleryManager
        transport={createTransport({ galleries: [gallery()], requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={onSessionExpired}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "보리 수정" }));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("archive 뒤 draft 복구를 허용하고 hard delete action을 노출하지 않는다", async () => {
    const user = userEvent.setup();
    const archived = gallery({ status: "archived" });
    const restored = gallery({ status: "draft", updatedAt: "2026-08-30T00:00:01Z" });
    const galleryList = vi
      .fn()
      .mockResolvedValueOnce([gallery()])
      .mockResolvedValueOnce([archived])
      .mockResolvedValueOnce([restored]);
    const requestJsonMutation = vi
      .fn()
      .mockResolvedValueOnce(archived)
      .mockResolvedValueOnce(restored);
    render(
      <AdminGalleryManager
        transport={createTransport({ galleryList, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "보리 수정" }));
    await user.selectOptions(screen.getByLabelText("상태"), "archived");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("보관됨")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "보리 수정" }));
    await user.selectOptions(screen.getByLabelText("상태"), "draft");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("초안")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
  });
});
