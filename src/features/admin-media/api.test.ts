import { describe, expect, it, vi } from "vitest";

import { AdminApiError, DefaultAdminAuthClient } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminMediaApi } from "./api";
import type { MediaItem } from "./types";

const MEDIA: MediaItem = {
  id: "d64047ee-93fe-4f87-949f-493d47ad6ee4",
  status: "active",
  sourceContentType: "image/heic",
  contentType: "image/jpeg",
  sourceByteSize: 1234,
  byteSize: 987,
  width: 1200,
  height: 900,
  createdAt: "2026-08-30T00:00:00Z",
  updatedAt: "2026-08-30T00:00:00Z",
  createdBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  updatedBy: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
};

function createTransport(): AdminApiTransport {
  return {
    requestAuthenticatedJson: vi.fn().mockResolvedValue([MEDIA]),
    requestJsonMutation: vi.fn().mockResolvedValue(MEDIA),
    requestMultipartMutation: vi.fn().mockResolvedValue(MEDIA),
    requestAuthenticatedBlob: vi
      .fn()
      .mockResolvedValue(new Blob(["image"], { type: "image/jpeg" })),
  };
}

describe("AdminMediaApi", () => {
  it("malformed media response shape를 unavailable로 거부한다", async () => {
    const client = new DefaultAdminAuthClient({
      fetcher: vi.fn().mockResolvedValue(
        new Response(JSON.stringify([{ ...MEDIA, byteSize: "broken" }]), {
          headers: { "Content-Type": "application/json" },
        }),
      ),
    });
    const api = new AdminMediaApi(client);

    await expect(api.list()).rejects.toEqual(new AdminApiError("unavailable"));
  });

  it("relative media list와 private content path만 사용한다", async () => {
    const transport = createTransport();
    const api = new AdminMediaApi(transport);

    await expect(api.list()).resolves.toEqual([MEDIA]);
    await expect(api.content(MEDIA.id)).resolves.toBeInstanceOf(Blob);

    expect(transport.requestAuthenticatedJson).toHaveBeenCalledWith(
      "/api/admin/media",
      expect.any(Function),
    );
    expect(transport.requestAuthenticatedBlob).toHaveBeenCalledWith(
      `/api/admin/media/${MEDIA.id}/content`,
      ["image/jpeg", "image/png"],
    );
  });

  it("upload는 file part 하나를 가진 FormData를 한 번 전송한다", async () => {
    const transport = createTransport();
    const api = new AdminMediaApi(transport);
    const file = new File(["image"], "photo.heic", { type: "image/heic" });

    await expect(api.upload(file)).resolves.toEqual(MEDIA);

    expect(transport.requestMultipartMutation).toHaveBeenCalledTimes(1);
    const [path, method, body] = vi.mocked(transport.requestMultipartMutation).mock
      .calls[0]!;
    expect(path).toBe("/api/admin/media");
    expect(method).toBe("POST");
    expect(body).toBeInstanceOf(FormData);
    expect([...body.keys()]).toEqual(["file"]);
    expect(body.get("file")).toBe(file);
  });

  it("archive와 restore를 status-only PUT body로 전송한다", async () => {
    const transport = createTransport();
    const api = new AdminMediaApi(transport);

    await api.updateStatus(MEDIA.id, "archived");
    await api.updateStatus(MEDIA.id, "active");

    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      1,
      `/api/admin/media/${MEDIA.id}`,
      "PUT",
      { status: "archived" },
      expect.any(Function),
    );
    expect(transport.requestJsonMutation).toHaveBeenNthCalledWith(
      2,
      `/api/admin/media/${MEDIA.id}`,
      "PUT",
      { status: "active" },
      expect.any(Function),
    );
  });
});
