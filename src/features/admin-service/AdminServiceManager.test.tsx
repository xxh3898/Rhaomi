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
  it("empty 뒤 refresh server array order를 locale 재정렬 없이 보존한다", async () => {
    const user = userEvent.setup();
    const localeOrdered = [
      service({ name: "가나다", slug: "ganada", sortOrder: 10 }),
      service({ id: SPA_ID, name: "라마바", slug: "ramaba", sortOrder: 10 }),
    ].sort((left, right) => left.name.localeCompare(right.name, "ko"));
    const serverOrdered = [...localeOrdered].reverse();
    expect(serverOrdered.map((item) => item.name)).not.toEqual(
      localeOrdered.map((item) => item.name),
    );
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce(serverOrdered);

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
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => within(item).getByRole("heading", { level: 3 }).textContent),
    ).toEqual(serverOrdered.map((item) => item.name));
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

  it("create 뒤 canonical GET pending 동안 focus를 보류하고 resolve 뒤 trigger로 복귀한다", async () => {
    const user = userEvent.setup();
    let resolveCanonical:
      | ((value: readonly GroomingService[]) => void)
      | undefined;
    const created = service({ sortOrder: 100 });
    const existing = service({
      id: SPA_ID,
      name: "스파",
      slug: "spa",
      sortOrder: 100,
    });
    const canonicalList = [created, existing];
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([existing])
      .mockReturnValueOnce(
        new Promise<readonly GroomingService[]>((resolve) => {
          resolveCanonical = resolve;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(created);
    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText("spa");

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
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    resolveCanonical?.(canonicalList);
    await waitFor(() => expect(trigger).toBeEnabled());
    const list = screen.getByRole("list", { name: "서비스 목록" });
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => within(item).getByRole("heading", { level: 3 }).textContent),
    ).toEqual(canonicalList.map((item) => item.name));
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("optional description과 자유 텍스트 priceText blank를 null로 전송한다", async () => {
    const user = userEvent.setup();
    const created = service({ description: null, priceText: null, sortOrder: 100 });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([created]);
    const requestJsonMutation = vi.fn().mockResolvedValue(created);
    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
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
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
  });

  it("update 뒤 canonical GET pending 동안 focus를 보류하고 resolve 뒤 item action으로 복귀한다", async () => {
    const user = userEvent.setup();
    let resolveCanonical:
      | ((value: readonly GroomingService[]) => void)
      | undefined;
    const current = service();
    const spa = service({ id: SPA_ID, name: "스파", slug: "spa", sortOrder: 20 });
    const updated = service({
      name: "프리미엄 기본 미용",
      sortOrder: 30,
      updatedAt: "2026-08-30T00:00:01Z",
    });
    const canonicalList = [updated, spa];
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([current, spa])
      .mockReturnValueOnce(
        new Promise<readonly GroomingService[]>((resolve) => {
          resolveCanonical = resolve;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(updated);

    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await user.click(await screen.findByRole("button", { name: "기본 미용 수정" }));
    await user.clear(screen.getByLabelText("서비스 이름"));
    await user.type(screen.getByLabelText("서비스 이름"), "프리미엄 기본 미용");
    await user.clear(screen.getByLabelText("정렬 순서"));
    await user.type(screen.getByLabelText("정렬 순서"), "30");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      `/api/admin/services/${BASIC_ID}`,
      "PUT",
      {
        status: "draft",
        name: "프리미엄 기본 미용",
        description: "목욕과 기본 커트",
        priceText: "상담 후 안내",
        sortOrder: 30,
      },
      expect.any(Function),
    );
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    const trigger = screen.getByRole("button", {
      name: "프리미엄 기본 미용 수정",
    });
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    resolveCanonical?.(canonicalList);
    await waitFor(() => expect(trigger).toBeEnabled());
    const list = screen.getByRole("list", { name: "서비스 목록" });
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => within(item).getByRole("heading", { level: 3 }).textContent),
    ).toEqual(canonicalList.map((item) => item.name));
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("create 뒤 canonical GET reject 후 warning과 trigger focus를 복구하고 explicit refresh도 허용한다", async () => {
    const user = userEvent.setup();
    let rejectCanonical: ((reason?: unknown) => void) | undefined;
    const created = service({ sortOrder: 100 });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(
        new Promise<readonly GroomingService[]>((_, reject) => {
          rejectCanonical = reject;
        }),
      )
      .mockResolvedValueOnce([created]);
    const requestJsonMutation = vi.fn().mockResolvedValue(created);

    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 서비스가 없습니다/);
    const trigger = screen.getByRole("button", { name: "새 서비스" });
    await user.click(trigger);
    await user.type(screen.getByLabelText("서비스 이름"), "기본 미용");
    await user.type(screen.getByLabelText("슬러그"), "basic-grooming");
    await user.click(screen.getByRole("button", { name: "서비스 생성" }));

    expect(await screen.findByText("서비스를 생성했습니다.")).toBeInTheDocument();
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    rejectCanonical?.(new AdminApiError("unavailable"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "저장은 완료됐지만 목록 순서를 새로고침하지 못했습니다",
    );
    await waitFor(() => expect(trigger).toBeEnabled());
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(screen.getByText("basic-grooming")).toBeInTheDocument();
    expect(screen.queryByText(/서비스를 생성하지 못했습니다/)).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "새로고침" }));
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
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
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([service()])
      .mockResolvedValueOnce([archived])
      .mockResolvedValueOnce([restored]);
    render(
      <AdminServiceManager
        transport={createTransport({
          requestAuthenticatedJson,
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
