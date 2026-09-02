import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminMediaManager } from "./AdminMediaManager";
import type { MediaItem } from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const ACTIVE_ID = "d64047ee-93fe-4f87-949f-493d47ad6ee4";
const ARCHIVED_ID = "1252ef09-6758-4af2-963d-9a65d0f369cf";

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
    requestAuthenticatedJson: vi.fn().mockResolvedValue([]),
    requestJsonMutation: vi.fn().mockResolvedValue(mediaItem()),
    requestMultipartMutation: vi.fn().mockResolvedValue(mediaItem()),
    requestAuthenticatedBlob: vi
      .fn()
      .mockResolvedValue(new Blob(["image"], { type: "image/jpeg" })),
    ...overrides,
  };
}

let createObjectUrl: ReturnType<typeof vi.fn>;
let revokeObjectUrl: ReturnType<typeof vi.fn>;

beforeEach(() => {
  createObjectUrl = vi.fn(() => "blob:admin-media-preview");
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
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("AdminMediaManager", () => {
  it("목록 loading에서 ready metadata와 private preview로 전환한다", async () => {
    let resolveList: ((items: readonly MediaItem[]) => void) | undefined;
    const requestAuthenticatedJson = vi.fn().mockReturnValue(
      new Promise<readonly MediaItem[]>((resolve) => {
        resolveList = resolve;
      }),
    );
    const transport = createTransport({ requestAuthenticatedJson });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent(
      "미디어 목록을 불러오고 있습니다.",
    );
    resolveList?.([mediaItem()]);

    expect(await screen.findByText("image/heic")).toBeInTheDocument();
    expect(screen.getByText("1,200 × 900")).toBeInTheDocument();
    expect(screen.getByText("1.0 KiB")).toBeInTheDocument();
    expect(await screen.findByRole("img", { name: /미디어 미리보기, 활성, 1번/ })).toBeInTheDocument();
    expect(transport.requestAuthenticatedBlob).toHaveBeenCalledWith(
      `/api/admin/media/${ACTIVE_ID}/content`,
      ["image/jpeg", "image/png"],
    );
  });

  it("empty와 initial list error를 구분하고 명시적 retry를 제공한다", async () => {
    const user = userEvent.setup();
    const requestAuthenticatedJson = vi
      .fn()
      .mockRejectedValueOnce(new AdminApiError("unavailable"))
      .mockResolvedValueOnce([]);
    const transport = createTransport({ requestAuthenticatedJson });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "미디어 목록을 불러오지 못했습니다.",
    );
    await user.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText(/업로드된 미디어가 없습니다/)).toBeInTheDocument();
    expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2);
  });

  it("active와 archived를 server ordering을 바꾸지 않고 client에서 필터링한다", async () => {
    const user = userEvent.setup();
    const active = mediaItem();
    const archived = mediaItem({ id: ARCHIVED_ID, status: "archived" });
    const transport = createTransport({
      requestAuthenticatedJson: vi.fn().mockResolvedValue([active, archived]),
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByText("2개")).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);

    const filters = screen.getByRole("group", { name: "미디어 상태 필터" });
    await user.click(within(filters).getByRole("button", { name: "활성" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "미디어 보관" })).toBeEnabled();

    await user.click(within(filters).getByRole("button", { name: "보관" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "미디어 복구" })).toBeEnabled();
  });

  it("refresh와 unmount에서 preview object URL을 revoke한다", async () => {
    const user = userEvent.setup();
    const requestAuthenticatedJson = vi.fn().mockResolvedValue([mediaItem()]);
    const transport = createTransport({ requestAuthenticatedJson });

    const view = render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("img")).toBeInTheDocument();
    expect(createObjectUrl).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "새로고침" }));
    await waitFor(() => expect(createObjectUrl).toHaveBeenCalledTimes(2));
    expect(revokeObjectUrl).toHaveBeenCalledTimes(1);

    view.unmount();
    expect(revokeObjectUrl).toHaveBeenCalledTimes(2);
  });

  it("preview fetch 실패가 metadata list를 제거하지 않는다", async () => {
    const transport = createTransport({
      requestAuthenticatedJson: vi.fn().mockResolvedValue([mediaItem()]),
      requestAuthenticatedBlob: vi
        .fn()
        .mockRejectedValue(new AdminApiError("unavailable")),
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(
      await screen.findByText("미리보기를 불러오지 못했습니다."),
    ).toBeInTheDocument();
    expect(screen.getByText("image/heic")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "미디어 보관" })).toBeEnabled();
  });

  it("IntersectionObserver 안에 들어온 preview만 bounded fetch한다", async () => {
    const callbacks: IntersectionObserverCallback[] = [];
    class TestIntersectionObserver {
      readonly root = null;
      readonly rootMargin = "160px";
      readonly thresholds = [0];

      constructor(callback: IntersectionObserverCallback) {
        callbacks.push(callback);
      }

      disconnect() {}
      observe() {}
      takeRecords(): IntersectionObserverEntry[] {
        return [];
      }
      unobserve() {}
    }
    vi.stubGlobal("IntersectionObserver", TestIntersectionObserver);
    const requestAuthenticatedBlob = vi
      .fn()
      .mockResolvedValue(new Blob(["image"], { type: "image/jpeg" }));
    const transport = createTransport({
      requestAuthenticatedJson: vi.fn().mockResolvedValue([
        mediaItem(),
        mediaItem({ id: ARCHIVED_ID, status: "archived" }),
      ]),
      requestAuthenticatedBlob,
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await screen.findByText("2개");
    expect(callbacks).toHaveLength(2);
    expect(requestAuthenticatedBlob).not.toHaveBeenCalled();

    act(() => {
      callbacks[0]?.(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );
    });
    await waitFor(() => expect(requestAuthenticatedBlob).toHaveBeenCalledTimes(1));
  });

  it("20 MiB 초과 file을 network 전송 전에 거부한다", async () => {
    const user = userEvent.setup();
    const transport = createTransport();
    const file = new File(["x"], "too-large.heic", { type: "image/heic" });
    Object.defineProperty(file, "size", { value: 20 * 1024 * 1024 + 1 });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await screen.findByText(/업로드된 미디어가 없습니다/);
    await user.upload(screen.getByLabelText("파일 선택"), file);
    await user.click(screen.getByRole("button", { name: "업로드" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("20 MiB 이하");
    expect(transport.requestMultipartMutation).not.toHaveBeenCalled();
  });

  it("선택한 file을 교체하고 취소할 수 있다", async () => {
    const user = userEvent.setup();
    const transport = createTransport();

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/업로드된 미디어가 없습니다/);
    const input = screen.getByLabelText("파일 선택");

    await user.upload(input, new File(["one"], "first.png", { type: "image/png" }));
    expect(screen.getByText("first.png")).toBeInTheDocument();

    await user.upload(input, new File(["two"], "second.heic", { type: "image/heic" }));
    expect(screen.queryByText("first.png")).not.toBeInTheDocument();
    expect(screen.getByText("second.heic")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "선택 취소" }));
    expect(screen.queryByText("second.heic")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "업로드" })).toBeDisabled();
  });

  it("upload pending 중복 submit을 막고 성공 뒤 file state와 input을 비운다", async () => {
    const user = userEvent.setup();
    let resolveUpload: ((item: MediaItem) => void) | undefined;
    const requestMultipartMutation = vi.fn().mockReturnValue(
      new Promise<MediaItem>((resolve) => {
        resolveUpload = resolve;
      }),
    );
    const transport = createTransport({ requestMultipartMutation });
    const file = new File(["image"], "iphone.heic", { type: "image/heic" });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await screen.findByText(/업로드된 미디어가 없습니다/);
    const input = screen.getByLabelText("파일 선택");
    await user.upload(input, file);
    const upload = screen.getByRole("button", { name: "업로드" });
    await user.click(upload);
    await user.click(screen.getByRole("button", { name: "업로드 중" }));

    expect(requestMultipartMutation).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "업로드 중" })).toBeDisabled();

    resolveUpload?.(mediaItem());
    expect(await screen.findByText("image/heic")).toBeInTheDocument();
    expect(screen.queryByText("iphone.heic")).not.toBeInTheDocument();
    expect(input).toHaveValue("");
  });

  it.each([
    [new AdminApiError("invalid-request"), "파일 요청 형식"],
    [new AdminApiError("too-large"), "20 MiB 이하"],
    [new AdminApiError("type-unsupported"), "JPEG, PNG, HEIC 또는 HEIF"],
    [new AdminApiError("invalid-image"), "손상됐거나 지원하지 않는 이미지"],
    [new AdminApiError("processor-unavailable"), "이미지 처리기를 일시적으로"],
    [new AdminApiError("forbidden"), "보안 요청을 확인할 수 없습니다"],
    [new Error("raw backend detail"), "업로드하지 못했습니다"],
  ])("upload 오류를 frontend-owned 문구로 표시한다", async (error, message) => {
    const user = userEvent.setup();
    const transport = createTransport({
      requestMultipartMutation: vi.fn().mockRejectedValue(error),
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/업로드된 미디어가 없습니다/);
    await user.upload(
      screen.getByLabelText("파일 선택"),
      new File(["image"], "photo.heic", { type: "image/heic" }),
    );
    await user.click(screen.getByRole("button", { name: "업로드" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(alert).not.toHaveTextContent("raw backend detail");
    expect(transport.requestMultipartMutation).toHaveBeenCalledTimes(1);
  });

  it("item 단위 pending을 격리하고 status response 전에는 낙관 변경하지 않는다", async () => {
    const user = userEvent.setup();
    const active = mediaItem();
    const archived = mediaItem({ id: ARCHIVED_ID, status: "archived" });
    let resolveMutation: ((item: MediaItem) => void) | undefined;
    const requestJsonMutation = vi.fn().mockReturnValue(
      new Promise<MediaItem>((resolve) => {
        resolveMutation = resolve;
      }),
    );
    const transport = createTransport({
      requestAuthenticatedJson: vi.fn().mockResolvedValue([active, archived]),
      requestJsonMutation,
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await screen.findByText("2개");
    await user.click(screen.getByRole("button", { name: "미디어 보관" }));

    expect(screen.getByRole("button", { name: "미디어 상태 변경 중" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "미디어 복구" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "새로고침" })).toBeDisabled();
    expect(screen.getAllByText("활성").length).toBeGreaterThan(0);
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);

    resolveMutation?.(mediaItem({ status: "archived", updatedAt: "2026-08-30T00:00:01Z" }));
    await waitFor(() => expect(screen.getAllByText("보관됨")).not.toHaveLength(0));
  });

  it("mutation 403을 성공으로 표시하거나 자동 재전송하지 않는다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValue(new AdminApiError("forbidden"));
    const transport = createTransport({
      requestAuthenticatedJson: vi.fn().mockResolvedValue([mediaItem()]),
      requestJsonMutation,
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await user.click(await screen.findByRole("button", { name: "미디어 보관" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("보안 요청");
    expect(screen.getAllByText("활성").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "미디어 보관" })).toBeEnabled();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("status 404를 stale item으로 안내하고 명시적 refresh를 유지한다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValue(new AdminApiError("not-found"));
    const transport = createTransport({
      requestAuthenticatedJson: vi.fn().mockResolvedValue([mediaItem()]),
      requestJsonMutation,
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await user.click(await screen.findByRole("button", { name: "미디어 보관" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "목록을 새로고침해 주세요.",
    );
    expect(screen.getByRole("button", { name: "새로고침" })).toBeEnabled();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("media 401에서 session-expired callback을 호출한다", async () => {
    const onSessionExpired = vi.fn();
    const transport = createTransport({
      requestAuthenticatedJson: vi
        .fn()
        .mockRejectedValue(new AdminApiError("session-expired")),
    });

    render(
      <AdminMediaManager
        transport={transport}
        onBack={vi.fn()}
        onSessionExpired={onSessionExpired}
      />,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("미디어 목록을 불러오지 못했습니다.")).not.toBeInTheDocument();
  });
});
