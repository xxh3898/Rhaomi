export const ADMIN_ROLE = "ADMIN" as const;

export type AdminRole = typeof ADMIN_ROLE;

export type AdminIdentity = Readonly<{
  id: string;
  email: string;
  role: AdminRole;
}>;

export type LoginCredentials = Readonly<{
  email: string;
  password: string;
}>;

export type CsrfToken = Readonly<{
  headerName: string;
  token: string;
}>;

export type LogoutResult = "logged-out" | "session-ended";

export type JsonValidator<T> = (value: unknown) => value is T;

export type AdminMutationMethod = "POST" | "PUT";

export interface AdminApiTransport {
  requestAuthenticatedJson<T>(
    path: string,
    validator: JsonValidator<T>,
  ): Promise<T>;
  requestJsonMutation<T>(
    path: string,
    method: AdminMutationMethod,
    body: unknown,
    validator: JsonValidator<T>,
  ): Promise<T>;
  requestMultipartMutation<T>(
    path: string,
    method: "POST",
    body: FormData,
    validator: JsonValidator<T>,
  ): Promise<T>;
  requestAuthenticatedBlob(
    path: string,
    allowedContentTypes: readonly string[],
  ): Promise<Blob>;
}

export type AdminAuthErrorKind =
  | "invalid-credentials"
  | "invalid-request"
  | "service-unavailable"
  | "forbidden"
  | "session-expired"
  | "unavailable";

export interface AdminAuthClient extends AdminApiTransport {
  getSession(): Promise<AdminIdentity | null>;
  login(credentials: LoginCredentials): Promise<AdminIdentity>;
  prepareSessionCsrf(): Promise<void>;
  logout(): Promise<LogoutResult>;
  clearSession(): void;
}
