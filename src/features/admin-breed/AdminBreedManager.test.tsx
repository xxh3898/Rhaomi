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
  it("loading, empty, retry와 sortOrder/name/id 정렬을 구분한다", async () => {
    const user = userEvent.setup();
    const requestAuthenticatedJson = vi
      .fn()
      .mockRejectedValueOnce(new AdminApiError("unavailable"))
      .mockResolvedValueOnce([
        breed({ id: POODLE_ID, name: "푸들", slug: "poodle", sortOrder: 20 }),
        breed(),
      ]);

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
    expect(within(list).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      expect.stringContaining("비숑 프리제"),
      expect.stringContaining("푸들"),
    ]);
  });

  it("keyboard create open, blank sortOrder null, canonical response와 focus 복귀를 보장한다", async () => {
    const user = userEvent.setup();
    const created = breed({ sortOrder: 100 });
    const requestJsonMutation = vi.fn().mockResolvedValue(created);

    render(
      <AdminBreedManager
        transport={createTransport({ requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 견종이 없습니다/);

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
    expect(await screen.findByText("bichon-frise")).toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("edit에서 slug를 잠그고 full PUT response로 재정렬한 뒤 action으로 focus를 돌린다", async () => {
    const user = userEvent.setup();
    const current = breed();
    const updated = breed({
      status: "published",
      name: "비숑",
      description: null,
      sortOrder: 30,
      updatedAt: "2026-08-30T00:00:01Z",
    });
    const requestJsonMutation = vi.fn().mockResolvedValue(updated);

    render(
      <AdminBreedManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([
            current,
            breed({
              id: POODLE_ID,
              name: "푸들",
              slug: "poodle",
              sortOrder: 20,
            }),
          ]),
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
    expect(await screen.findByRole("button", { name: "비숑 수정" })).toHaveFocus();
    expect(screen.getByText("게시됨")).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "견종 목록" });
    expect(within(list).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      expect.stringContaining("푸들"),
      expect.stringContaining("비숑"),
    ]);
  });

  it("archive 후 restore를 허용하고 영구 삭제 action을 노출하지 않는다", async () => {
    const user = userEvent.setup();
    const archived = breed({ status: "archived" });
    const restored = breed({ status: "draft", updatedAt: "2026-08-30T00:00:02Z" });
    const requestJsonMutation = vi
      .fn()
      .mockResolvedValueOnce(archived)
      .mockResolvedValueOnce(restored);

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
    const requestJsonMutation = vi.fn().mockReturnValue(
      new Promise<Breed>((resolve) => {
        resolveCreate = resolve;
      }),
    );
    render(
      <AdminBreedManager
        transport={createTransport({ requestJsonMutation })}
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

    resolveCreate?.(breed({ sortOrder: 100 }));
    expect(await screen.findByText("bichon-frise")).toBeInTheDocument();
  });

  it("update pending 중 동일 item PUT을 한 번만 보내고 cancel은 원 trigger로 focus를 복귀한다", async () => {
    const user = userEvent.setup();
    let resolveUpdate: ((value: Breed) => void) | undefined;
    const requestJsonMutation = vi.fn().mockReturnValue(
      new Promise<Breed>((resolve) => {
        resolveUpdate = resolve;
      }),
    );
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

    resolveUpdate?.(breed({ updatedAt: "2026-08-30T00:00:03Z" }));
    await waitFor(() => expect(trigger).toHaveFocus());
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
