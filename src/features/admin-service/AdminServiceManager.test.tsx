import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminServiceManager } from "./AdminServiceManager";
import type { GroomingService } from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const BASIC_ID = "d64047ee-93fe-4f87-949f-493d47ad6ee4";
const SPA_ID = "1252ef09-6758-4af2-963d-9a65d0f369cf";

function service(overrides: Partial<GroomingService> = {}): GroomingService {
  return {
    id: BASIC_ID,
    status: "draft",
    name: "기본 미용",
    slug: "basic-grooming",
    description: "목욕과 기본 커트",
    priceText: "상담 후 안내",
    sortOrder: 10,
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
    requestJsonMutation: vi.fn().mockResolvedValue(service()),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
    ...overrides,
  };
}

describe("AdminServiceManager", () => {
  it("empty와 sortOrder/name/id 정렬을 제공한다", async () => {
    const user = userEvent.setup();
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        service({ id: SPA_ID, name: "스파", slug: "spa", sortOrder: 20 }),
        service(),
      ]);

    render(
      <AdminServiceManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    expect(await screen.findByText(/등록된 서비스가 없습니다/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "새로고침" }));
    const list = await screen.findByRole("list", { name: "서비스 목록" });
    expect(within(list).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      expect.stringContaining("기본 미용"),
      expect.stringContaining("스파"),
    ]);
  });

  it("refresh pending 동안 새 mutation 진입을 막아 stale GET 경쟁을 차단한다", async () => {
    const user = userEvent.setup();
    let resolveRefresh: ((value: readonly GroomingService[]) => void) | undefined;
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(
        new Promise<readonly GroomingService[]>((resolve) => {
          resolveRefresh = resolve;
        }),
      );
    render(
      <AdminServiceManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 서비스가 없습니다/);
    await user.click(screen.getByRole("button", { name: "새로고침" }));
    expect(screen.getByRole("button", { name: "새로고침 중" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "새 서비스" })).toBeDisabled();

    resolveRefresh?.([]);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "새 서비스" })).toBeEnabled(),
    );
  });

  it("keyboard create와 priceText payload, canonical response, focus 복귀를 보장한다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi.fn().mockResolvedValue(service({ sortOrder: 100 }));
    render(
      <AdminServiceManager
        transport={createTransport({ requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 서비스가 없습니다/);

    const trigger = screen.getByRole("button", { name: "새 서비스" });
    trigger.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByLabelText("서비스 이름")).toHaveFocus();
    await user.type(screen.getByLabelText("서비스 이름"), "기본 미용");
    await user.type(screen.getByLabelText("슬러그"), "basic-grooming");
    await user.type(screen.getByLabelText("설명"), "목욕과 기본 커트");
    await user.type(screen.getByLabelText("가격 안내"), "상담 후 안내");
    await user.click(screen.getByRole("button", { name: "서비스 생성" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      "/api/admin/services",
      "POST",
      {
        name: "기본 미용",
        slug: "basic-grooming",
        description: "목욕과 기본 커트",
        priceText: "상담 후 안내",
        sortOrder: null,
      },
      expect.any(Function),
    );
    expect(await screen.findByText("basic-grooming")).toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("optional description과 자유 텍스트 priceText blank를 null로 전송한다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi.fn().mockResolvedValue(
      service({ description: null, priceText: null, sortOrder: 100 }),
    );
    render(
      <AdminServiceManager
        transport={createTransport({ requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 서비스가 없습니다/);
    await user.click(screen.getByRole("button", { name: "새 서비스" }));
    await user.type(screen.getByLabelText("서비스 이름"), "기본 미용");
    await user.type(screen.getByLabelText("슬러그"), "basic-grooming");
    await user.click(screen.getByRole("button", { name: "서비스 생성" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      "/api/admin/services",
      "POST",
      {
        name: "기본 미용",
        slug: "basic-grooming",
        description: null,
        priceText: null,
        sortOrder: null,
      },
      expect.any(Function),
    );
  });

  it("published 전환 전에 description과 priceText를 보조 검증하고 backend 422도 고정한다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValue(new AdminApiError("publish-validation-failed"));
    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([
            service({ description: null, priceText: null }),
          ]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "기본 미용 수정" }));
    expect(screen.getByLabelText("슬러그 (변경 불가)")).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("상태"), "published");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "게시하려면 설명과 가격 안내가 필요합니다",
    );
    expect(requestJsonMutation).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("설명"), "목욕과 기본 커트");
    await user.type(screen.getByLabelText("가격 안내"), "상담 후 안내");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "게시 조건을 충족하지 못했습니다",
    );
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("full PUT으로 archived와 published 복구를 수행하고 focus를 action으로 돌린다", async () => {
    const user = userEvent.setup();
    const archived = service({ status: "archived" });
    const restored = service({ status: "published", updatedAt: "2026-08-30T00:00:02Z" });
    const requestJsonMutation = vi
      .fn()
      .mockResolvedValueOnce(archived)
      .mockResolvedValueOnce(restored);
    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([service()]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const trigger = await screen.findByRole("button", { name: "기본 미용 수정" });
    await user.click(trigger);
    await user.selectOptions(screen.getByLabelText("상태"), "archived");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("보관됨")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "기본 미용 수정" })).toHaveFocus();

    await user.click(screen.getByRole("button", { name: "기본 미용 수정" }));
    await user.selectOptions(screen.getByLabelText("상태"), "published");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("게시됨")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenNthCalledWith(
      2,
      `/api/admin/services/${BASIC_ID}`,
      "PUT",
      {
        status: "published",
        name: "기본 미용",
        description: "목욕과 기본 커트",
        priceText: "상담 후 안내",
        sortOrder: 10,
      },
      expect.any(Function),
    );
  });

  it("mutation 403을 자동 재전송하지 않고 401은 session callback으로 위임한다", async () => {
    const user = userEvent.setup();
    const onSessionExpired = vi.fn();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValue(new AdminApiError("forbidden"));
    const { unmount } = render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([service()]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={onSessionExpired}
      />,
    );
    await user.click(await screen.findByRole("button", { name: "기본 미용 수정" }));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("보안 요청을 확인할 수 없습니다");
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
    unmount();

    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson: vi
            .fn()
            .mockRejectedValue(new AdminApiError("session-expired")),
        })}
        onBack={vi.fn()}
        onSessionExpired={onSessionExpired}
      />,
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
  });
});
