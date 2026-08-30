import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { AdminApiError, AdminAuthError } from "@/features/admin-auth/api";
import type { AdminAuthClient } from "@/features/admin-auth/types";

import { AdminAuthShell } from "./AdminAuthShell";

const ADMIN = {
  id: "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d",
  email: "admin@example.test",
  role: "ADMIN",
} as const;

function createClient(
  overrides: Partial<AdminAuthClient> = {},
): AdminAuthClient {
  return {
    getSession: vi.fn().mockResolvedValue(null),
    login: vi.fn().mockResolvedValue(ADMIN),
    prepareSessionCsrf: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue("logged-out"),
    clearSession: vi.fn(),
    requestAuthenticatedJson: vi.fn().mockResolvedValue([]),
    requestJsonMutation: vi.fn(),
    requestMultipartMutation: vi.fn(),
    requestAuthenticatedBlob: vi.fn(),
    ...overrides,
  };
}

describe("AdminAuthShell", () => {
  it("초기 session 확인 상태를 접근 가능한 텍스트로 표시한 뒤 로그인 form을 연다", async () => {
    let resolveSession: ((value: null) => void) | undefined;
    const client = createClient({
      getSession: vi.fn().mockReturnValue(
        new Promise<null>((resolve) => {
          resolveSession = resolve;
        }),
      ),
    });

    render(<AdminAuthShell client={client} />);

    expect(screen.getByRole("status")).toHaveTextContent(
      "관리자 세션을 확인하고 있습니다.",
    );
    resolveSession?.(null);
    expect(await screen.findByRole("button", { name: "로그인" })).toBeEnabled();
  });

  it("visible label, autocomplete와 Enter submit을 제공하고 중복 제출을 막는다", async () => {
    const user = userEvent.setup();
    let resolveLogin: ((value: typeof ADMIN) => void) | undefined;
    const login = vi.fn().mockReturnValue(
      new Promise<typeof ADMIN>((resolve) => {
        resolveLogin = resolve;
      }),
    );
    const client = createClient({ login });

    render(<AdminAuthShell client={client} />);

    const email = await screen.findByLabelText("이메일");
    const password = screen.getByLabelText("비밀번호");
    expect(email).toHaveAttribute("type", "email");
    expect(email).toHaveAttribute("autocomplete", "username");
    expect(password).toHaveAttribute("autocomplete", "current-password");

    await user.type(email, ADMIN.email);
    await user.type(password, "test-password{Enter}");
    fireEvent.submit(password.closest("form")!);

    expect(login).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "로그인 중" })).toBeDisabled();

    resolveLogin?.(ADMIN);
    expect(await screen.findByText(ADMIN.email)).toBeInTheDocument();
    expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "로그아웃" }));
    expect(await screen.findByLabelText("이메일")).toHaveValue("");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("");
  });

  it("login POST 성공 직후 password를 제거한 다음 session용 fresh CSRF를 준비한다", async () => {
    const user = userEvent.setup();
    let resolveFreshCsrf: (() => void) | undefined;
    const prepareSessionCsrf = vi.fn().mockImplementation(() => {
      expect(screen.queryByLabelText("이메일")).not.toBeInTheDocument();
      expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();
      return new Promise<void>((resolve) => {
        resolveFreshCsrf = resolve;
      });
    });
    const client = createClient({ prepareSessionCsrf });

    render(<AdminAuthShell client={client} />);

    await user.type(await screen.findByLabelText("이메일"), ADMIN.email);
    await user.type(screen.getByLabelText("비밀번호"), "test-password");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => {
      expect(prepareSessionCsrf).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByLabelText("이메일")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "로그인 보안을 준비하고 있습니다.",
    );
    expect(screen.queryByText(ADMIN.email)).not.toBeInTheDocument();

    resolveFreshCsrf?.();
    expect(await screen.findByText(ADMIN.email)).toBeInTheDocument();
  });

  it("post-login CSRF 실패를 anonymous로 위장하지 않고 기존 session으로 복구한다", async () => {
    const user = userEvent.setup();
    const getSession = vi
      .fn()
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(ADMIN);
    const prepareSessionCsrf = vi
      .fn()
      .mockRejectedValueOnce(new AdminAuthError("unavailable"))
      .mockResolvedValueOnce(undefined);
    const login = vi.fn().mockResolvedValue(ADMIN);
    const client = createClient({ getSession, login, prepareSessionCsrf });

    render(<AdminAuthShell client={client} />);

    await user.type(await screen.findByLabelText("이메일"), ADMIN.email);
    await user.type(screen.getByLabelText("비밀번호"), "test-password");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(
      "관리자 서비스를 일시적으로 사용할 수 없습니다.",
    );
    expect(alert).not.toHaveTextContent("이메일 또는 비밀번호");
    expect(screen.queryByRole("button", { name: "로그인" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText(ADMIN.email)).toBeInTheDocument();
    expect(login).toHaveBeenCalledTimes(1);
    expect(getSession).toHaveBeenCalledTimes(2);
    expect(prepareSessionCsrf).toHaveBeenCalledTimes(2);
  });

  it("기존 session도 fresh CSRF가 준비되기 전에는 authenticated로 표시하지 않는다", async () => {
    let resolveFreshCsrf: (() => void) | undefined;
    const prepareSessionCsrf = vi.fn().mockReturnValue(
      new Promise<void>((resolve) => {
        resolveFreshCsrf = resolve;
      }),
    );
    const client = createClient({
      getSession: vi.fn().mockResolvedValue(ADMIN),
      prepareSessionCsrf,
    });

    render(<AdminAuthShell client={client} />);

    expect(
      await screen.findByText("로그인 보안을 준비하고 있습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByText(ADMIN.email)).not.toBeInTheDocument();

    resolveFreshCsrf?.();
    expect(await screen.findByText(ADMIN.email)).toBeInTheDocument();
  });

  it("로그인 실패 뒤 password를 제거하고 focus하며 raw 오류를 노출하지 않는다", async () => {
    const user = userEvent.setup();
    const client = createClient({
      login: vi.fn().mockRejectedValue(new Error("SQL connection detail")),
    });

    render(<AdminAuthShell client={client} />);

    await user.type(await screen.findByLabelText("이메일"), ADMIN.email);
    const password = screen.getByLabelText("비밀번호");
    await user.type(password, "test-password");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("로그인 서비스를 일시적으로 사용할 수 없습니다.");
    expect(alert).not.toHaveTextContent("SQL connection detail");
    expect(password).toHaveValue("");
    expect(password).toHaveFocus();
  });

  it("UTF-8 72바이트 초과 password를 API 호출 전에 명시적으로 거부한다", async () => {
    const user = userEvent.setup();
    const login = vi.fn().mockResolvedValue(ADMIN);
    const client = createClient({ login });

    render(<AdminAuthShell client={client} />);

    await user.type(await screen.findByLabelText("이메일"), ADMIN.email);
    const password = screen.getByLabelText("비밀번호");
    await user.type(password, "가".repeat(25));
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(login).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "UTF-8 기준 72바이트 이하여야 합니다.",
    );
    expect(password).toHaveValue("");
    expect(password).toHaveFocus();
  });

  it.each([
    ["invalid-request", "입력 형식을 확인해 주세요."],
    ["invalid-credentials", "이메일 또는 비밀번호를 확인해 주세요."],
    ["forbidden", "로그인 요청을 확인할 수 없습니다."],
    ["service-unavailable", "인증 서비스를 일시적으로 사용할 수 없습니다."],
  ] as const)("login %s 실패를 고정 문구로 표시하고 password를 제거한다", async (kind, message) => {
    const user = userEvent.setup();
    const client = createClient({
      login: vi
        .fn()
        .mockRejectedValue(new AdminAuthError(kind)),
    });

    render(<AdminAuthShell client={client} />);
    await user.type(await screen.findByLabelText("이메일"), ADMIN.email);
    const password = screen.getByLabelText("비밀번호");
    await user.type(password, "wrong-password");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(password).toHaveValue("");
    expect(password).toHaveFocus();
  });

  it("session 장애 화면에서 명시적 재시도로 authenticated 상태를 복구한다", async () => {
    const user = userEvent.setup();
    const getSession = vi
      .fn()
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce(ADMIN);
    const client = createClient({ getSession });

    render(<AdminAuthShell client={client} />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "관리자 서비스를 일시적으로 사용할 수 없습니다.",
    );
    await user.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText(ADMIN.email)).toBeInTheDocument();
    expect(getSession).toHaveBeenCalledTimes(2);
  });

  it("dashboard에서 갤러리를 포함한 관리 영역을 열고 공지만 준비 중으로 유지한다", async () => {
    const user = userEvent.setup();
    const client = createClient({
      getSession: vi.fn().mockResolvedValue(ADMIN),
      requestAuthenticatedJson: vi.fn().mockImplementation((path: string) =>
        path === "/api/admin/shop-settings"
          ? Promise.reject(new AdminApiError("not-found"))
          : Promise.resolve([]),
      ),
    });

    render(<AdminAuthShell client={client} />);

    expect(await screen.findByText(ADMIN.email)).toBeInTheDocument();
    expect(screen.getByText("역할: ADMIN")).toBeInTheDocument();
    expect(screen.getAllByText("준비 중")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "공지, 준비 중" })).toBeDisabled();
    const shopButton = screen.getByRole("button", { name: "매장정보 관리 열기" });
    const mediaButton = screen.getByRole("button", { name: "미디어 관리 열기" });
    expect(shopButton).toBeEnabled();
    expect(mediaButton).toBeEnabled();
    expect(screen.getByRole("button", { name: "견종 관리 열기" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "서비스 관리 열기" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "갤러리 관리 열기" })).toBeEnabled();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();

    shopButton.focus();
    await user.keyboard("{Enter}");
    expect(
      await screen.findByRole("heading", { name: "매장정보 관리" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/아직 매장정보가 등록되지 않았습니다/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "관리 홈으로" }));

    const returnedMediaButton = screen.getByRole("button", {
      name: "미디어 관리 열기",
    });
    returnedMediaButton.focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("heading", { name: "미디어 관리" })).toBeInTheDocument();
    expect(screen.getByText(/업로드된 미디어가 없습니다/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "관리 홈으로" }));
    await user.click(screen.getByRole("button", { name: "갤러리 관리 열기" }));
    expect(await screen.findByRole("heading", { name: "갤러리 관리" })).toBeInTheDocument();
    expect(screen.getByText(/등록된 갤러리 항목이 없습니다/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "관리 홈으로" }));
    await user.click(screen.getByRole("button", { name: "견종 관리 열기" }));
    expect(await screen.findByRole("heading", { name: "견종 관리" })).toBeInTheDocument();
    expect(screen.getByText(/등록된 견종이 없습니다/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "관리 홈으로" }));
    await user.click(screen.getByRole("button", { name: "서비스 관리 열기" }));
    expect(await screen.findByRole("heading", { name: "서비스 관리" })).toBeInTheDocument();
    expect(screen.getByText(/등록된 서비스가 없습니다/)).toBeInTheDocument();
  });

  it("keyboard로 logout하고 anonymous form으로 전환한다", async () => {
    const user = userEvent.setup();
    const logout = vi.fn().mockResolvedValue("logged-out");
    const client = createClient({
      getSession: vi.fn().mockResolvedValue(ADMIN),
      logout,
    });

    render(<AdminAuthShell client={client} />);

    const logoutButton = await screen.findByRole("button", { name: "로그아웃" });
    logoutButton.focus();
    await user.keyboard("{Enter}");

    expect(logout).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole("button", { name: "로그인" })).toBeEnabled();
  });

  it("media request 401에서 dashboard를 비우고 로그인 form으로 복귀한다", async () => {
    const user = userEvent.setup();
    const client = createClient({
      getSession: vi.fn().mockResolvedValue(ADMIN),
      requestAuthenticatedJson: vi
        .fn()
        .mockRejectedValue(new AdminApiError("session-expired")),
    });

    render(<AdminAuthShell client={client} />);
    await user.click(await screen.findByRole("button", { name: "미디어 관리 열기" }));

    expect(await screen.findByRole("button", { name: "로그인" })).toBeEnabled();
    expect(screen.getByRole("alert")).toHaveTextContent("관리자 세션이 만료됐습니다.");
    expect(screen.queryByText(ADMIN.email)).not.toBeInTheDocument();
  });

  it("logout 403을 성공으로 위장하지 않고 dashboard에 고정 오류를 표시한다", async () => {
    const user = userEvent.setup();
    const logout = vi.fn().mockRejectedValue(new AdminAuthError("forbidden"));
    const client = createClient({
      getSession: vi.fn().mockResolvedValue(ADMIN),
      logout,
    });

    render(<AdminAuthShell client={client} />);
    await user.click(await screen.findByRole("button", { name: "로그아웃" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "로그아웃 요청을 확인할 수 없습니다.",
    );
    expect(screen.getByText(ADMIN.email)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "로그아웃" })).toBeEnabled();
    });
    expect(logout).toHaveBeenCalledTimes(1);
  });
});
