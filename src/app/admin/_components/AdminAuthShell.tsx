"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";

import {
  createAdminAuthClient,
  isAdminAuthError,
} from "@/features/admin-auth/api";
import type {
  AdminAuthClient,
  AdminIdentity,
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

function passwordByteLength(password: string): number {
  return new TextEncoder().encode(password).byteLength;
}

export function AdminAuthShell({ client }: AdminAuthShellProps) {
  const [state, setState] = useState<AuthState>({ kind: "checking" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const passwordInputRef = useRef<HTMLInputElement>(null);
  const busyRef = useRef(false);
  const requestSequenceRef = useRef(0);
  const handleSessionExpired = useCallback(() => {
    setEmail("");
    setPassword("");
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

    void authClient.prepareSessionCsrf().then(
      () => {
        if (active && requestSequence === requestSequenceRef.current) {
          setState({ kind: "authenticated", admin });
        }
      },
      () => {
        if (active && requestSequence === requestSequenceRef.current) {
          setState({ kind: "unavailable" });
        }
      },
    );

    return () => {
      active = false;
    };
  }, [authClient, state]);

  useEffect(() => {
    if (state.kind === "anonymous" && state.message) {
      passwordInputRef.current?.focus();
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
