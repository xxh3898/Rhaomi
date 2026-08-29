import {
  ADMIN_ROLE,
  type AdminAuthClient,
  type AdminAuthErrorKind,
  type AdminIdentity,
  type CsrfToken,
  type LoginCredentials,
  type LogoutResult,
} from "./types";

const AUTH_PATH = "/api/admin/auth";
const ADMIN_PATH_PREFIX = "/api/admin/";
const HEADER_NAME_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type Fetcher = typeof fetch;
type JsonValidator<T> = (value: unknown) => value is T;

type AdminAuthClientOptions = Readonly<{
  fetcher?: Fetcher;
  onSessionExpired?: () => void;
}>;

export class AdminAuthError extends Error {
  readonly kind: AdminAuthErrorKind;

  constructor(kind: AdminAuthErrorKind) {
    super(kind);
    this.name = "AdminAuthError";
    this.kind = kind;
  }
}

export function isAdminAuthError(error: unknown): error is AdminAuthError {
  return error instanceof AdminAuthError;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isAdminIdentity(value: unknown): value is AdminIdentity {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    UUID_PATTERN.test(value.id) &&
    typeof value.email === "string" &&
    value.email.includes("@") &&
    value.role === ADMIN_ROLE
  );
}

function isCsrfToken(value: unknown): value is CsrfToken {
  return (
    isRecord(value) &&
    typeof value.headerName === "string" &&
    HEADER_NAME_PATTERN.test(value.headerName) &&
    typeof value.token === "string" &&
    value.token.length > 0
  );
}

function isSuccessStatus(status: number): boolean {
  return status >= 200 && status < 300;
}

async function decodeJson<T>(
  response: Response,
  validator: JsonValidator<T>,
): Promise<T> {
  try {
    const value: unknown = await response.json();
    if (validator(value)) {
      return value;
    }
  } catch {
    // UI에는 parser detail이나 원문 response를 전달하지 않는다.
  }

  throw new AdminAuthError("unavailable");
}

function assertAdminPath(path: string): void {
  if (!path.startsWith(ADMIN_PATH_PREFIX) || path.startsWith("//")) {
    throw new AdminAuthError("unavailable");
  }
}

function mapLoginFailure(status: number): AdminAuthError {
  if (status === 400) {
    return new AdminAuthError("invalid-request");
  }
  if (status === 401) {
    return new AdminAuthError("invalid-credentials");
  }
  if (status === 403) {
    return new AdminAuthError("forbidden");
  }
  if (status === 503) {
    return new AdminAuthError("service-unavailable");
  }
  return new AdminAuthError("unavailable");
}

export class DefaultAdminAuthClient implements AdminAuthClient {
  readonly #fetcher: Fetcher;
  readonly #onSessionExpired: () => void;
  #csrfToken: CsrfToken | null = null;

  constructor(options: AdminAuthClientOptions = {}) {
    this.#fetcher = options.fetcher ?? globalThis.fetch.bind(globalThis);
    this.#onSessionExpired = options.onSessionExpired ?? (() => undefined);
  }

  clearSession(): void {
    this.#csrfToken = null;
  }

  async getSession(): Promise<AdminIdentity | null> {
    const response = await this.#request(`${AUTH_PATH}/me`, { method: "GET" });

    if (response.status === 401) {
      this.clearSession();
      return null;
    }
    if (!isSuccessStatus(response.status)) {
      throw new AdminAuthError("unavailable");
    }

    return decodeJson(response, isAdminIdentity);
  }

  async login(credentials: LoginCredentials): Promise<AdminIdentity> {
    const csrfToken = await this.#fetchCsrf();
    const response = await this.#request(`${AUTH_PATH}/login`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        [csrfToken.headerName]: csrfToken.token,
      },
      body: JSON.stringify(credentials),
    });

    if (!isSuccessStatus(response.status)) {
      this.clearSession();
      throw mapLoginFailure(response.status);
    }

    // Spring Security session fixation 뒤 pre-login token을 재사용하지 않는다.
    this.#csrfToken = null;
    const admin = await decodeJson(response, isAdminIdentity);
    this.#csrfToken = await this.#fetchCsrf();
    return admin;
  }

  async logout(): Promise<LogoutResult> {
    const csrfToken = this.#csrfToken ?? (await this.#fetchCsrf());
    const response = await this.#request(`${AUTH_PATH}/logout`, {
      method: "POST",
      headers: { [csrfToken.headerName]: csrfToken.token },
    });

    if (response.status === 204) {
      this.clearSession();
      return "logged-out";
    }
    if (response.status === 401) {
      this.clearSession();
      this.#onSessionExpired();
      return "session-ended";
    }

    this.#csrfToken = null;
    if (response.status === 403) {
      throw new AdminAuthError("forbidden");
    }
    throw new AdminAuthError("unavailable");
  }

  async requestAuthenticatedJson<T>(
    path: string,
    validator: JsonValidator<T>,
  ): Promise<T> {
    const response = await this.#request(path, { method: "GET" });

    if (response.status === 401) {
      this.clearSession();
      this.#onSessionExpired();
      throw new AdminAuthError("session-expired");
    }
    if (response.status === 403) {
      throw new AdminAuthError("forbidden");
    }
    if (!isSuccessStatus(response.status)) {
      throw new AdminAuthError("unavailable");
    }

    return decodeJson(response, validator);
  }

  async #fetchCsrf(): Promise<CsrfToken> {
    const response = await this.#request(`${AUTH_PATH}/csrf`, { method: "GET" });
    if (!isSuccessStatus(response.status)) {
      throw new AdminAuthError(
        response.status === 503 ? "service-unavailable" : "unavailable",
      );
    }

    const csrfToken = await decodeJson(response, isCsrfToken);
    this.#csrfToken = csrfToken;
    return csrfToken;
  }

  async #request(path: string, init: RequestInit): Promise<Response> {
    assertAdminPath(path);
    const method = (init.method ?? "GET").toUpperCase();
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");

    try {
      return await this.#fetcher(path, {
        ...init,
        headers,
        credentials: "same-origin",
        ...(method === "GET" ? { cache: "no-store" } : {}),
      });
    } catch {
      throw new AdminAuthError("unavailable");
    }
  }
}

export function createAdminAuthClient(
  options: AdminAuthClientOptions = {},
): DefaultAdminAuthClient {
  return new DefaultAdminAuthClient(options);
}
