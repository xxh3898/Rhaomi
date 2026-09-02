import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminBreedManager } from "./AdminBreedManager";
import type { Breed } from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const BICHON_ID = "d64047ee-93fe-4f87-949f-493d47ad6ee4";
const POODLE_ID = "1252ef09-6758-4af2-963d-9a65d0f369cf";

function breed(overrides: Partial<Breed> = {}): Breed {
  return {
    id: BICHON_ID,
    status: "draft",
    name: "비숑 프리제",
    slug: "bichon-frise",
    description: "밝고 다정한 소형견",
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
    requestJsonMutation: vi.fn().mockResolvedValue(breed()),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
    ...overrides,
  };
}

describe("AdminBreedManager", () => {
  it("loading, error, retry 뒤 server array order를 locale 재정렬 없이 보존한다", async () => {
    const user = userEvent.setup();
    const localeOrdered = [
      breed({ name: "가나다", slug: "ganada", sortOrder: 10 }),
      breed({ id: POODLE_ID, name: "라마바", slug: "ramaba", sortOrder: 10 }),
    ].sort((left, right) => left.name.localeCompare(right.name, "ko"));
    const serverOrdered = [...localeOrdered].reverse();
    expect(serverOrdered.map((item) => item.name)).not.toEqual(
      localeOrdered.map((item) => item.name),
    );
    const requestAuthenticatedJson = vi
      .fn()
      .mockRejectedValueOnce(new AdminApiError("unavailable"))
      .mockResolvedValueOnce(serverOrdered);

    render(
      <AdminBreedManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("견종 목록을 불러오고 있습니다");
    expect(await screen.findByRole("alert")).toHaveTextContent("견종 목록을 불러오지 못했습니다");
    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    const list = await screen.findByRole("list", { name: "견종 목록" });
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => within(item).getByRole("heading", { level: 3 }).textContent),
    ).toEqual(serverOrdered.map((item) => item.name));
  });

  it("create 뒤 canonical GET pending 동안 focus를 보류하고 resolve 뒤 trigger로 복귀한다", async () => {
    const user = userEvent.setup();
    let resolveCanonical: ((value: readonly Breed[]) => void) | undefined;
    const created = breed({ sortOrder: 100 });
    const existing = breed({
      id: POODLE_ID,
      name: "푸들",
      slug: "poodle",
      sortOrder: 100,
    });
    const canonicalList = [created, existing];
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([existing])
      .mockReturnValueOnce(
        new Promise<readonly Breed[]>((resolve) => {
          resolveCanonical = resolve;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(created);

    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText("poodle");

    const trigger = screen.getByRole("button", { name: "새 견종" });
    trigger.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByLabelText("견종 이름")).toHaveFocus();
    await user.type(screen.getByLabelText("견종 이름"), "비숑 프리제");
    await user.type(screen.getByLabelText("슬러그"), "bichon-frise");
    await user.type(screen.getByLabelText("설명"), "밝고 다정한 소형견");
    await user.click(screen.getByRole("button", { name: "견종 생성" }));

    await waitFor(() => expect(requestJsonMutation).toHaveBeenCalledTimes(1));
    expect(requestJsonMutation).toHaveBeenCalledWith(
      "/api/admin/breeds",
      "POST",
      {
        name: "비숑 프리제",
        slug: "bichon-frise",
        description: "밝고 다정한 소형견",
        sortOrder: null,
      },
      expect.any(Function),
    );
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    resolveCanonical?.(canonicalList);
    await waitFor(() => expect(trigger).toBeEnabled());
    const list = screen.getByRole("list", { name: "견종 목록" });
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => within(item).getByRole("heading", { level: 3 }).textContent),
    ).toEqual(canonicalList.map((item) => item.name));
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("update 뒤 canonical GET pending 동안 focus를 보류하고 resolve 뒤 item action으로 복귀한다", async () => {
    const user = userEvent.setup();
    let resolveCanonical: ((value: readonly Breed[]) => void) | undefined;
    const current = breed();
    const updated = breed({
      status: "published",
      name: "비숑",
      description: null,
      sortOrder: 30,
      updatedAt: "2026-08-30T00:00:01Z",
    });
    const poodle = breed({
      id: POODLE_ID,
      name: "푸들",
      slug: "poodle",
      sortOrder: 20,
    });
    const canonicalList = [updated, poodle];
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([current, poodle])
      .mockReturnValueOnce(
        new Promise<readonly Breed[]>((resolve) => {
          resolveCanonical = resolve;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(updated);

    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const trigger = await screen.findByRole("button", { name: "비숑 프리제 수정" });
    await user.click(trigger);
    expect(screen.getByLabelText("견종 이름")).toHaveFocus();
    expect(screen.getByLabelText("슬러그 (변경 불가)")).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("상태"), "published");
    await user.clear(screen.getByLabelText("견종 이름"));
    await user.type(screen.getByLabelText("견종 이름"), "비숑");
    await user.clear(screen.getByLabelText("설명"));
    await user.clear(screen.getByLabelText("정렬 순서"));
    await user.type(screen.getByLabelText("정렬 순서"), "30");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      `/api/admin/breeds/${BICHON_ID}`,
      "PUT",
      {
        status: "published",
        name: "비숑",
        description: null,
        sortOrder: 30,
      },
      expect.any(Function),
    );
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    resolveCanonical?.(canonicalList);
    await waitFor(() => expect(trigger).toBeEnabled());
    expect(screen.getByRole("button", { name: "비숑 수정" })).toHaveFocus();
    expect(screen.getByText("게시됨")).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "견종 목록" });
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => within(item).getByRole("heading", { level: 3 }).textContent),
    ).toEqual(canonicalList.map((item) => item.name));
  });

  it("create 뒤 canonical GET reject 후 warning과 trigger focus를 복구하고 explicit refresh도 허용한다", async () => {
    const user = userEvent.setup();
    let rejectCanonical: ((reason?: unknown) => void) | undefined;
    const created = breed({ sortOrder: 100 });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(
        new Promise<readonly Breed[]>((_, reject) => {
          rejectCanonical = reject;
        }),
      )
      .mockResolvedValueOnce([created]);
    const requestJsonMutation = vi.fn().mockResolvedValue(created);

    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 견종이 없습니다/);
    const trigger = screen.getByRole("button", { name: "새 견종" });
    await user.click(trigger);
    await user.type(screen.getByLabelText("견종 이름"), "비숑 프리제");
    await user.type(screen.getByLabelText("슬러그"), "bichon-frise");
    await user.click(screen.getByRole("button", { name: "견종 생성" }));

    expect(await screen.findByText("견종을 생성했습니다.")).toBeInTheDocument();
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    rejectCanonical?.(new AdminApiError("unavailable"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "저장은 완료됐지만 목록 순서를 새로고침하지 못했습니다",
    );
    await waitFor(() => expect(trigger).toBeEnabled());
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(screen.getByText("bichon-frise")).toBeInTheDocument();
    expect(screen.queryByText(/견종을 생성하지 못했습니다/)).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "새로고침" }));
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByText("bichon-frise")).toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("archive 후 restore를 허용하고 영구 삭제 action을 노출하지 않는다", async () => {
    const user = userEvent.setup();
    const archived = breed({ status: "archived" });
    const restored = breed({ status: "draft", updatedAt: "2026-08-30T00:00:02Z" });
    const requestJsonMutation = vi
      .fn()
      .mockResolvedValueOnce(archived)
      .mockResolvedValueOnce(restored);
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([breed()])
      .mockResolvedValueOnce([archived])
      .mockResolvedValueOnce([restored]);

    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await user.click(await screen.findByRole("button", { name: "비숑 프리제 수정" }));
    await user.selectOptions(screen.getByLabelText("상태"), "archived");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("보관됨")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "비숑 프리제 수정" }));
    await user.selectOptions(screen.getByLabelText("상태"), "draft");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("초안")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(2);
  });

  it("slug conflict와 mutation 403을 고정 문구로 표시하고 자동 재전송하지 않는다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValueOnce(new AdminApiError("slug-conflict"))
      .mockRejectedValueOnce(new AdminApiError("forbidden"));

    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([breed()]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText("bichon-frise");
    await user.click(screen.getByRole("button", { name: "새 견종" }));
    await user.type(screen.getByLabelText("견종 이름"), "푸들");
    await user.type(screen.getByLabelText("슬러그"), "poodle");
    await user.click(screen.getByRole("button", { name: "견종 생성" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 슬러그");

    await user.click(screen.getByRole("button", { name: "생성 취소" }));
    await user.click(screen.getByRole("button", { name: "비숑 프리제 수정" }));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("보안 요청을 확인할 수 없습니다");
    expect(requestJsonMutation).toHaveBeenCalledTimes(2);
  });

  it("CONTENT_NOT_FOUND를 stale content 안내로 표시한다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValue(new AdminApiError("content-not-found"));
    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([breed()]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "비숑 프리제 수정" }));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "목록을 새로고침해 주세요",
    );
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("create pending 중 중복 POST와 refresh 경쟁을 막는다", async () => {
    const user = userEvent.setup();
    let resolveCreate: ((value: Breed) => void) | undefined;
    const created = breed({ sortOrder: 100 });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([created]);
    const requestJsonMutation = vi.fn().mockReturnValue(
      new Promise<Breed>((resolve) => {
        resolveCreate = resolve;
      }),
    );
    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 견종이 없습니다/);
    await user.click(screen.getByRole("button", { name: "새 견종" }));
    await user.type(screen.getByLabelText("견종 이름"), "비숑 프리제");
    await user.type(screen.getByLabelText("슬러그"), "bichon-frise");
    await user.click(screen.getByRole("button", { name: "견종 생성" }));

    const pending = screen.getByRole("button", { name: "생성 중" });
    expect(pending).toBeDisabled();
    expect(screen.getByRole("button", { name: "새로고침" })).toBeDisabled();
    await user.click(pending);
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);

    resolveCreate?.(created);
    expect(await screen.findByText("bichon-frise")).toBeInTheDocument();
    expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2);
  });

  it("update pending 중 동일 item PUT을 한 번만 보내고 cancel은 원 trigger로 focus를 복귀한다", async () => {
    const user = userEvent.setup();
    let resolveUpdate: ((value: Breed) => void) | undefined;
    const updated = breed({ updatedAt: "2026-08-30T00:00:03Z" });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([breed()])
      .mockResolvedValueOnce([updated]);
    const requestJsonMutation = vi.fn().mockReturnValue(
      new Promise<Breed>((resolve) => {
        resolveUpdate = resolve;
      }),
    );
    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson,
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const trigger = await screen.findByRole("button", { name: "비숑 프리제 수정" });
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "수정 취소" }));
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    const pending = screen.getByRole("button", { name: "저장 중" });
    expect(pending).toBeDisabled();
    await user.click(pending);
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);

    resolveUpdate?.(updated);
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2);
  });

  it("mutation 이전 stale GET이 post-mutation canonical generation을 덮지 않는다", async () => {
    const user = userEvent.setup();
    let resolveStale: ((value: readonly Breed[]) => void) | undefined;
    const staleRequest = vi.fn().mockReturnValue(
      new Promise<readonly Breed[]>((resolve) => {
        resolveStale = resolve;
      }),
    );
    const current = breed();
    const updated = breed({
      name: "서버 최신 견종",
      updatedAt: "2026-08-30T00:00:04Z",
    });
    const currentRequest = vi
      .fn()
      .mockResolvedValueOnce([current])
      .mockResolvedValueOnce([updated]);
    const currentTransport = createTransport({
      requestAuthenticatedJson: currentRequest,
      requestJsonMutation: vi.fn().mockResolvedValue(updated),
    });
    const { rerender } = render(
      <AdminBreedManager
        transport={createTransport({ requestAuthenticatedJson: staleRequest })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await waitFor(() => expect(staleRequest).toHaveBeenCalledTimes(1));
    rerender(
      <AdminBreedManager
        transport={currentTransport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "비숑 프리제 수정" }));
    await user.clear(screen.getByLabelText("견종 이름"));
    await user.type(screen.getByLabelText("견종 이름"), "서버 최신 견종");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("서버 최신 견종")).toBeInTheDocument();
    expect(currentRequest).toHaveBeenCalledTimes(2);

    resolveStale?.([
      breed({ name: "오래된 견종", updatedAt: "2026-08-29T23:59:59Z" }),
    ]);
    await waitFor(() =>
      expect(screen.queryByText("오래된 견종")).not.toBeInTheDocument(),
    );
    expect(screen.getByText("서버 최신 견종")).toBeInTheDocument();
  });

  it("401은 session-expired callback으로 위임한다", async () => {
    const onSessionExpired = vi.fn();
    render(
      <AdminBreedManager
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
