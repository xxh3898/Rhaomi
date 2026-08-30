import { describe, expect, it, vi } from "vitest";

import { AdminApiError, DefaultAdminAuthClient } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminServiceApi } from "./api";
import type {
  CreateServiceRequest,
  GroomingService,
  UpdateServiceRequest,
} from "./types";

const SERVICE: GroomingService = {
  id: "d64047ee-93fe-4f87-949f-493d47ad6ee4",
  status: "draft",
  name: "기본 미용",
  slug: "basic-grooming",
  description: "목욕과 기본 커트",
  priceText: "상담 후 안내",
  sortOrder: 10,
  createdAt: "2026-08-30T00:00:00Z",
  updatedAt: "2026-08-30T00:00:00Z",
  createdBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  updatedBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
};

function createTransport(): AdminApiTransport {
  return {
    requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
      Promise.resolve(path === "/api/admin/services" ? [SERVICE] : SERVICE),
    ),
    requestJsonMutation: vi.fn().mockImplementation(
      (
        _path: string,
        _method: string,
        request: { status?: GroomingService["status"] },
      ) =>
        Promise.resolve(
          request.status ? { ...SERVICE, status: request.status } : SERVICE,
        ),
    ),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
  };
}

describe("AdminServiceApi", () => {
  it("list/detail/create/update가 기존 service endpoint와 정확한 body를 사용한다", async () => {
    const transport = createTransport();
    const api = new AdminServiceApi(transport);
    const createRequest: CreateServiceRequest = {
      name: "기본 미용",
      slug: "basic-grooming",
      description: null,
      priceText: null,
      sortOrder: null,
    };
    const updateRequest: UpdateServiceRequest = {
      status: "published",
      name: "기본 미용",
      description: "목욕과 기본 커트",
      priceText: "상담 후 안내",
      sortOrder: 10,
    };

    await api.list();
    await api.get(SERVICE.id);
    await api.create(createRequest);
    await api.update(SERVICE.id, updateRequest);

    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      1,
      "/api/admin/services",
      expect.any(Function),
    );
    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      2,
      `/api/admin/services/${SERVICE.id}`,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      1,
      "/api/admin/services",
      "POST",
      createRequest,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      2,
      `/api/admin/services/${SERVICE.id}`,
      "PUT",
      updateRequest,
      expect.any(Function),
    );
    expect(updateRequest).not.toHaveProperty("slug");
  });

  it("unexpected 내부 field와 malformed service response를 거부한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ ...SERVICE, storageKey: "private" }]), {
        headers: { "Content-Type": "application/json" },
      }),
    );
    const api = new AdminServiceApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(api.list()).rejects.toEqual(new AdminApiError("unavailable"));
  });

  it("detail response의 nullable field와 audit Instant를 strict하게 검증한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ...SERVICE, updatedAt: "not-an-instant" }), {
        headers: { "Content-Type": "application/json" },
      }),
    );
    const api = new AdminServiceApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(api.get(SERVICE.id)).rejects.toEqual(
      new AdminApiError("unavailable"),
    );
  });

  it("create 성공 response가 draft가 아니면 contract drift로 거부한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
          { headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...SERVICE, status: "published" }), {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }),
      );
    const api = new AdminServiceApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(
      api.create({
        name: SERVICE.name,
        slug: SERVICE.slug,
        description: null,
        priceText: null,
        sortOrder: null,
      }),
    ).rejects.toEqual(new AdminApiError("unavailable"));
  });
});
