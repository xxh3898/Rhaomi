"use client";

import type { FormEvent } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { isAdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";
import contentStyles from "@/features/admin-content/AdminContentManager.module.css";
import {
  applyContentMutationResult,
  CONTENT_STATUSES,
  type ContentStatus,
} from "@/features/admin-content/types";

import { AdminNoticeApi } from "./api";
import styles from "./AdminNoticeManager.module.css";
import {
  buildNoticeCreateRequest,
  buildNoticeUpdateRequest,
  EMPTY_NOTICE_DRAFT,
  noticeToDraft,
  validateNoticeDraft,
  type Notice,
  type NoticeDraft,
  type NoticeDraftValidationKind,
} from "./types";

type ListState = "loading" | "ready" | "error" | "refreshing";
type ListLoadMode = "initial" | "refresh" | "post-mutation";

type AdminNoticeManagerProps = Readonly<{
  transport: AdminApiTransport;
  onBack: () => void;
  onSessionExpired: () => void;
}>;

const STATUS_LABELS: Readonly<Record<ContentStatus, string>> = {
  draft: "초안",
  published: "게시됨",
  archived: "보관됨",
};
const POST_MUTATION_REFRESH_FAILURE =
  "저장은 완료됐지만 목록 순서를 새로고침하지 못했습니다. 새로고침을 다시 시도해 주세요.";

function isSessionExpired(error: unknown): boolean {
  return isAdminApiError(error) && error.kind === "session-expired";
}

function mutationErrorMessage(
  error: unknown,
  action: "create" | "update",
): string {
  if (isAdminApiError(error)) {
    if (error.kind === "invalid-request") {
      return "입력 형식을 확인해 주세요.";
    }
    if (error.kind === "content-not-found") {
      return "공지 정보가 달라졌습니다. 목록을 새로고침해 주세요.";
    }
    if (error.kind === "slug-conflict") {
      return "이미 사용 중인 슬러그입니다. 다른 값을 입력해 주세요.";
    }
    if (error.kind === "publish-validation-failed") {
      return "게시하려면 본문과 게시 시각을 입력해 주세요.";
    }
    if (error.kind === "notice-window-invalid") {
      return "만료 시각은 게시 시각이 있을 때 그보다 늦어야 합니다.";
    }
    if (error.kind === "forbidden") {
      return "보안 요청을 확인할 수 없습니다. 다시 저장해 주세요.";
    }
  }
  return action === "create"
    ? "공지를 생성하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요."
    : "공지를 수정하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.";
}

function validationMessage(kind: NoticeDraftValidationKind): string {
  if (kind === "window-invalid") {
    return "만료 시각은 게시 시각이 있을 때 그보다 늦어야 합니다.";
  }
  if (kind === "publish-invalid") {
    return "게시하려면 공백이 아닌 본문과 게시 시각을 입력해 주세요.";
  }
  return "제목·슬러그·문자 길이와 날짜·시간 입력을 확인해 주세요.";
}

function formatTimestamp(value: string | null): string {
  return value === null ? "없음" : new Date(value).toLocaleString("ko-KR");
}

export function AdminNoticeManager({
  transport,
  onBack,
  onSessionExpired,
}: AdminNoticeManagerProps) {
  const api = useMemo(() => new AdminNoticeApi(transport), [transport]);
  const [items, setItems] = useState<readonly Notice[]>([]);
  const [listState, setListState] = useState<ListState>("loading");
  const [listMessage, setListMessage] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createDraft, setCreateDraft] = useState<NoticeDraft>(EMPTY_NOTICE_DRAFT);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState<NoticeDraft | null>(null);
  const [mutationPending, setMutationPending] = useState(false);
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const requestSequenceRef = useRef(0);
  const mutationBusyRef = useRef(false);
  const createTriggerRef = useRef<HTMLButtonElement>(null);
  const createTitleRef = useRef<HTMLInputElement>(null);
  const editTitleRef = useRef<HTMLInputElement>(null);
  const editTriggerRefs = useRef(new Map<string, HTMLButtonElement>());
  const restoreFocusRef = useRef<HTMLButtonElement | null>(null);

  const loadNotices = useCallback(
    async (mode: ListLoadMode) => {
      const sequence = ++requestSequenceRef.current;
      setListMessage(null);
      setListState(mode === "initial" ? "loading" : "refreshing");
      try {
        const response = await api.list();
        if (sequence !== requestSequenceRef.current) return;
        setItems(response);
        setListState("ready");
      } catch (error) {
        if (sequence !== requestSequenceRef.current) return;
        if (isSessionExpired(error)) {
          onSessionExpired();
          return;
        }
        setListMessage(
          mode === "post-mutation"
            ? POST_MUTATION_REFRESH_FAILURE
            : "공지 목록을 불러오지 못했습니다. 연결을 확인해 주세요.",
        );
        setListState(mode === "initial" ? "error" : "ready");
      }
    },
    [api, onSessionExpired],
  );

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void loadNotices("initial"), 0);
    return () => {
      window.clearTimeout(initialLoad);
      requestSequenceRef.current += 1;
    };
  }, [loadNotices]);

  useEffect(() => {
    if (createOpen) {
      createTitleRef.current?.focus();
      return;
    }
    if (editingId) {
      editTitleRef.current?.focus();
      return;
    }
    if (listState !== "ready" || mutationPending) return;
    const target = restoreFocusRef.current;
    if (!target || !target.isConnected || target.disabled || target.tabIndex < 0) {
      return;
    }
    target.focus();
    if (document.activeElement === target) {
      restoreFocusRef.current = null;
    }
  }, [createOpen, editingId, listState, mutationPending]);

  function openCreate() {
    setActionMessage(null);
    setSuccessMessage(null);
    setEditingId(null);
    setEditDraft(null);
    setCreateDraft(EMPTY_NOTICE_DRAFT);
    setCreateOpen(true);
  }

  function closeCreate() {
    restoreFocusRef.current = createTriggerRef.current;
    setActionMessage(null);
    setCreateOpen(false);
  }

  function openEdit(item: Notice) {
    setActionMessage(null);
    setSuccessMessage(null);
    setCreateOpen(false);
    setEditingId(item.id);
    setEditDraft(noticeToDraft(item));
  }

  function closeEdit() {
    if (editingId) {
      restoreFocusRef.current = editTriggerRefs.current.get(editingId) ?? null;
    }
    setActionMessage(null);
    setEditingId(null);
    setEditDraft(null);
  }

  function updateCurrentDraft(patch: Partial<NoticeDraft>) {
    setActionMessage(null);
    if (createOpen) {
      setCreateDraft((current) => ({ ...current, ...patch }));
    } else if (editingId) {
      setEditDraft((current) => (current ? { ...current, ...patch } : current));
    }
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mutationBusyRef.current || listState !== "ready") return;
    const validation = validateNoticeDraft(createDraft, "create");
    const request = buildNoticeCreateRequest(createDraft);
    if (validation || !request) {
      setActionMessage(validationMessage(validation ?? "invalid-fields"));
      return;
    }
    mutationBusyRef.current = true;
    requestSequenceRef.current += 1;
    setMutationPending(true);
    setActionMessage(null);
    try {
      const created = await api.create(request);
      setItems((current) => applyContentMutationResult(current, created));
      setSuccessMessage("공지를 생성했습니다.");
      restoreFocusRef.current = createTriggerRef.current;
      setCreateOpen(false);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadNotices("post-mutation");
    } catch (error) {
      if (isSessionExpired(error)) onSessionExpired();
      else setActionMessage(mutationErrorMessage(error, "create"));
    } finally {
      mutationBusyRef.current = false;
      setMutationPending(false);
    }
  }

  async function handleUpdate(event: FormEvent<HTMLFormElement>, item: Notice) {
    event.preventDefault();
    if (mutationBusyRef.current || !editDraft || listState !== "ready") return;
    const validation = validateNoticeDraft(editDraft, "update");
    const request = buildNoticeUpdateRequest(editDraft);
    if (validation || !request) {
      setActionMessage(validationMessage(validation ?? "invalid-fields"));
      return;
    }
    mutationBusyRef.current = true;
    requestSequenceRef.current += 1;
    setMutationPending(true);
    setActionMessage(null);
    try {
      const updated = await api.update(item.id, request);
      if (updated.slug !== item.slug) {
        throw new Error("immutable notice contract drift");
      }
      setItems((current) => applyContentMutationResult(current, updated));
      setSuccessMessage("공지를 수정했습니다.");
      restoreFocusRef.current = editTriggerRefs.current.get(item.id) ?? null;
      setEditingId(null);
      setEditDraft(null);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadNotices("post-mutation");
    } catch (error) {
      if (isSessionExpired(error)) onSessionExpired();
      else setActionMessage(mutationErrorMessage(error, "update"));
    } finally {
      mutationBusyRef.current = false;
      setMutationPending(false);
    }
  }

  const controlsDisabled = mutationPending || listState === "refreshing";
  const interactionDisabled = controlsDisabled || listState !== "ready";

  function renderFields(draft: NoticeDraft, mode: "create" | "edit") {
    const prefix = `notice-${mode}`;
    return (
      <div className={contentStyles.fieldGrid}>
        {mode === "edit" ? (
          <label className={contentStyles.field}>
            상태
            <select
              value={draft.status}
              onChange={(event) =>
                updateCurrentDraft({ status: event.target.value as ContentStatus })
              }
            >
              {CONTENT_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {STATUS_LABELS[status]}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <label className={contentStyles.field}>
          제목
          <input
            ref={mode === "create" ? createTitleRef : editTitleRef}
            value={draft.title}
            maxLength={200}
            onChange={(event) => updateCurrentDraft({ title: event.target.value })}
          />
        </label>
        <div className={contentStyles.field}>
          <label htmlFor={`${prefix}-slug`}>
            {mode === "create" ? "슬러그" : "슬러그 (변경 불가)"}
          </label>
          <input
            id={`${prefix}-slug`}
            aria-describedby={`${prefix}-slug-help`}
            value={draft.slug}
            maxLength={160}
            inputMode="url"
            placeholder={mode === "create" ? "holiday-hours" : undefined}
            disabled={mode === "edit"}
            readOnly={mode === "edit"}
            onChange={(event) => updateCurrentDraft({ slug: event.target.value })}
          />
          <span id={`${prefix}-slug-help`} className={contentStyles.help}>
            영문 소문자·숫자와 단어 사이 하이픈만 사용하며 생성 뒤 변경할 수 없습니다.
          </span>
        </div>
        <label className={styles.checkboxField}>
          <input
            type="checkbox"
            checked={draft.pinned}
            onChange={(event) => updateCurrentDraft({ pinned: event.target.checked })}
          />
          상단 고정
        </label>
        <label className={contentStyles.wideField}>
          요약
          <textarea
            value={draft.summary}
            maxLength={300}
            onChange={(event) => updateCurrentDraft({ summary: event.target.value })}
          />
        </label>
        <div className={`${contentStyles.wideField} ${styles.bodyField}`}>
          <label htmlFor={`${prefix}-body`}>Markdown 본문</label>
          <textarea
            id={`${prefix}-body`}
            aria-describedby={`${prefix}-body-help`}
            value={draft.bodyMarkdown}
            maxLength={50_000}
            spellCheck="false"
            onChange={(event) =>
              updateCurrentDraft({ bodyMarkdown: event.target.value })
            }
          />
          <span id={`${prefix}-body-help`} className={contentStyles.help}>
            Markdown source만 저장하며 이 화면에서는 HTML로 렌더링하지 않습니다.
          </span>
        </div>
        <label className={contentStyles.field}>
          게시 시각
          <input
            type="datetime-local"
            step="0.000001"
            value={draft.publishedAt}
            onChange={(event) =>
              updateCurrentDraft({ publishedAt: event.target.value })
            }
          />
        </label>
        <label className={contentStyles.field}>
          만료 시각
          <input
            type="datetime-local"
            step="0.000001"
            value={draft.expiresAt}
            onChange={(event) =>
              updateCurrentDraft({ expiresAt: event.target.value })
            }
          />
        </label>
      </div>
    );
  }

  return (
    <section className={contentStyles.manager} aria-labelledby="notice-title">
      <div className={contentStyles.topBar}>
        <button type="button" onClick={onBack} disabled={mutationPending}>
          관리 홈으로
        </button>
        <button
          type="button"
          disabled={
            controlsDisabled ||
            listState === "loading" ||
            listState === "error" ||
            createOpen ||
            editingId !== null
          }
          onClick={() => void loadNotices("refresh")}
        >
          {listState === "refreshing" ? "새로고침 중" : "새로고침"}
        </button>
      </div>

      <header className={contentStyles.header}>
        <p>Notice content</p>
        <h2 id="notice-title">공지 관리</h2>
        <span>게시·만료 기간과 고정 여부를 관리하고 Markdown source를 편집합니다.</span>
      </header>

      <div className={contentStyles.headerRow}>
        <div>
          <h3>공지 콘텐츠</h3>
          <p className={contentStyles.help}>
            서버가 정한 고정·게시·수정 시각·식별자 순서를 그대로 표시합니다.
          </p>
        </div>
        <button
          ref={createTriggerRef}
          className={contentStyles.primaryButton}
          type="button"
          disabled={interactionDisabled || createOpen || editingId !== null}
          onClick={openCreate}
        >
          새 공지
        </button>
      </div>

      {successMessage ? (
        <p className={contentStyles.success} role="status">
          {successMessage}
        </p>
      ) : null}

      {createOpen ? (
        <section
          className={contentStyles.createPanel}
          aria-labelledby="notice-create-title"
        >
          <div className={contentStyles.headerRow}>
            <div>
              <h3 id="notice-create-title">새 공지</h3>
              <p className={contentStyles.help}>새 항목은 항상 초안으로 생성됩니다.</p>
            </div>
          </div>
          <form
            className={contentStyles.form}
            noValidate
            onSubmit={(event) => void handleCreate(event)}
          >
            {renderFields(createDraft, "create")}
            {actionMessage ? (
              <p className={contentStyles.alert} role="alert">
                {actionMessage}
              </p>
            ) : null}
            <div className={contentStyles.formActions}>
              <button
                className={contentStyles.secondaryButton}
                type="button"
                disabled={mutationPending}
                onClick={closeCreate}
              >
                생성 취소
              </button>
              <button
                className={contentStyles.primaryButton}
                type="submit"
                disabled={interactionDisabled}
              >
                {mutationPending ? "생성 중" : "공지 생성"}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {listState === "loading" ? (
        <p className={contentStyles.statePanel} role="status">
          공지 목록을 불러오고 있습니다.
        </p>
      ) : null}
      {listState === "error" ? (
        <div className={contentStyles.statePanel}>
          <p className={contentStyles.alert} role="alert">
            {listMessage}
          </p>
          <button
            className={contentStyles.secondaryButton}
            type="button"
            onClick={() => void loadNotices("initial")}
          >
            다시 시도
          </button>
        </div>
      ) : null}
      {listState !== "loading" && listState !== "error" && listMessage ? (
        <p className={contentStyles.alert} role="alert">
          {listMessage}
        </p>
      ) : null}
      {listState !== "loading" &&
      listState !== "error" &&
      items.length === 0 ? (
        <p className={contentStyles.emptyState}>
          등록된 공지가 없습니다. 새 공지를 초안으로 만들어 주세요.
        </p>
      ) : null}

      {items.length > 0 ? (
        <ul
          className={contentStyles.contentList}
          aria-label="공지 목록"
          aria-busy={listState === "refreshing"}
        >
          {items.map((item) => (
            <li key={item.id}>
              <div className={contentStyles.cardHeading}>
                <div>
                  <h3>{item.title}</h3>
                  <code className={contentStyles.slug}>{item.slug}</code>
                </div>
                <button
                  ref={(element) => {
                    if (element) editTriggerRefs.current.set(item.id, element);
                    else editTriggerRefs.current.delete(item.id);
                  }}
                  className={contentStyles.editButton}
                  type="button"
                  aria-label={`${item.title} 수정`}
                  disabled={
                    interactionDisabled ||
                    createOpen ||
                    (editingId !== null && editingId !== item.id)
                  }
                  onClick={() => openEdit(item)}
                >
                  수정
                </button>
              </div>
              <div className={contentStyles.metadataRow}>
                <strong className={contentStyles.badge}>
                  {STATUS_LABELS[item.status]}
                </strong>
                <span>{item.pinned ? "상단 고정" : "일반 공지"}</span>
                <span>게시 {formatTimestamp(item.publishedAt)}</span>
                <span>만료 {formatTimestamp(item.expiresAt)}</span>
                <span>수정 {formatTimestamp(item.updatedAt)}</span>
              </div>
              <p className={contentStyles.description}>
                {item.summary ?? "요약 없음"}
              </p>

              {editingId === item.id && editDraft ? (
                <section
                  className={contentStyles.editPanel}
                  aria-label={`${item.title} 수정 form`}
                >
                  <form
                    className={contentStyles.form}
                    noValidate
                    onSubmit={(event) => void handleUpdate(event, item)}
                  >
                    {renderFields(editDraft, "edit")}
                    {actionMessage ? (
                      <p className={contentStyles.alert} role="alert">
                        {actionMessage}
                      </p>
                    ) : null}
                    <p className={contentStyles.help}>
                      보관은 영구 삭제가 아니며 초안 또는 게시 상태로 복구할 수 있습니다.
                    </p>
                    <div className={contentStyles.formActions}>
                      <button
                        className={contentStyles.secondaryButton}
                        type="button"
                        disabled={mutationPending}
                        onClick={closeEdit}
                      >
                        수정 취소
                      </button>
                      <button
                        className={contentStyles.primaryButton}
                        type="submit"
                        disabled={interactionDisabled}
                      >
                        {mutationPending ? "저장 중" : "변경 저장"}
                      </button>
                    </div>
                  </form>
                </section>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
