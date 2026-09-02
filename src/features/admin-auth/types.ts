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

export type AdminAuthenticationStage =
  | "FIRST_FACTOR_VERIFIED"
  | "SECOND_FACTOR_VERIFIED"
  | "RECOVERY_ROTATION_REQUIRED";

export type AdminWebAuthnStatus = Readonly<{
  required: boolean;
  authenticationStage: AdminAuthenticationStage;
  activeCredentialCount: number;
  initialEnrollmentRequired: boolean;
  recoveryCodesAvailable: boolean;
}>;

export type RecoveryCodes = Readonly<{
  recoveryCodes: readonly string[];
}>;

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
  | "webauthn-failed"
  | "webauthn-unsupported"
  | "unavailable";

export interface AdminAuthClient extends AdminApiTransport {
  getSession(): Promise<AdminIdentity | null>;
  login(credentials: LoginCredentials): Promise<AdminIdentity>;
  prepareSessionCsrf(): Promise<void>;
  getWebAuthnStatus(): Promise<AdminWebAuthnStatus>;
  registerPasskey(label: string): Promise<AdminWebAuthnStatus>;
  authenticatePasskey(): Promise<AdminWebAuthnStatus>;
  verifyRecoveryCode(code: string): Promise<AdminWebAuthnStatus>;
  rotateRecoveryCodes(): Promise<RecoveryCodes>;
  logout(): Promise<LogoutResult>;
  clearSession(): void;
}
