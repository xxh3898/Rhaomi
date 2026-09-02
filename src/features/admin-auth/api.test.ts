import { describe, expect, it, vi } from "vitest";

import {
  AdminApiError,
  DefaultAdminAuthClient,
} from "./api";

const ADMIN = {
  id: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  email: "admin@example.test",
  role: "ADMIN",
} as const;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function emptyResponse(status: number): Response {
  return new Response(null, { status });
}

function acceptsRecord(value: unknown): value is Record<string, unknown> {
  void value;
  return true;
}

describe("DefaultAdminAuthClient", () => {
  it("session 조회를 상대경로, same-origin credential, no-store로 수행한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse(ADMIN));
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(client.getSession()).resolves.toEqual(ADMIN);

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/admin/auth/me");
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
    });
  });

  it("initial me의 401만 anonymous로 반환한다", async () => {
    const client = new DefaultAdminAuthClient({
      fetcher: vi.fn().mockResolvedValue(emptyResponse(401)),
    });

    await expect(client.getSession()).resolves.toBeNull();
  });

  it.each([
    ["5xx", vi.fn().mockResolvedValue(emptyResponse(503))],
    ["network", vi.fn().mockRejectedValue(new Error("network detail"))],
    ["malformed", vi.fn().mockResolvedValue(jsonResponse({ email: "broken" }))],
  ])("initial me의 %s 오류를 unavailable로 구분한다", async (_name, fetcher) => {
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(client.getSession()).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("login POST와 session용 fresh CSRF 준비를 명확한 단계로 분리한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "before-login" }),
      )
      .mockResolvedValueOnce(jsonResponse(ADMIN))
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "after-login" }),
      )
      .mockResolvedValueOnce(emptyResponse(204));
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(
      client.login({ email: ADMIN.email, password: "test-password" }),
    ).resolves.toEqual(ADMIN);
    expect(fetcher).toHaveBeenCalledTimes(2);

    await expect(client.prepareSessionCsrf()).resolves.toBeUndefined();
    await expect(client.logout()).resolves.toBe("logged-out");

    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      "/api/admin/auth/csrf",
      "/api/admin/auth/login",
      "/api/admin/auth/csrf",
      "/api/admin/auth/logout",
    ]);

    const loginHeaders = new Headers(fetcher.mock.calls[1]?.[1]?.headers);
    expect(loginHeaders.get("X-CSRF-TOKEN")).toBe("before-login");
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
    });
    expect(JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body))).toEqual({
      email: ADMIN.email,
      password: "test-password",
    });

    const logoutHeaders = new Headers(fetcher.mock.calls[3]?.[1]?.headers);
    expect(logoutHeaders.get("X-CSRF-TOKEN")).toBe("after-login");
  });

  it("login 성공 뒤 pre-login CSRF를 post-login mutation에 재사용하지 않는다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "pre-login" }),
      )
      .mockResolvedValueOnce(jsonResponse(ADMIN))
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "post-login" }),
      )
      .mockResolvedValueOnce(emptyResponse(204));
    const client = new DefaultAdminAuthClient({ fetcher });

    await client.login({ email: ADMIN.email, password: "test-password" });
    await expect(client.logout()).resolves.toBe("logged-out");

    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      "/api/admin/auth/csrf",
      "/api/admin/auth/login",
      "/api/admin/auth/csrf",
      "/api/admin/auth/logout",
    ]);
    const logoutHeaders = new Headers(fetcher.mock.calls[3]?.[1]?.headers);
    expect(logoutHeaders.get("X-CSRF-TOKEN")).toBe("post-login");
    expect(logoutHeaders.get("X-CSRF-TOKEN")).not.toBe("pre-login");
  });

  it.each([
    [400, "invalid-request"],
    [401, "invalid-credentials"],
    [403, "forbidden"],
    [503, "service-unavailable"],
    [500, "unavailable"],
  ])("login status %i를 %s로 매핑한다", async (status, kind) => {
    const client = new DefaultAdminAuthClient({
      fetcher: vi
        .fn()
        .mockResolvedValueOnce(
          jsonResponse({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
        )
        .mockResolvedValueOnce(jsonResponse({ message: "노출 금지 detail" }, status)),
    });

    await expect(
      client.login({ email: ADMIN.email, password: "wrong" }),
    ).rejects.toMatchObject({ kind });
  });

  it("잘못된 CSRF shape를 request에 사용하지 않는다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse({ headerName: "bad header\nname", token: "csrf" }),
    );
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(
      client.login({ email: ADMIN.email, password: "test-password" }),
    ).rejects.toMatchObject({ kind: "unavailable" });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("session용 fresh CSRF 준비 실패 뒤에도 pre-login token을 남기지 않는다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "pre-login" }),
      )
      .mockResolvedValueOnce(jsonResponse(ADMIN))
      .mockRejectedValueOnce(new Error("fresh csrf unavailable"))
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "retry-fresh" }),
      )
      .mockResolvedValueOnce(emptyResponse(204));
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(
      client.login({ email: ADMIN.email, password: "test-password" }),
    ).resolves.toEqual(ADMIN);
    await expect(client.prepareSessionCsrf()).rejects.toMatchObject({
      kind: "unavailable",
    });
    await expect(client.logout()).resolves.toBe("logged-out");

    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      "/api/admin/auth/csrf",
      "/api/admin/auth/login",
      "/api/admin/auth/csrf",
      "/api/admin/auth/csrf",
      "/api/admin/auth/logout",
    ]);
    const logoutHeaders = new Headers(fetcher.mock.calls[4]?.[1]?.headers);
    expect(logoutHeaders.get("X-CSRF-TOKEN")).toBe("retry-fresh");
  });

  it("logout 401은 종료된 session으로 처리한다", async () => {
    const onSessionExpired = vi.fn();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
      )
      .mockResolvedValueOnce(emptyResponse(401));
    const client = new DefaultAdminAuthClient({ fetcher, onSessionExpired });

    await expect(client.logout()).resolves.toBe("session-ended");
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("WebAuthn 검증 401을 session expiry로 위장하거나 자동 재전송하지 않는다", async () => {
    const onSessionExpired = vi.fn();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "mfa-csrf" }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ code: "WEBAUTHN_VERIFICATION_FAILED" }, 401),
      );
    const client = new DefaultAdminAuthClient({ fetcher, onSessionExpired });

    await expect(client.verifyRecoveryCode("00000000-11111111-22222222-33333333"))
      .rejects.toMatchObject({ kind: "webauthn-failed" });

    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      "/api/admin/auth/csrf",
      "/api/admin/auth/recovery-codes/verify",
    ]);
  });

  it("logout 403 mutation을 자동 재시도하지 않고 다음 사용자 시도에서 CSRF를 갱신한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "stale" }),
      )
      .mockResolvedValueOnce(emptyResponse(403))
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "fresh" }),
      )
      .mockResolvedValueOnce(emptyResponse(204));
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(client.logout()).rejects.toMatchObject({ kind: "forbidden" });
    expect(fetcher).toHaveBeenCalledTimes(2);

    await expect(client.logout()).resolves.toBe("logged-out");
    expect(fetcher).toHaveBeenCalledTimes(4);
    const retryHeaders = new Headers(fetcher.mock.calls[3]?.[1]?.headers);
    expect(retryHeaders.get("X-CSRF-TOKEN")).toBe("fresh");
  });

  it("authenticated API 401에서 in-memory session을 비우고 만료 callback을 호출한다", async () => {
    const onSessionExpired = vi.fn();
    const client = new DefaultAdminAuthClient({
      fetcher: vi.fn().mockResolvedValue(emptyResponse(401)),
      onSessionExpired,
    });

    await expect(
      client.requestAuthenticatedJson(
        "/api/admin/notices",
        acceptsRecord,
      ),
    ).rejects.toEqual(new AdminApiError("session-expired"));
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("authenticated JSON GET의 malformed shape를 unavailable로 거부한다", async () => {
    const client = new DefaultAdminAuthClient({
      fetcher: vi.fn().mockResolvedValue(jsonResponse({ unexpected: true })),
    });

    await expect(
      client.requestAuthenticatedJson(
        "/api/admin/media",
        (value): value is readonly unknown[] => Array.isArray(value),
      ),
    ).rejects.toEqual(new AdminApiError("unavailable"));
  });

  it("JSON mutation에 in-memory CSRF와 JSON body를 적용한다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
      )
      .mockResolvedValueOnce(jsonResponse({ status: "archived" }));
    const client = new DefaultAdminAuthClient({ fetcher });

    await client.prepareSessionCsrf();
    await expect(
      client.requestJsonMutation(
        "/api/admin/media/b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
        "PUT",
        { status: "archived" },
        (value): value is { status: string } =>
          typeof value === "object" && value !== null && "status" in value,
      ),
    ).resolves.toEqual({ status: "archived" });

    const init = fetcher.mock.calls[1]?.[1];
    const headers = new Headers(init?.headers);
    expect(headers.get("X-CSRF-TOKEN")).toBe("session-csrf");
    expect(headers.get("Content-Type")).toBe("application/json");
    expect(JSON.parse(String(init?.body))).toEqual({ status: "archived" });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("multipart mutation에서 browser FormData boundary를 덮어쓰지 않는다", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
      )
      .mockResolvedValueOnce(jsonResponse({ id: "created" }, 201));
    const client = new DefaultAdminAuthClient({ fetcher });
    const formData = new FormData();
    formData.append("file", new File(["image"], "sample.png", { type: "image/png" }));

    await expect(
      client.requestMultipartMutation(
        "/api/admin/media",
        "POST",
        formData,
        (value): value is { id: string } =>
          typeof value === "object" && value !== null && "id" in value,
      ),
    ).resolves.toEqual({ id: "created" });

    const init = fetcher.mock.calls[1]?.[1];
    const headers = new Headers(init?.headers);
    expect(init?.body).toBe(formData);
    expect(headers.has("Content-Type")).toBe(false);
    expect(headers.get("X-CSRF-TOKEN")).toBe("session-csrf");
  });

  it("mutation 403을 재시도하지 않고 다음 사용자 action에서 fresh CSRF를 사용한다", async () => {
    const validator = (value: unknown): value is { status: string } =>
      typeof value === "object" && value !== null && "status" in value;
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "stale" }),
      )
      .mockResolvedValueOnce(jsonResponse({ code: "FORBIDDEN" }, 403))
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "fresh" }),
      )
      .mockResolvedValueOnce(jsonResponse({ status: "archived" }));
    const client = new DefaultAdminAuthClient({ fetcher });

    await client.prepareSessionCsrf();
    await expect(
      client.requestJsonMutation(
        "/api/admin/media/b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
        "PUT",
        { status: "archived" },
        validator,
      ),
    ).rejects.toEqual(new AdminApiError("forbidden"));
    expect(fetcher).toHaveBeenCalledTimes(2);

    await expect(
      client.requestJsonMutation(
        "/api/admin/media/b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
        "PUT",
        { status: "archived" },
        validator,
      ),
    ).resolves.toEqual({ status: "archived" });
    expect(fetcher).toHaveBeenCalledTimes(4);
    const retryHeaders = new Headers(fetcher.mock.calls[3]?.[1]?.headers);
    expect(retryHeaders.get("X-CSRF-TOKEN")).toBe("fresh");
  });

  it("mutation 401에서 session을 비우고 callback을 호출하며 요청을 재전송하지 않는다", async () => {
    const onSessionExpired = vi.fn();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
      )
      .mockResolvedValueOnce(emptyResponse(401));
    const client = new DefaultAdminAuthClient({ fetcher, onSessionExpired });

    await expect(
      client.requestJsonMutation(
        "/api/admin/media/b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
        "PUT",
        { status: "archived" },
        acceptsRecord,
      ),
    ).rejects.toEqual(new AdminApiError("session-expired"));

    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it.each([
    [400, "INVALID_REQUEST", "invalid-request"],
    [404, "MEDIA_NOT_FOUND", "not-found"],
    [413, "MEDIA_TOO_LARGE", "too-large"],
    [415, "MEDIA_TYPE_UNSUPPORTED", "type-unsupported"],
    [422, "MEDIA_INVALID_IMAGE", "invalid-image"],
    [503, "MEDIA_PROCESSOR_UNAVAILABLE", "processor-unavailable"],
  ] as const)(
    "media status %i와 allowlisted code %s를 %s로 매핑한다",
    async (status, code, kind) => {
      const fetcher = vi
        .fn()
        .mockResolvedValueOnce(
          jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
        )
        .mockResolvedValueOnce(
          jsonResponse({ code, message: "backend raw message" }, status),
        );
      const client = new DefaultAdminAuthClient({ fetcher });
      const body = new FormData();
      body.append("file", new File(["image"], "photo.heic"));

      await expect(
        client.requestMultipartMutation(
          "/api/admin/media",
          "POST",
          body,
          acceptsRecord,
        ),
      ).rejects.toEqual(new AdminApiError(kind));
      expect(fetcher).toHaveBeenCalledTimes(2);
    },
  );

  it.each([
    [404, "SHOP_SETTINGS_NOT_FOUND", "not-found"],
    [422, "BUSINESS_HOURS_INVALID", "business-hours-invalid"],
    [422, "SHOP_MEDIA_RELATION_INVALID", "shop-media-relation-invalid"],
  ] as const)(
    "shop status %i와 allowlisted code %s를 %s로 매핑한다",
    async (status, code, kind) => {
      const fetcher = vi
        .fn()
        .mockResolvedValueOnce(
          jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
        )
        .mockResolvedValueOnce(
          jsonResponse({ code, message: "backend raw message" }, status),
        );
      const client = new DefaultAdminAuthClient({ fetcher });

      await expect(
        client.requestJsonMutation(
          "/api/admin/shop-settings",
          "PUT",
          { shopName: "테스트" },
          acceptsRecord,
        ),
      ).rejects.toEqual(new AdminApiError(kind));
      expect(fetcher).toHaveBeenCalledTimes(2);
    },
  );

  it.each([
    [404, "CONTENT_NOT_FOUND", "content-not-found"],
    [409, "SLUG_CONFLICT", "slug-conflict"],
    [422, "PUBLISH_VALIDATION_FAILED", "publish-validation-failed"],
    [422, "NOTICE_WINDOW_INVALID", "notice-window-invalid"],
  ] as const)(
    "content status %i와 allowlisted code %s를 %s로 매핑한다",
    async (status, code, kind) => {
      const fetcher = vi
        .fn()
        .mockResolvedValueOnce(
          jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
        )
        .mockResolvedValueOnce(
          jsonResponse({ code, message: "backend raw message" }, status),
        );
      const client = new DefaultAdminAuthClient({ fetcher });

      await expect(
        client.requestJsonMutation(
          "/api/admin/breeds/d64047ee-93fe-4f87-949f-493d47ad6ee4",
          "PUT",
          { status: "published" },
          acceptsRecord,
        ),
      ).rejects.toEqual(new AdminApiError(kind));
      expect(fetcher).toHaveBeenCalledTimes(2);
    },
  );

  it.each([
    [404, "GALLERY_ITEM_NOT_FOUND", "gallery-item-not-found"],
    [422, "GALLERY_RELATION_INVALID", "gallery-relation-invalid"],
    [422, "GALLERY_PUBLISH_INVALID", "gallery-publish-invalid"],
  ] as const)(
    "gallery status %i와 allowlisted code %s를 %s로 매핑한다",
    async (status, code, kind) => {
      const fetcher = vi
        .fn()
        .mockResolvedValueOnce(
          jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
        )
        .mockResolvedValueOnce(
          jsonResponse({ code, message: "backend raw gallery detail" }, status),
        );
      const client = new DefaultAdminAuthClient({ fetcher });

      await expect(
        client.requestJsonMutation(
          "/api/admin/gallery-items/d64047ee-93fe-4f87-949f-493d47ad6ee4",
          "PUT",
          { status: "published" },
          acceptsRecord,
        ),
      ).rejects.toEqual(new AdminApiError(kind));
      expect(fetcher).toHaveBeenCalledTimes(2);
    },
  );

  it.each([200, 201])("JSON mutation의 %i success response를 검증한다", async (status) => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ headerName: "X-CSRF-TOKEN", token: "session-csrf" }),
      )
      .mockResolvedValueOnce(jsonResponse({ shopName: "라오미펫" }, status));
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(
      client.requestJsonMutation(
        "/api/admin/shop-settings",
        "PUT",
        { shopName: "라오미펫" },
        (value): value is { shopName: string } =>
          typeof value === "object" &&
          value !== null &&
          "shopName" in value &&
          typeof value.shopName === "string",
      ),
    ).resolves.toEqual({ shopName: "라오미펫" });
  });

  it("authenticated image GET을 same-origin no-store로 읽고 JPEG/PNG만 허용한다", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(new Uint8Array([1, 2, 3]), {
        headers: { "Content-Type": "image/jpeg" },
      }),
    );
    const client = new DefaultAdminAuthClient({ fetcher });

    const blob = await client.requestAuthenticatedBlob(
      "/api/admin/media/b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d/content",
      ["image/jpeg", "image/png"],
    );

    expect(blob.type).toBe("image/jpeg");
    expect(blob.size).toBe(3);
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
    });
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("Accept")).toBe(
      "image/jpeg, image/png",
    );
  });

  it("binary 401은 session 만료 callback을 호출하고 non-image response는 거부한다", async () => {
    const onSessionExpired = vi.fn();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(emptyResponse(401))
      .mockResolvedValueOnce(
        new Response("html", { headers: { "Content-Type": "text/html" } }),
      );
    const client = new DefaultAdminAuthClient({ fetcher, onSessionExpired });
    const path = "/api/admin/media/b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d/content";

    await expect(
      client.requestAuthenticatedBlob(path, ["image/jpeg", "image/png"]),
    ).rejects.toEqual(
      new AdminApiError("session-expired"),
    );
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    await expect(
      client.requestAuthenticatedBlob(path, ["image/jpeg", "image/png"]),
    ).rejects.toEqual(
      new AdminApiError("unavailable"),
    );
  });

  it("admin namespace 밖의 URL을 fetch하지 않는다", async () => {
    const fetcher = vi.fn();
    const client = new DefaultAdminAuthClient({ fetcher });

    await expect(
      client.requestAuthenticatedJson(
        "https://example.test/api/admin/notices",
        acceptsRecord,
      ),
    ).rejects.toMatchObject({ kind: "unavailable" });
    expect(fetcher).not.toHaveBeenCalled();
  });
});
