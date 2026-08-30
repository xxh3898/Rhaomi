import { describe, expect, it, vi } from "vitest";

import { AdminApiError, DefaultAdminAuthClient } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminNoticeApi } from "./api";
import type {
  CreateNoticeRequest,
  Notice,
  UpdateNoticeRequest,
} from "./types";

const NOTICE: Notice = {
  id: "d64047ee-93fe-4f87-949f-493d47ad6ee4",
  status: "draft",
  title: "추석 연휴 운영 안내",
  slug: "chuseok-hours",
  summary: null,
  bodyMarkdown: null,
  pinned: false,
  publishedAt: null,
  expiresAt: null,
  createdAt: "2026-08-30T00:00:00.000001Z",
  updatedAt: "2026-08-30T00:00:00.000002Z",
  createdBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  updatedBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
};

function createTransport(): AdminApiTransport {
  return {
    requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
      Promise.resolve(path === "/api/admin/notices" ? [NOTICE] : NOTICE),
    ),
    requestJsonMutation: vi.fn().mockImplementation(
      (_path: string, _method: string, request: { status?: Notice["status"] }) =>
        Promise.resolve(
          request.status ? { ...NOTICE, status: request.status } : NOTICE,
        ),
    ),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
  };
}

describe("AdminNoticeApi", () => {
  it("list/detail/create/update가 Notice endpoint와 exact body를 사용한다", async () => {
    const transport = createTransport();
    const api = new AdminNoticeApi(transport);
    const createRequest: CreateNoticeRequest = {
      title: NOTICE.title,
      slug: NOTICE.slug,
      summary: null,
      bodyMarkdown: null,
      pinned: false,
      publishedAt: null,
      expiresAt: null,
    };
    const updateRequest: UpdateNoticeRequest = {
      status: "archived",
      title: NOTICE.title,
      summary: "연휴 안내",
      bodyMarkdown: null,
      pinned: true,
      publishedAt: null,
      expiresAt: null,
    };

    await api.list();
    await api.get(NOTICE.id);
    await api.create(createRequest);
    await api.update(NOTICE.id, updateRequest);

    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      1,
      "/api/admin/notices",
      expect.any(Function),
    );
    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      2,
      `/api/admin/notices/${NOTICE.id}`,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      1,
      "/api/admin/notices",
      "POST",
      createRequest,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      2,
      `/api/admin/notices/${NOTICE.id}`,
      "PUT",
      updateRequest,
      expect.any(Function),
    );
    expect(createRequest).not.toHaveProperty("status");
    expect(updateRequest).not.toHaveProperty("slug");
  });

  it("unexpected 내부 field와 malformed response를 거부한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ ...NOTICE, internalRevision: 1 }]), {
        headers: { "Content-Type": "application/json" },
      }),
    );
    const api = new AdminNoticeApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(api.list()).rejects.toEqual(new AdminApiError("unavailable"));
  });

  it("detail id가 요청 id와 다르면 contract drift로 거부한다", async () => {
    const transport = createTransport();
    vi.mocked(transport.requestAuthenticatedJson).mockResolvedValueOnce({
      ...NOTICE,
      id: "1c749ded-a05f-4e84-bc9d-f865a64784be",
    });
    const api = new AdminNoticeApi(transport);

    await expect(api.get(NOTICE.id)).rejects.toEqual(
      new AdminApiError("unavailable"),
    );
  });

  it("create가 draft가 아니거나 update status가 다르면 거부한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
          { headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...NOTICE, status: "published" }), {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }),
      );
    const api = new AdminNoticeApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(
      api.create({
        title: NOTICE.title,
        slug: NOTICE.slug,
        summary: null,
        bodyMarkdown: null,
        pinned: false,
        publishedAt: null,
        expiresAt: null,
      }),
    ).rejects.toEqual(new AdminApiError("unavailable"));

    const transport = createTransport();
    vi.mocked(transport.requestJsonMutation).mockResolvedValueOnce({
      ...NOTICE,
      status: "draft",
    });
    const updateApi = new AdminNoticeApi(transport);
    await expect(
      updateApi.update(NOTICE.id, {
        status: "archived",
        title: NOTICE.title,
        summary: null,
        bodyMarkdown: null,
        pinned: false,
        publishedAt: null,
        expiresAt: null,
      }),
    ).rejects.toEqual(new AdminApiError("unavailable"));
  });
});
