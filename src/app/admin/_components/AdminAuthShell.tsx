"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";

import {
  createAdminAuthClient,
  isAdminApiError,
  isAdminAuthError,
} from "@/features/admin-auth/api";
import type {
  AdminAuthClient,
  AdminIdentity,
  AdminWebAuthnStatus,
} from "@/features/admin-auth/types";
import { AdminDashboard } from "@/features/admin-dashboard/AdminDashboard";

import styles from "../admin.module.css";

const MAX_PASSWORD_BYTES = 72;

type AuthState =
  | Readonly<{ kind: "checking" }>
  | Readonly<{
      kind: "preparing";
      admin: AdminIdentity;
      requestSequence: number;
    }>
  | Readonly<{ kind: "anonymous"; message?: string }>
  | Readonly<{ kind: "submitting"; operation: "login" }>
  | Readonly<{
      kind: "submitting";
      operation: "logout";
      admin: AdminIdentity;
      message?: string;
    }>
  | Readonly<{ kind: "authenticated"; admin: AdminIdentity; message?: string }>
  | Readonly<{
      kind: "second-factor";
      admin: AdminIdentity;
      status: AdminWebAuthnStatus;
      operation?: "passkey" | "recovery" | "logout";
      message?: string;
    }>
  | Readonly<{
      kind: "recovery-rotation";
      admin: AdminIdentity;
      operation?: "rotation" | "logout";
      message?: string;
    }>
  | Readonly<{
      kind: "recovery-codes";
      admin: AdminIdentity;
      recoveryCodes: readonly string[];
      operation?: "continue" | "logout";
      message?: string;
    }>
  | Readonly<{ kind: "unavailable" }>;

type AdminAuthShellProps = Readonly<{
  client?: AdminAuthClient;
}>;

function loginErrorMessage(error: unknown): string {
  if (isAdminAuthError(error)) {
    if (error.kind === "invalid-credentials") {
      return "이메일 또는 비밀번호를 확인해 주세요.";
    }
    if (error.kind === "invalid-request") {
      return "입력 형식을 확인해 주세요.";
    }
    if (error.kind === "service-unavailable") {
      return "인증 서비스를 일시적으로 사용할 수 없습니다.";
    }
    if (error.kind === "forbidden") {
      return "로그인 요청을 확인할 수 없습니다. 다시 시도해 주세요.";
    }
  }

  return "로그인 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
}

function logoutErrorMessage(error: unknown): string {
  if (isAdminAuthError(error) && error.kind === "forbidden") {
    return "로그아웃 요청을 확인할 수 없습니다. 다시 시도해 주세요.";
  }

  return "로그아웃하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.";
}

function passkeyErrorMessage(error: unknown): string {
  if (isAdminAuthError(error)) {
    if (error.kind === "webauthn-unsupported") {
      return "이 브라우저 또는 보안 환경에서는 Passkey를 사용할 수 없습니다.";
    }
    if (error.kind === "webauthn-failed") {
      return "Passkey 확인을 완료하지 못했습니다. 다시 시도해 주세요.";
    }
    if (error.kind === "forbidden") {
      return "Passkey 요청 권한 또는 보안 token을 확인해 주세요.";
    }
    if (error.kind === "invalid-request") {
      return "Passkey 이름 또는 요청 형식을 확인해 주세요.";
    }
  }
  return "Passkey 서비스를 일시적으로 사용할 수 없습니다.";
}

function passwordByteLength(password: string): number {
  return new TextEncoder().encode(password).byteLength;
}

export function AdminAuthShell({ client }: AdminAuthShellProps) {
  const [state, setState] = useState<AuthState>({ kind: "checking" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passkeyLabel, setPasskeyLabel] = useState("내 Passkey");
  const [recoveryCode, setRecoveryCode] = useState("");
  const passwordInputRef = useRef<HTMLInputElement>(null);
  const passkeyLabelInputRef = useRef<HTMLInputElement>(null);
  const passkeyActionRef = useRef<HTMLButtonElement>(null);
  const recoveryRotationActionRef = useRef<HTMLButtonElement>(null);
  const recoveryCodesContinueRef = useRef<HTMLButtonElement>(null);
  const busyRef = useRef(false);
  const requestSequenceRef = useRef(0);
  const handleSessionExpired = useCallback(() => {
    setEmail("");
    setPassword("");
    setRecoveryCode("");
    setState({
      kind: "anonymous",
      message: "관리자 세션이 만료됐습니다. 다시 로그인해 주세요.",
    });
  }, []);
  const [authClient] = useState<AdminAuthClient>(() =>
    client ??
    createAdminAuthClient({
      onSessionExpired: handleSessionExpired,
    }),
  );

  const resolveSession = useCallback(
    async (requestSequence: number) => {
      try {
        const admin = await authClient.getSession();
        if (requestSequence !== requestSequenceRef.current) {
          return;
        }
        if (admin) {
          setEmail("");
          setPassword("");
          setState({ kind: "preparing", admin, requestSequence });
          return;
        }
        setState({ kind: "anonymous" });
      } catch {
        if (requestSequence === requestSequenceRef.current) {
          setState({ kind: "unavailable" });
        }
      }
    },
    [authClient],
  );

  const checkSession = useCallback(() => {
    const requestSequence = ++requestSequenceRef.current;
    setState({ kind: "checking" });
    void resolveSession(requestSequence);
  }, [resolveSession]);

  useEffect(() => {
    const requestSequence = ++requestSequenceRef.current;
    void resolveSession(requestSequence);
    return () => {
      requestSequenceRef.current += 1;
      authClient.clearSession();
    };
  }, [authClient, resolveSession]);

  useEffect(() => {
    if (state.kind !== "preparing") {
      return;
    }

    const { admin, requestSequence } = state;
    let active = true;

    void (async () => {
      try {
        await authClient.prepareSessionCsrf();
        const webAuthnStatus = await authClient.getWebAuthnStatus();
        if (active && requestSequence === requestSequenceRef.current) {
          if (
            !webAuthnStatus.required ||
            webAuthnStatus.authenticationStage === "SECOND_FACTOR_VERIFIED"
          ) {
            setState({ kind: "authenticated", admin });
          } else if (
            webAuthnStatus.authenticationStage ===
            "RECOVERY_ROTATION_REQUIRED"
          ) {
            setState({ kind: "recovery-rotation", admin });
          } else {
            setState({
              kind: "second-factor",
              admin,
              status: webAuthnStatus,
            });
          }
        }
      } catch (error) {
        if (active && requestSequence === requestSequenceRef.current) {
          if (isAdminApiError(error) && error.kind === "session-expired") {
            handleSessionExpired();
          } else {
            setState({ kind: "unavailable" });
          }
        }
      }
    })();

    return () => {
      active = false;
    };
  }, [authClient, handleSessionExpired, state]);

  useEffect(() => {
    if (state.kind === "anonymous" && state.message) {
      passwordInputRef.current?.focus();
    }
  }, [state]);

  useEffect(() => {
    if (state.kind === "second-factor" && !state.operation) {
      if (state.status.initialEnrollmentRequired) {
        passkeyLabelInputRef.current?.focus();
      } else {
        passkeyActionRef.current?.focus();
      }
    } else if (state.kind === "recovery-rotation" && !state.operation) {
      recoveryRotationActionRef.current?.focus();
    } else if (state.kind === "recovery-codes" && !state.operation) {
      recoveryCodesContinueRef.current?.focus();
    }
  }, [state]);

  async function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busyRef.current || state.kind !== "anonymous") {
      return;
    }

    if (passwordByteLength(password) > MAX_PASSWORD_BYTES) {
      setPassword("");
      setState({
        kind: "anonymous",
        message: "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.",
      });
      return;
    }

    busyRef.current = true;
    setState({ kind: "submitting", operation: "login" });

    try {
      const admin = await authClient.login({ email, password });
      const requestSequence = ++requestSequenceRef.current;
      setEmail("");
      setPassword("");
      setState({ kind: "preparing", admin, requestSequence });
    } catch (error) {
      setState({ kind: "anonymous", message: loginErrorMessage(error) });
    } finally {
      setPassword("");
      busyRef.current = false;
    }
  }

  async function handleLogout(admin: AdminIdentity) {
    if (busyRef.current) {
      return;
    }

    busyRef.current = true;
    setState({ kind: "submitting", operation: "logout", admin });

    try {
      await authClient.logout();
      setEmail("");
      setPassword("");
      setState({ kind: "anonymous" });
    } catch (error) {
      setState({
        kind: "authenticated",
        admin,
        message: logoutErrorMessage(error),
      });
    } finally {
      busyRef.current = false;
    }
  }

  async function handlePasskey(
    stateValue: Extract<AuthState, { kind: "second-factor" }>,
  ) {
    if (busyRef.current) {
      return;
    }
    busyRef.current = true;
    setState({ ...stateValue, operation: "passkey", message: undefined });
    if (stateValue.status.initialEnrollmentRequired) {
      try {
        await authClient.registerPasskey(passkeyLabel);
        setPasskeyLabel("");
      } catch (error) {
        setState({
          ...stateValue,
          operation: undefined,
          message: passkeyErrorMessage(error),
        });
        busyRef.current = false;
        return;
      }

      setState({
        kind: "recovery-rotation",
        admin: stateValue.admin,
        operation: "rotation",
      });
      try {
        const generated = await authClient.rotateRecoveryCodes();
        setState({
          kind: "recovery-codes",
          admin: stateValue.admin,
          recoveryCodes: generated.recoveryCodes,
        });
      } catch (error) {
        setState({
          kind: "recovery-rotation",
          admin: stateValue.admin,
          message: passkeyErrorMessage(error),
        });
      } finally {
        busyRef.current = false;
      }
      return;
    }

    try {
      await authClient.authenticatePasskey();
      const requestSequence = ++requestSequenceRef.current;
      setState({
        kind: "preparing",
        admin: stateValue.admin,
        requestSequence,
      });
    } catch (error) {
      setState({
        ...stateValue,
        operation: undefined,
        message: passkeyErrorMessage(error),
      });
    } finally {
      busyRef.current = false;
    }
  }

  async function handleRecovery(
    stateValue: Extract<AuthState, { kind: "second-factor" }>,
  ) {
    if (busyRef.current || recoveryCode.length === 0) {
      return;
    }
    busyRef.current = true;
    setState({ ...stateValue, operation: "recovery", message: undefined });
    try {
      await authClient.verifyRecoveryCode(recoveryCode);
      setRecoveryCode("");
    } catch (error) {
      setRecoveryCode("");
      setState({
        ...stateValue,
        operation: undefined,
        message: passkeyErrorMessage(error),
      });
      busyRef.current = false;
      return;
    }

    setState({
      kind: "recovery-rotation",
      admin: stateValue.admin,
      operation: "rotation",
    });
    try {
      const generated = await authClient.rotateRecoveryCodes();
      setState({
        kind: "recovery-codes",
        admin: stateValue.admin,
        recoveryCodes: generated.recoveryCodes,
      });
    } catch (error) {
      setState({
        kind: "recovery-rotation",
        admin: stateValue.admin,
        message: passkeyErrorMessage(error),
      });
    } finally {
      busyRef.current = false;
    }
  }

  async function handleRecoveryRotation(
    stateValue: Extract<AuthState, { kind: "recovery-rotation" }>,
  ) {
    if (busyRef.current) {
      return;
    }
    busyRef.current = true;
    setState({ ...stateValue, operation: "rotation", message: undefined });
    try {
      const generated = await authClient.rotateRecoveryCodes();
      setState({
        kind: "recovery-codes",
        admin: stateValue.admin,
        recoveryCodes: generated.recoveryCodes,
      });
    } catch (error) {
      setState({
        ...stateValue,
        operation: undefined,
        message: passkeyErrorMessage(error),
      });
    } finally {
      busyRef.current = false;
    }
  }

  function handleRecoveryCodesContinue(
    stateValue: Extract<AuthState, { kind: "recovery-codes" }>,
  ) {
    if (busyRef.current) {
      return;
    }
    busyRef.current = true;
    const requestSequence = ++requestSequenceRef.current;
    setState({
      kind: "preparing",
      admin: stateValue.admin,
      requestSequence,
    });
    busyRef.current = false;
  }

  async function handleMfaLogout(
    stateValue: Extract<
      AuthState,
      { kind: "second-factor" | "recovery-rotation" | "recovery-codes" }
    >,
  ) {
    if (busyRef.current) {
      return;
    }
    busyRef.current = true;
    setRecoveryCode("");
    setState({ ...stateValue, operation: "logout", message: undefined });
    try {
      await authClient.logout();
      setState({ kind: "anonymous" });
    } catch (error) {
      setState({
        ...stateValue,
        operation: undefined,
        message: logoutErrorMessage(error),
      });
    } finally {
      busyRef.current = false;
    }
  }

  const loginPending = state.kind === "submitting" && state.operation === "login";
  const authenticatedState =
    state.kind === "authenticated" ||
    (state.kind === "submitting" && state.operation === "logout")
      ? state
      : null;

  return (
    <main className={styles.main}>
      <section
        className={`${styles.panel} ${authenticatedState ? styles.authenticatedPanel : ""}`}
        aria-labelledby="admin-title"
      >
        <header className={styles.header}>
          <p className={styles.eyebrow}>Rhaomi Pet · Secure Admin</p>
          <h1 id="admin-title" className={styles.title}>
            라오미펫 관리자
          </h1>
          <p className={styles.description}>
            승인된 관리자 계정으로 로그인해 주세요.
          </p>
        </header>

        {state.kind === "checking" ? (
          <div className={styles.statePanel} role="status" aria-live="polite">
            <span className={styles.spinner} aria-hidden="true" />
            <p>관리자 세션을 확인하고 있습니다.</p>
          </div>
        ) : null}

        {state.kind === "preparing" ? (
          <div className={styles.statePanel} role="status" aria-live="polite">
            <span className={styles.spinner} aria-hidden="true" />
            <p>로그인 보안을 준비하고 있습니다.</p>
          </div>
        ) : null}

        {state.kind === "unavailable" ? (
          <div className={styles.statePanel}>
            <p role="alert">
              관리자 서비스를 일시적으로 사용할 수 없습니다. 연결을 확인해 주세요.
            </p>
            <button className={styles.primaryButton} type="button" onClick={checkSession}>
              다시 시도
            </button>
          </div>
        ) : null}

        {state.kind === "second-factor" ? (
          <div className={styles.form} aria-busy={Boolean(state.operation)}>
            <div className={styles.statePanel}>
              <h2>2차 인증</h2>
              <p>
                {state.status.initialEnrollmentRequired
                  ? "첫 Passkey를 등록해야 관리자 기능을 사용할 수 있습니다."
                  : "Passkey로 본인 확인을 완료해 주세요."}
              </p>
            </div>
            {state.message ? (
              <p className={styles.alert} role="alert">
                {state.message}
              </p>
            ) : null}
            {state.status.initialEnrollmentRequired ? (
              <div className={styles.field}>
                <label htmlFor="passkey-label">Passkey 이름</label>
                <input
                  ref={passkeyLabelInputRef}
                  id="passkey-label"
                  value={passkeyLabel}
                  maxLength={100}
                  disabled={Boolean(state.operation)}
                  autoComplete="off"
                  onChange={(event) => setPasskeyLabel(event.target.value)}
                />
              </div>
            ) : null}
            <button
              ref={passkeyActionRef}
              className={styles.primaryButton}
              type="button"
              disabled={Boolean(state.operation)}
              onClick={() => void handlePasskey(state)}
            >
              {state.operation === "passkey"
                ? "Passkey 확인 중"
                : state.status.initialEnrollmentRequired
                  ? "Passkey 등록"
                  : "Passkey로 계속"}
            </button>
            {state.status.recoveryCodesAvailable ? (
              <div className={styles.field}>
                <label htmlFor="recovery-code">복구 코드</label>
                <input
                  id="recovery-code"
                  type="password"
                  autoComplete="one-time-code"
                  value={recoveryCode}
                  disabled={Boolean(state.operation)}
                  onChange={(event) => setRecoveryCode(event.target.value)}
                />
                <button
                  className={styles.secondaryButton}
                  type="button"
                  disabled={Boolean(state.operation) || recoveryCode.length === 0}
                  onClick={() => void handleRecovery(state)}
                >
                  {state.operation === "recovery" ? "복구 중" : "복구 코드 사용"}
                </button>
              </div>
            ) : null}
            <button
              className={styles.secondaryButton}
              type="button"
              disabled={Boolean(state.operation)}
              onClick={() => void handleMfaLogout(state)}
            >
              로그아웃
            </button>
          </div>
        ) : null}

        {state.kind === "recovery-rotation" ? (
          <div className={styles.form} aria-busy={Boolean(state.operation)}>
            <h2>복구 코드 교체 필요</h2>
            <p>
              사용한 복구 코드 set은 모두 폐기됐습니다. 새 복구 코드를 발급해야
              관리자 기능을 사용할 수 있습니다.
            </p>
            {state.message ? (
              <p className={styles.alert} role="alert">
                {state.message}
              </p>
            ) : null}
            <button
              ref={recoveryRotationActionRef}
              className={styles.primaryButton}
              type="button"
              disabled={Boolean(state.operation)}
              onClick={() => void handleRecoveryRotation(state)}
            >
              {state.operation === "rotation" ? "발급 중" : "새 복구 코드 발급"}
            </button>
            <button
              className={styles.secondaryButton}
              type="button"
              disabled={Boolean(state.operation)}
              onClick={() => void handleMfaLogout(state)}
            >
              로그아웃
            </button>
          </div>
        ) : null}

        {state.kind === "recovery-codes" ? (
          <div className={styles.form} aria-busy={Boolean(state.operation)}>
            <h2>복구 코드를 안전하게 보관해 주세요</h2>
            <p>
              아래 코드는 이번 응답에서만 표시됩니다. 각 코드는 한 번만 사용할 수
              있습니다.
            </p>
            {state.message ? (
              <p className={styles.alert} role="alert">
                {state.message}
              </p>
            ) : null}
            <ol className={styles.recoveryCodes} aria-label="복구 코드">
              {state.recoveryCodes.map((code) => (
                <li key={code}>
                  <code>{code}</code>
                </li>
              ))}
            </ol>
            <button
              ref={recoveryCodesContinueRef}
              className={styles.primaryButton}
              type="button"
              disabled={Boolean(state.operation)}
              onClick={() => handleRecoveryCodesContinue(state)}
            >
              {state.operation === "continue" ? "보안 준비 중" : "보관 완료 후 계속"}
            </button>
            <button
              className={styles.secondaryButton}
              type="button"
              disabled={Boolean(state.operation)}
              onClick={() => void handleMfaLogout(state)}
            >
              로그아웃
            </button>
          </div>
        ) : null}

        {state.kind === "anonymous" || loginPending ? (
          <form className={styles.form} onSubmit={handleLogin} aria-busy={loginPending}>
            {state.kind === "anonymous" && state.message ? (
              <p className={styles.alert} role="alert">
                {state.message}
              </p>
            ) : null}

            <div className={styles.field}>
              <label htmlFor="admin-email">이메일</label>
              <input
                id="admin-email"
                name="email"
                type="email"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                inputMode="email"
                required
                disabled={loginPending}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </div>

            <div className={styles.field}>
              <label htmlFor="admin-password">비밀번호</label>
              <input
                ref={passwordInputRef}
                id="admin-password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                disabled={loginPending}
                aria-describedby="password-help"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
              <p id="password-help" className={styles.helpText}>
                UTF-8 기준 최대 72바이트
              </p>
            </div>

            <button className={styles.primaryButton} type="submit" disabled={loginPending}>
              {loginPending ? "로그인 중" : "로그인"}
            </button>
          </form>
        ) : null}

        {authenticatedState ? (
          <div className={styles.dashboard}>
            {authenticatedState.message ? (
              <p className={styles.alert} role="alert">
                {authenticatedState.message}
              </p>
            ) : null}

            <div className={styles.identity}>
              <span>현재 관리자</span>
              <strong>{authenticatedState.admin.email}</strong>
              <span>역할: {authenticatedState.admin.role}</span>
            </div>

            <AdminDashboard
              transport={authClient}
              onSessionExpired={handleSessionExpired}
            />

            <button
              className={styles.secondaryButton}
              type="button"
              disabled={authenticatedState.kind === "submitting"}
              onClick={() => void handleLogout(authenticatedState.admin)}
            >
              {authenticatedState.kind === "submitting" ? "로그아웃 중" : "로그아웃"}
            </button>
          </div>
        ) : null}
      </section>
    </main>
  );
}
