import { describe, expect, it, vi } from "vitest";

import { AdminApiError, DefaultAdminAuthClient } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminBreedApi } from "./api";
import type { Breed, CreateBreedRequest, UpdateBreedRequest } from "./types";

const BREED: Breed = {
  id: "d64047ee-93fe-4f87-949f-493d47ad6ee4",
  status: "draft",
  name: "비숑 프리제",
  slug: "bichon-frise",
  description: "밝고 다정한 소형견",
  sortOrder: 10,
  createdAt: "2026-08-30T00:00:00Z",
  updatedAt: "2026-08-30T00:00:00Z",
  createdBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  updatedBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
};

function createTransport(): AdminApiTransport {
  return {
    requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
      Promise.resolve(path === "/api/admin/breeds" ? [BREED] : BREED),
    ),
    requestJsonMutation: vi.fn().mockImplementation(
      (_path: string, _method: string, request: { status?: Breed["status"] }) =>
        Promise.resolve(
          request.status ? { ...BREED, status: request.status } : BREED,
        ),
    ),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
  };
}

describe("AdminBreedApi", () => {
  it("list/detail/create/update가 기존 breed endpoint와 정확한 body를 사용한다", async () => {
    const transport = createTransport();
    const api = new AdminBreedApi(transport);
    const createRequest: CreateBreedRequest = {
      name: "비숑 프리제",
      slug: "bichon-frise",
      description: null,
      sortOrder: null,
    };
    const updateRequest: UpdateBreedRequest = {
      status: "published",
      name: "비숑 프리제",
      description: "밝고 다정한 소형견",
      sortOrder: 10,
    };

    await api.list();
    await api.get(BREED.id);
    await api.create(createRequest);
    await api.update(BREED.id, updateRequest);

    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      1,
      "/api/admin/breeds",
      expect.any(Function),
    );
    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      2,
      `/api/admin/breeds/${BREED.id}`,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      1,
      "/api/admin/breeds",
      "POST",
      createRequest,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      2,
      `/api/admin/breeds/${BREED.id}`,
      "PUT",
      updateRequest,
      expect.any(Function),
    );
    expect(updateRequest).not.toHaveProperty("slug");
  });

  it("unexpected 내부 field와 malformed breed response를 거부한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ ...BREED, passwordHash: "must-not-pass" }]), {
        headers: { "Content-Type": "application/json" },
      }),
    );
    const api = new AdminBreedApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(api.list()).rejects.toEqual(new AdminApiError("unavailable"));
  });

  it("detail response의 UUID, enum, slug, sortOrder, Instant를 strict하게 검증한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ...BREED, sortOrder: -1 }), {
        headers: { "Content-Type": "application/json" },
      }),
    );
    const api = new AdminBreedApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(api.get(BREED.id)).rejects.toEqual(
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
        new Response(JSON.stringify({ ...BREED, status: "published" }), {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }),
      );
    const api = new AdminBreedApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(
      api.create({
        name: BREED.name,
        slug: BREED.slug,
        description: null,
        sortOrder: null,
      }),
    ).rejects.toEqual(new AdminApiError("unavailable"));
  });
});
