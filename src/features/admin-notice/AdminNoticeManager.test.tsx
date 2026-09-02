import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";
import {
  instantToLocalDateTimeValue,
  localDateTimeValueToInstant,
} from "@/features/admin-content/timestamps";

import { AdminNoticeManager } from "./AdminNoticeManager";
import type { Notice } from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const HOLIDAY_ID = "d64047ee-93fe-4f87-949f-493d47ad6ee4";
const PARKING_ID = "1252ef09-6758-4af2-963d-9a65d0f369cf";

function notice(overrides: Partial<Notice> = {}): Notice {
  return {
    id: HOLIDAY_ID,
    status: "draft",
    title: "추석 연휴 운영 안내",
    slug: "chuseok-hours",
    summary: "연휴 운영 시간을 알려드립니다.",
    bodyMarkdown: "## 운영 시간\n\n- 오전 10시\n- 오후 6시",
    pinned: true,
    publishedAt: "2026-09-01T00:00:00.123456Z",
    expiresAt: "2026-09-10T00:00:00.123457Z",
    createdAt: "2026-08-30T00:00:00.000001Z",
    updatedAt: "2026-08-30T00:00:00.000002Z",
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
    requestJsonMutation: vi.fn().mockResolvedValue(notice()),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
    ...overrides,
  };
}

function listTitles(): Array<string | null> {
  return within(screen.getByRole("list", { name: "공지 목록" }))
    .getAllByRole("listitem")
    .map((item) => within(item).getByRole("heading", { level: 3 }).textContent);
}

describe("AdminNoticeManager", () => {
  it("loading, error, retry 뒤 backend array order를 재정렬 없이 보존하고 body를 card에 렌더링하지 않는다", async () => {
    const user = userEvent.setup();
    const serverOrdered = [
      notice({
        id: PARKING_ID,
        title: "주차 안내",
        slug: "parking",
        pinned: false,
        publishedAt: null,
        expiresAt: null,
      }),
      notice(),
    ];
    const requestAuthenticatedJson = vi
      .fn()
      .mockRejectedValueOnce(new AdminApiError("unavailable"))
      .mockResolvedValueOnce(serverOrdered);

    render(
      <AdminNoticeManager
        transport={createTransport({ requestAuthenticatedJson })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("공지 목록을 불러오고 있습니다");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "공지 목록을 불러오지 못했습니다",
    );
    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    await screen.findByRole("list", { name: "공지 목록" });
    expect(listTitles()).toEqual(serverOrdered.map((item) => item.title));
    expect(screen.queryByText("## 운영 시간")).not.toBeInTheDocument();
    expect(screen.getByText("일반 공지")).toBeInTheDocument();
    expect(screen.getByText("상단 고정")).toBeInTheDocument();
  });

  it("keyboard create가 exact draft body를 보내고 canonical GET 뒤 원 trigger로 focus를 복귀한다", async () => {
    const user = userEvent.setup();
    let resolveCanonical: ((value: readonly Notice[]) => void) | undefined;
    const created = notice({
      title: "임시 휴무 안내",
      slug: "temporary-closure",
      summary: null,
      bodyMarkdown: "# 휴무\n\n`9월 2일`",
      pinned: false,
      publishedAt: null,
      expiresAt: null,
    });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(
        new Promise<readonly Notice[]>((resolve) => {
          resolveCanonical = resolve;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(created);

    render(
      <AdminNoticeManager
        transport={createTransport({ requestAuthenticatedJson, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 공지가 없습니다/);
    const trigger = screen.getByRole("button", { name: "새 공지" });
    trigger.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByLabelText("제목")).toHaveFocus();
    await user.type(screen.getByLabelText("제목"), "임시 휴무 안내");
    await user.type(screen.getByLabelText("슬러그"), "temporary-closure");
    fireEvent.change(screen.getByLabelText("Markdown 본문"), {
      target: { value: "# 휴무\n\n`9월 2일`" },
    });
    await user.click(screen.getByRole("button", { name: "공지 생성" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      "/api/admin/notices",
      "POST",
      {
        title: "임시 휴무 안내",
        slug: "temporary-closure",
        summary: null,
        bodyMarkdown: "# 휴무\n\n`9월 2일`",
        pinned: false,
        publishedAt: null,
        expiresAt: null,
      },
      expect.any(Function),
    );
    expect(requestJsonMutation.mock.calls[0]?.[2]).not.toHaveProperty("status");
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();
    expect(trigger).not.toHaveFocus();

    resolveCanonical?.([created]);
    await waitFor(() => expect(trigger).toBeEnabled());
    expect(trigger).toHaveFocus();
    expect(screen.getByText("임시 휴무 안내")).toBeInTheDocument();
  });

  it("update가 immutable slug와 audit을 제외하고 microsecond 원본을 보존한다", async () => {
    const user = userEvent.setup();
    let resolveCanonical: ((value: readonly Notice[]) => void) | undefined;
    const current = notice();
    const updated = notice({
      status: "published",
      title: "연휴 운영 안내",
      summary: null,
      pinned: false,
      updatedAt: "2026-08-30T00:00:01.000003Z",
    });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([current])
      .mockReturnValueOnce(
        new Promise<readonly Notice[]>((resolve) => {
          resolveCanonical = resolve;
        }),
      );
    const requestJsonMutation = vi.fn().mockResolvedValue(updated);

    render(
      <AdminNoticeManager
        transport={createTransport({ requestAuthenticatedJson, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    const trigger = await screen.findByRole("button", {
      name: "추석 연휴 운영 안내 수정",
    });
    await user.click(trigger);
    expect(screen.getByLabelText("제목")).toHaveFocus();
    expect(screen.getByLabelText("슬러그 (변경 불가)")).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("상태"), "published");
    await user.clear(screen.getByLabelText("제목"));
    await user.type(screen.getByLabelText("제목"), "연휴 운영 안내");
    await user.clear(screen.getByLabelText("요약"));
    await user.click(screen.getByLabelText("상단 고정"));
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(requestJsonMutation).toHaveBeenCalledWith(
      `/api/admin/notices/${HOLIDAY_ID}`,
      "PUT",
      {
        status: "published",
        title: "연휴 운영 안내",
        summary: null,
        bodyMarkdown: current.bodyMarkdown,
        pinned: false,
        publishedAt: current.publishedAt,
        expiresAt: current.expiresAt,
      },
      expect.any(Function),
    );
    const body = requestJsonMutation.mock.calls[0]?.[2];
    expect(body).not.toHaveProperty("slug");
    expect(body).not.toHaveProperty("updatedAt");
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();

    resolveCanonical?.([updated]);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "연휴 운영 안내 수정" })).toHaveFocus(),
    );
    expect(screen.getByText("게시됨")).toBeInTheDocument();
  });

  it("window와 published 필수값을 현재 시각 규칙 없이 검증한다", async () => {
    const user = userEvent.setup();
    render(
      <AdminNoticeManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([notice()]),
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(
      await screen.findByRole("button", { name: "추석 연휴 운영 안내 수정" }),
    );
    fireEvent.change(screen.getByLabelText("게시 시각"), {
      target: { value: "2026-09-01T00:00:00.123" },
    });
    fireEvent.change(screen.getByLabelText("만료 시각"), {
      target: { value: "2026-09-01T00:00:00.123" },
    });
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "만료 시각은 게시 시각이 있을 때 그보다 늦어야 합니다",
    );

    fireEvent.change(screen.getByLabelText("만료 시각"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("게시 시각"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("Markdown 본문"), { target: { value: "" } });
    await user.selectOptions(screen.getByLabelText("상태"), "published");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "공백이 아닌 본문과 게시 시각",
    );
  });

  it("미래 게시 시각과 유효 window create를 허용한다", async () => {
    const user = userEvent.setup();
    const publishedInstant = "2099-01-01T00:00:00.000000Z";
    const expiresInstant = "2099-01-01T00:00:00.001000Z";
    const publishedLocal = instantToLocalDateTimeValue(publishedInstant);
    const expiresLocal = instantToLocalDateTimeValue(expiresInstant);
    const created = notice({
      title: "미래 공지",
      slug: "future-notice",
      publishedAt: localDateTimeValueToInstant(publishedLocal) ?? null,
      expiresAt: localDateTimeValueToInstant(expiresLocal) ?? null,
    });
    const requestJsonMutation = vi.fn().mockResolvedValue(created);
    render(
      <AdminNoticeManager
        transport={createTransport({
          requestAuthenticatedJson: vi
            .fn()
            .mockResolvedValueOnce([])
            .mockResolvedValueOnce([created]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 공지가 없습니다/);
    await user.click(screen.getByRole("button", { name: "새 공지" }));
    await user.type(screen.getByLabelText("제목"), "미래 공지");
    await user.type(screen.getByLabelText("슬러그"), "future-notice");
    fireEvent.change(screen.getByLabelText("게시 시각"), {
      target: { value: publishedLocal },
    });
    fireEvent.change(screen.getByLabelText("만료 시각"), {
      target: { value: expiresLocal },
    });
    await user.click(screen.getByRole("button", { name: "공지 생성" }));

    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
    expect(requestJsonMutation.mock.calls[0]?.[2]).toMatchObject({
      publishedAt: expect.stringMatching(/\.000000Z$/),
      expiresAt: expect.stringMatching(/\.001000Z$/),
    });
  });

  it("post-mutation GET 실패를 저장 실패와 분리하고 explicit refresh와 focus를 복구한다", async () => {
    const user = userEvent.setup();
    let rejectCanonical: ((reason?: unknown) => void) | undefined;
    const created = notice({ title: "새 공지", slug: "new-notice" });
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(
        new Promise<readonly Notice[]>((_, reject) => {
          rejectCanonical = reject;
        }),
      )
      .mockResolvedValueOnce([created]);
    const requestJsonMutation = vi.fn().mockResolvedValue(created);
    render(
      <AdminNoticeManager
        transport={createTransport({ requestAuthenticatedJson, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 공지가 없습니다/);
    const trigger = screen.getByRole("button", { name: "새 공지" });
    await user.click(trigger);
    await user.type(screen.getByLabelText("제목"), "새 공지");
    await user.type(screen.getByLabelText("슬러그"), "new-notice");
    await user.click(screen.getByRole("button", { name: "공지 생성" }));
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(2));
    expect(trigger).toBeDisabled();

    rejectCanonical?.(new AdminApiError("unavailable"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "저장은 완료됐지만 목록 순서를 새로고침하지 못했습니다",
    );
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(screen.getByText("new-notice")).toBeInTheDocument();
    expect(screen.queryByText(/공지를 생성하지 못했습니다/)).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "새로고침" }));
    await waitFor(() => expect(requestAuthenticatedJson).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("archive와 restore를 허용하고 영구 delete action을 노출하지 않는다", async () => {
    const user = userEvent.setup();
    const archived = notice({ status: "archived" });
    const restored = notice({ status: "draft", updatedAt: "2026-08-30T00:00:02Z" });
    const requestJsonMutation = vi
      .fn()
      .mockResolvedValueOnce(archived)
      .mockResolvedValueOnce(restored);
    const requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValueOnce([notice()])
      .mockResolvedValueOnce([archived])
      .mockResolvedValueOnce([restored]);
    render(
      <AdminNoticeManager
        transport={createTransport({ requestAuthenticatedJson, requestJsonMutation })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(
      await screen.findByRole("button", { name: "추석 연휴 운영 안내 수정" }),
    );
    await user.selectOptions(screen.getByLabelText("상태"), "archived");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("보관됨")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "추석 연휴 운영 안내 수정" }));
    await user.selectOptions(screen.getByLabelText("상태"), "draft");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("초안")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(2);
  });

  it("allowlisted 오류를 고정 문구로 표시하고 403 mutation을 자동 재전송하지 않는다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValueOnce(new AdminApiError("notice-window-invalid"))
      .mockRejectedValueOnce(new AdminApiError("forbidden"));
    render(
      <AdminNoticeManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([notice()]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    await user.click(
      await screen.findByRole("button", { name: "추석 연휴 운영 안내 수정" }),
    );
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "만료 시각은 게시 시각이 있을 때",
    );
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "보안 요청을 확인할 수 없습니다",
    );
    expect(requestJsonMutation).toHaveBeenCalledTimes(2);
  });

  it("중복 slug를 backend detail 없이 frontend 문구로 표시한다", async () => {
    const user = userEvent.setup();
    const requestJsonMutation = vi
      .fn()
      .mockRejectedValue(new AdminApiError("slug-conflict"));
    render(
      <AdminNoticeManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await screen.findByText(/등록된 공지가 없습니다/);
    await user.click(screen.getByRole("button", { name: "새 공지" }));
    await user.type(screen.getByLabelText("제목"), "중복 공지");
    await user.type(screen.getByLabelText("슬러그"), "duplicate-notice");
    await user.click(screen.getByRole("button", { name: "공지 생성" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "이미 사용 중인 슬러그",
    );
    expect(screen.queryByText("backend raw message")).not.toBeInTheDocument();
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["invalid-request", "입력 형식을 확인해 주세요"],
    ["content-not-found", "공지 정보가 달라졌습니다"],
    ["publish-validation-failed", "게시하려면 본문과 게시 시각"],
  ] as const)("%s update 오류를 frontend 문구로 표시한다", async (kind, message) => {
    const user = userEvent.setup();
    const requestJsonMutation = vi.fn().mockRejectedValue(new AdminApiError(kind));
    render(
      <AdminNoticeManager
        transport={createTransport({
          requestAuthenticatedJson: vi.fn().mockResolvedValue([notice()]),
          requestJsonMutation,
        })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await user.click(
      await screen.findByRole("button", { name: "추석 연휴 운영 안내 수정" }),
    );
    await user.click(screen.getByRole("button", { name: "변경 저장" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(requestJsonMutation).toHaveBeenCalledTimes(1);
  });

  it("cancel은 즉시 focus를 복귀하고 mutation 이전 stale GET은 최신 목록을 덮지 않는다", async () => {
    const user = userEvent.setup();
    let resolveStale: ((value: readonly Notice[]) => void) | undefined;
    const staleRequest = vi.fn().mockReturnValue(
      new Promise<readonly Notice[]>((resolve) => {
        resolveStale = resolve;
      }),
    );
    const updated = notice({ title: "서버 최신 공지" });
    const currentRequest = vi
      .fn()
      .mockResolvedValueOnce([notice()])
      .mockResolvedValueOnce([updated]);
    const currentTransport = createTransport({
      requestAuthenticatedJson: currentRequest,
      requestJsonMutation: vi.fn().mockResolvedValue(updated),
    });
    const { rerender } = render(
      <AdminNoticeManager
        transport={createTransport({ requestAuthenticatedJson: staleRequest })}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );
    await waitFor(() => expect(staleRequest).toHaveBeenCalledTimes(1));
    rerender(
      <AdminNoticeManager
        transport={currentTransport}
        onBack={vi.fn()}
        onSessionExpired={vi.fn()}
      />,
    );

    const trigger = await screen.findByRole("button", {
      name: "추석 연휴 운영 안내 수정",
    });
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "수정 취소" }));
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await user.clear(screen.getByLabelText("제목"));
    await user.type(screen.getByLabelText("제목"), "서버 최신 공지");
    await user.click(screen.getByRole("button", { name: "변경 저장" }));
    expect(await screen.findByText("서버 최신 공지")).toBeInTheDocument();
    resolveStale?.([notice({ title: "오래된 공지" })]);
    await waitFor(() => expect(screen.queryByText("오래된 공지")).not.toBeInTheDocument());
    expect(screen.getByText("서버 최신 공지")).toBeInTheDocument();
  });

  it("401은 session-expired callback으로 위임한다", async () => {
    const onSessionExpired = vi.fn();
    render(
      <AdminNoticeManager
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
