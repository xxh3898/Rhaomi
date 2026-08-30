import { describe, expect, it, vi } from "vitest";

import { AdminApiError, DefaultAdminAuthClient } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminGalleryApi } from "./api";
import type {
  CreateGalleryRequest,
  GalleryItem,
  UpdateGalleryRequest,
} from "./types";

const GALLERY: GalleryItem = {
  id: "d64047ee-93fe-4f87-949f-493d47ad6ee4",
  status: "draft",
  dogName: null,
  breedId: null,
  primaryServiceId: null,
  coverImageId: null,
  beforeImageId: null,
  afterImageId: null,
  summary: null,
  altText: null,
  featured: false,
  sortOrder: 100,
  performedAt: null,
  publishedAt: null,
  createdAt: "2026-08-30T00:00:00Z",
  updatedAt: "2026-08-30T00:00:00Z",
  createdBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  updatedBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
};

const CREATE_REQUEST: CreateGalleryRequest = {
  dogName: null,
  breedId: null,
  primaryServiceId: null,
  coverImageId: null,
  beforeImageId: null,
  afterImageId: null,
  summary: null,
  altText: null,
  featured: false,
  sortOrder: null,
  performedAt: null,
  publishedAt: null,
};

const UPDATE_REQUEST: UpdateGalleryRequest = {
  status: "archived",
  dogName: null,
  breedId: null,
  primaryServiceId: null,
  coverImageId: null,
  beforeImageId: null,
  afterImageId: null,
  summary: null,
  altText: null,
  featured: false,
  sortOrder: 100,
  performedAt: null,
  publishedAt: null,
};

function createTransport(): AdminApiTransport {
  return {
    requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
      Promise.resolve(path === "/api/admin/gallery-items" ? [GALLERY] : GALLERY),
    ),
    requestJsonMutation: vi.fn().mockImplementation(
      (_path: string, _method: string, body: { status?: GalleryItem["status"] }) =>
        Promise.resolve(body.status ? { ...GALLERY, status: body.status } : GALLERY),
    ),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
  };
}

describe("AdminGalleryApi", () => {
  it("exact list/detail/create/update path와 request body를 사용한다", async () => {
    const transport = createTransport();
    const api = new AdminGalleryApi(transport);

    await api.list();
    await api.get(GALLERY.id);
    await api.create(CREATE_REQUEST);
    await api.update(GALLERY.id, UPDATE_REQUEST);

    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      1,
      "/api/admin/gallery-items",
      expect.any(Function),
    );
    expect(transport.requestAuthenticatedJson).toHaveBeenNthCalledWith(
      2,
      `/api/admin/gallery-items/${GALLERY.id}`,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      1,
      "/api/admin/gallery-items",
      "POST",
      CREATE_REQUEST,
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      2,
      `/api/admin/gallery-items/${GALLERY.id}`,
      "PUT",
      UPDATE_REQUEST,
      expect.any(Function),
    );
  });

  it("unexpected relation object와 malformed nullable/timestamp field를 거부한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify([{ ...GALLERY, breed: { id: GALLERY.breedId } }]),
        { headers: { "Content-Type": "application/json" } },
      ),
    );
    const api = new AdminGalleryApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(api.list()).rejects.toEqual(new AdminApiError("unavailable"));
  });

  it("create response가 draft가 아니면 거부한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf" }), {
          headers: { "Content-Type": "application/json" },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...GALLERY, status: "published" }), {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }),
      );
    const api = new AdminGalleryApi(new DefaultAdminAuthClient({ fetcher }));

    await expect(api.create(CREATE_REQUEST)).rejects.toEqual(
      new AdminApiError("unavailable"),
    );
  });

  it("detail/update response의 id와 update status mismatch를 거부한다", async () => {
    const transport = createTransport();
    transport.requestAuthenticatedJson = vi
      .fn()
      .mockResolvedValue({ ...GALLERY, id: GALLERY.createdBy });
    transport.requestJsonMutation = vi
      .fn()
      .mockResolvedValue({ ...GALLERY, status: "draft" });
    const api = new AdminGalleryApi(transport);

    await expect(api.get(GALLERY.id)).rejects.toEqual(
      new AdminApiError("unavailable"),
    );
    await expect(api.update(GALLERY.id, UPDATE_REQUEST)).rejects.toEqual(
      new AdminApiError("unavailable"),
    );
  });
});
