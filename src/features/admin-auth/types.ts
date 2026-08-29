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

export type AdminAuthErrorKind =
  | "invalid-credentials"
  | "invalid-request"
  | "service-unavailable"
  | "forbidden"
  | "session-expired"
  | "unavailable";

export interface AdminAuthClient {
  getSession(): Promise<AdminIdentity | null>;
  login(credentials: LoginCredentials): Promise<AdminIdentity>;
  prepareSessionCsrf(): Promise<void>;
  logout(): Promise<LogoutResult>;
  clearSession(): void;
}
