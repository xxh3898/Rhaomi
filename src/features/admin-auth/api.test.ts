import { describe, expect, it, vi } from "vitest";

import {
  AdminAuthError,
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

  it("login 전후 CSRF를 획득하고 동적 headerName과 fresh token을 사용한다", async () => {
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

  it.each([
    [400, "invalid-request"],
    [401, "invalid-credentials"],
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

  it("post-login CSRF 재획득 실패 시 pre-login token을 메모리에 남기지 않는다", async () => {
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
    ).rejects.toMatchObject({ kind: "unavailable" });
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
    ).rejects.toEqual(new AdminAuthError("session-expired"));
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
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
