import {
  ADMIN_ROLE,
  type AdminAuthClient,
  type AdminAuthErrorKind,
  type AdminIdentity,
  type AdminMutationMethod,
  type CsrfToken,
  type JsonValidator,
  type LoginCredentials,
  type LogoutResult,
} from "./types";

const AUTH_PATH = "/api/admin/auth";
const ADMIN_PATH_PREFIX = "/api/admin/";
const HEADER_NAME_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type Fetcher = typeof fetch;
type AdminAuthClientOptions = Readonly<{
  fetcher?: Fetcher;
  onSessionExpired?: () => void;
}>;

export type AdminApiErrorKind =
  | "invalid-request"
  | "not-found"
  | "business-hours-invalid"
  | "shop-media-relation-invalid"
  | "too-large"
  | "type-unsupported"
  | "invalid-image"
  | "processor-unavailable"
  | "forbidden"
  | "session-expired"
  | "unavailable";

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

export class AdminApiError extends Error {
  readonly kind: AdminApiErrorKind;

  constructor(kind: AdminApiErrorKind) {
    super(kind);
    this.name = "AdminApiError";
    this.kind = kind;
  }
}

export function isAdminApiError(error: unknown): error is AdminApiError {
  return error instanceof AdminApiError;
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
  invalidResponse: () => Error,
): Promise<T> {
  try {
    const value: unknown = await response.json();
    if (validator(value)) {
      return value;
    }
  } catch {
    // UI에는 parser detail이나 원문 response를 전달하지 않는다.
  }

  throw invalidResponse();
}

function assertAdminPath(path: string): void {
  if (
    !path.startsWith(ADMIN_PATH_PREFIX) ||
    !/^\/api\/admin\/[A-Za-z0-9/_-]*$/.test(path) ||
    path.includes("..")
  ) {
    throw new AdminApiError("unavailable");
  }
}

async function readApiErrorCode(response: Response): Promise<string | null> {
  try {
    const value: unknown = await response.json();
    if (isRecord(value) && typeof value.code === "string") {
      return value.code;
    }
  } catch {
    // status와 allowlist code 외 response detail은 사용하지 않는다.
  }
  return null;
}

async function mapApiFailure(response: Response): Promise<AdminApiError> {
  const code = await readApiErrorCode(response);

  if (response.status === 400 && code === "INVALID_REQUEST") {
    return new AdminApiError("invalid-request");
  }
  if (
    response.status === 404 &&
    (code === "MEDIA_NOT_FOUND" || code === "SHOP_SETTINGS_NOT_FOUND")
  ) {
    return new AdminApiError("not-found");
  }
  if (response.status === 413 && code === "MEDIA_TOO_LARGE") {
    return new AdminApiError("too-large");
  }
  if (response.status === 415 && code === "MEDIA_TYPE_UNSUPPORTED") {
    return new AdminApiError("type-unsupported");
  }
  if (response.status === 422 && code === "MEDIA_INVALID_IMAGE") {
    return new AdminApiError("invalid-image");
  }
  if (response.status === 422 && code === "BUSINESS_HOURS_INVALID") {
    return new AdminApiError("business-hours-invalid");
  }
  if (response.status === 422 && code === "SHOP_MEDIA_RELATION_INVALID") {
    return new AdminApiError("shop-media-relation-invalid");
  }
  if (response.status === 503 && code === "MEDIA_PROCESSOR_UNAVAILABLE") {
    return new AdminApiError("processor-unavailable");
  }
  return new AdminApiError("unavailable");
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

    return decodeJson(
      response,
      isAdminIdentity,
      () => new AdminAuthError("unavailable"),
    );
  }

  async login(credentials: LoginCredentials): Promise<AdminIdentity> {
    const csrfToken = await this.#fetchCsrf();
    let response: Response;

    try {
      response = await this.#request(`${AUTH_PATH}/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          [csrfToken.headerName]: csrfToken.token,
        },
        body: JSON.stringify(credentials),
      });
    } finally {
      // login POST가 성공·실패한 뒤에는 pre-login token을 보존하지 않는다.
      this.#csrfToken = null;
    }

    if (!isSuccessStatus(response.status)) {
      throw mapLoginFailure(response.status);
    }

    return decodeJson(
      response,
      isAdminIdentity,
      () => new AdminAuthError("unavailable"),
    );
  }

  async prepareSessionCsrf(): Promise<void> {
    // 기존 session 확인과 login 모두 mutation 전에 항상 fresh token을 준비한다.
    this.#csrfToken = null;
    await this.#fetchCsrf();
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
    await this.#assertAuthenticatedSuccess(response, false);
    return decodeJson(
      response,
      validator,
      () => new AdminApiError("unavailable"),
    );
  }

  async requestJsonMutation<T>(
    path: string,
    method: AdminMutationMethod,
    body: unknown,
    validator: JsonValidator<T>,
  ): Promise<T> {
    const csrfToken = this.#csrfToken ?? (await this.#fetchCsrf());
    const response = await this.#request(path, {
      method,
      headers: {
        "Content-Type": "application/json",
        [csrfToken.headerName]: csrfToken.token,
      },
      body: JSON.stringify(body),
    });
    await this.#assertAuthenticatedSuccess(response, true);
    return decodeJson(
      response,
      validator,
      () => new AdminApiError("unavailable"),
    );
  }

  async requestMultipartMutation<T>(
    path: string,
    method: "POST",
    body: FormData,
    validator: JsonValidator<T>,
  ): Promise<T> {
    const csrfToken = this.#csrfToken ?? (await this.#fetchCsrf());
    const response = await this.#request(path, {
      method,
      headers: { [csrfToken.headerName]: csrfToken.token },
      body,
    });
    await this.#assertAuthenticatedSuccess(response, true);
    return decodeJson(
      response,
      validator,
      () => new AdminApiError("unavailable"),
    );
  }

  async requestAuthenticatedBlob(
    path: string,
    allowedContentTypes: readonly string[],
  ): Promise<Blob> {
    const normalizedContentTypes = allowedContentTypes.map((value) =>
      value.toLowerCase(),
    );
    if (normalizedContentTypes.length === 0) {
      throw new AdminApiError("unavailable");
    }
    const response = await this.#request(path, {
      method: "GET",
      headers: { Accept: normalizedContentTypes.join(", ") },
    });
    await this.#assertAuthenticatedSuccess(response, false);

    const contentType = response.headers
      .get("Content-Type")
      ?.split(";", 1)[0]
      ?.trim()
      .toLowerCase();
    if (!contentType || !normalizedContentTypes.includes(contentType)) {
      throw new AdminApiError("unavailable");
    }

    const blob = await response.blob();
    if (blob.size === 0 || blob.type.toLowerCase() !== contentType) {
      throw new AdminApiError("unavailable");
    }
    return blob;
  }

  async #fetchCsrf(): Promise<CsrfToken> {
    const response = await this.#request(`${AUTH_PATH}/csrf`, { method: "GET" });
    if (!isSuccessStatus(response.status)) {
      throw new AdminAuthError(
        response.status === 503 ? "service-unavailable" : "unavailable",
      );
    }

    const csrfToken = await decodeJson(
      response,
      isCsrfToken,
      () => new AdminAuthError("unavailable"),
    );
    this.#csrfToken = csrfToken;
    return csrfToken;
  }

  async #assertAuthenticatedSuccess(
    response: Response,
    mutation: boolean,
  ): Promise<void> {
    if (response.status === 401) {
      this.clearSession();
      this.#onSessionExpired();
      throw new AdminApiError("session-expired");
    }
    if (response.status === 403) {
      if (mutation) {
        this.#csrfToken = null;
      }
      throw new AdminApiError("forbidden");
    }
    if (!isSuccessStatus(response.status)) {
      throw await mapApiFailure(response);
    }
  }

  async #request(path: string, init: RequestInit): Promise<Response> {
    assertAdminPath(path);
    const method = (init.method ?? "GET").toUpperCase();
    const headers = new Headers(init.headers);
    if (!headers.has("Accept")) {
      headers.set("Accept", "application/json");
    }

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
