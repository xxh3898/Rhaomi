import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminMediaPicker } from "./AdminMediaPicker";
import { AdminMediaApi } from "./api";
import type { MediaItem } from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const ACTIVE_ID = "d64047ee-93fe-4f87-949f-493d47ad6ee4";
const SECOND_ACTIVE_ID = "6c3cf849-26f4-44f8-b51b-899ba7937b4a";
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

function createApi(
  overrides: Partial<AdminApiTransport> = {},
): AdminMediaApi {
  return new AdminMediaApi({
    requestAuthenticatedJson: vi.fn(),
    requestJsonMutation: vi.fn(),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi
      .fn()
      .mockResolvedValue(new Blob(["image"], { type: "image/jpeg" })),
    ...overrides,
  });
}

let createObjectUrl: ReturnType<typeof vi.fn>;
let revokeObjectUrl: ReturnType<typeof vi.fn>;

beforeEach(() => {
  createObjectUrl = vi.fn(() => "blob:shop-media-picker");
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

describe("AdminMediaPicker", () => {
  it("server ordering을 유지하고 active asset만 single-select option으로 제공한다", async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const onClose = vi.fn();
    const items = [
      mediaItem(),
      mediaItem({ id: ARCHIVED_ID, status: "archived" }),
      mediaItem({ id: SECOND_ACTIVE_ID }),
    ];

    render(
      <AdminMediaPicker
        api={createApi()}
        id="hero-picker"
        slotLabel="Hero 이미지"
        state={{ kind: "ready", items }}
        selectedId={SECOND_ACTIVE_ID}
        disabled={false}
        onSelect={onSelect}
        onRetry={vi.fn()}
        onClose={onClose}
        onSessionExpired={vi.fn()}
      />,
    );

    const listItems = screen.getAllByRole("listitem");
    expect(listItems).toHaveLength(2);
    expect(within(listItems[0]!).getByText(ACTIVE_ID)).toBeInTheDocument();
    expect(within(listItems[1]!).getByText(SECOND_ACTIVE_ID)).toBeInTheDocument();
    expect(screen.queryByText(ARCHIVED_ID)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "현재 선택됨" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    await user.click(within(listItems[0]!).getByRole("button", { name: "이 미디어 선택" }));
    expect(onSelect).toHaveBeenCalledWith(ACTIVE_ID);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("없음 선택으로 현재 relation을 해제한다", async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const onClose = vi.fn();
    render(
      <AdminMediaPicker
        api={createApi()}
        id="og-picker"
        slotLabel="OG 이미지"
        state={{ kind: "ready", items: [mediaItem()] }}
        selectedId={ACTIVE_ID}
        disabled={false}
        onSelect={onSelect}
        onRetry={vi.fn()}
        onClose={onClose}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: "없음 — 현재 이미지 관계 해제" }),
    );
    expect(onSelect).toHaveBeenCalledWith(null);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("loading과 error를 구분하고 explicit retry를 제공한다", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const props = {
      api: createApi(),
      id: "hero-picker",
      slotLabel: "Hero 이미지",
      selectedId: null,
      disabled: false,
      onSelect: vi.fn(),
      onRetry,
      onClose: vi.fn(),
      onSessionExpired: vi.fn(),
    } as const;
    const view = render(<AdminMediaPicker {...props} state={{ kind: "loading" }} />);
    expect(screen.getByRole("status")).toHaveTextContent(
      "미디어 목록을 불러오고 있습니다.",
    );

    view.rerender(<AdminMediaPicker {...props} state={{ kind: "error" }} />);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "미디어 목록을 불러오지 못했습니다.",
    );
    await user.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("private preview Blob URL을 만들고 unmount에서 revoke한다", async () => {
    const view = render(
      <AdminMediaPicker
        api={createApi()}
        id="hero-picker"
        slotLabel="Hero 이미지"
        state={{ kind: "ready", items: [mediaItem()] }}
        selectedId={null}
        disabled={false}
        onSelect={vi.fn()}
        onRetry={vi.fn()}
        onClose={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(await screen.findByRole("img", { name: "Hero 이미지 선택 후보 1번" })).toBeInTheDocument();
    expect(createObjectUrl).toHaveBeenCalledTimes(1);
    view.unmount();
    expect(revokeObjectUrl).toHaveBeenCalledTimes(1);
  });

  it("preview 401을 공통 session expiry callback으로 전달한다", async () => {
    const onSessionExpired = vi.fn();
    render(
      <AdminMediaPicker
        api={createApi({
          requestAuthenticatedBlob: vi
            .fn()
            .mockRejectedValue(new AdminApiError("session-expired")),
        })}
        id="hero-picker"
        slotLabel="Hero 이미지"
        state={{ kind: "ready", items: [mediaItem()] }}
        selectedId={null}
        disabled={false}
        onSelect={vi.fn()}
        onRetry={vi.fn()}
        onClose={vi.fn()}
        onSessionExpired={onSessionExpired}
      />,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
  });
});
