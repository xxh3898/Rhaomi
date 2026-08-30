"use client";

import {
  FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import { isAdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";
import {
  applyContentMutationResult,
  CONTENT_STATUSES,
  isSlug,
  nullableText,
  parseOptionalSortOrder,
  parseRequiredSortOrder,
  type ContentStatus,
} from "@/features/admin-content/types";
import styles from "@/features/admin-content/AdminContentManager.module.css";

import { AdminBreedApi } from "./api";
import type { Breed, CreateBreedRequest, UpdateBreedRequest } from "./types";

type ListState = "loading" | "ready" | "error" | "refreshing";
type ListLoadMode = "initial" | "refresh" | "post-mutation";
type BreedCreateDraft = {
  name: string;
  slug: string;
  description: string;
  sortOrder: string;
};
type BreedEditDraft = {
  status: ContentStatus;
  name: string;
  description: string;
  sortOrder: string;
};

type AdminBreedManagerProps = Readonly<{
  transport: AdminApiTransport;
  onBack: () => void;
  onSessionExpired: () => void;
}>;

const EMPTY_CREATE_DRAFT: BreedCreateDraft = {
  name: "",
  slug: "",
  description: "",
  sortOrder: "",
};
const POST_MUTATION_REFRESH_FAILURE =
  "저장은 완료됐지만 목록 순서를 새로고침하지 못했습니다. 새로고침을 다시 시도해 주세요.";

function statusLabel(status: ContentStatus): string {
  if (status === "published") return "게시됨";
  if (status === "archived") return "보관됨";
  return "초안";
}

function isSessionExpired(error: unknown): boolean {
  return isAdminApiError(error) && error.kind === "session-expired";
}

function mutationErrorMessage(error: unknown, action: "create" | "update"): string {
  if (isAdminApiError(error)) {
    if (error.kind === "invalid-request") {
      return "입력 형식을 확인해 주세요.";
    }
    if (error.kind === "content-not-found") {
      return "견종 정보가 달라졌습니다. 목록을 새로고침해 주세요.";
    }
    if (error.kind === "slug-conflict") {
      return "이미 사용 중인 슬러그입니다. 다른 값을 입력해 주세요.";
    }
    if (error.kind === "publish-validation-failed") {
      return "게시 조건을 충족하지 못했습니다. 입력값을 확인해 주세요.";
    }
    if (error.kind === "forbidden") {
      return "보안 요청을 확인할 수 없습니다. 다시 시도해 주세요.";
    }
  }
  return action === "create"
    ? "견종을 생성하지 못했습니다. 다시 시도해 주세요."
    : "견종을 수정하지 못했습니다. 다시 시도해 주세요.";
}

function validateName(value: string): string | null {
  const normalized = value.trim();
  if (normalized.length === 0 || normalized.length > 100) {
    return null;
  }
  return normalized;
}

function buildCreateRequest(draft: BreedCreateDraft): CreateBreedRequest | null {
  const name = validateName(draft.name);
  const slug = draft.slug.trim();
  const sortOrder = parseOptionalSortOrder(draft.sortOrder);
  if (!name || !isSlug(slug) || sortOrder === undefined) {
    return null;
  }
  return {
    name,
    slug,
    description: nullableText(draft.description),
    sortOrder,
  };
}

function buildUpdateRequest(draft: BreedEditDraft): UpdateBreedRequest | null {
  const name = validateName(draft.name);
  const sortOrder = parseRequiredSortOrder(draft.sortOrder);
  if (!name || sortOrder === undefined) {
    return null;
  }
  return {
    status: draft.status,
    name,
    description: nullableText(draft.description),
    sortOrder,
  };
}

export function AdminBreedManager({
  transport,
  onBack,
  onSessionExpired,
}: AdminBreedManagerProps) {
  const api = useMemo(() => new AdminBreedApi(transport), [transport]);
  const [items, setItems] = useState<readonly Breed[]>([]);
  const [listState, setListState] = useState<ListState>("loading");
  const [listMessage, setListMessage] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createDraft, setCreateDraft] = useState<BreedCreateDraft>(EMPTY_CREATE_DRAFT);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState<BreedEditDraft | null>(null);
  const [mutationPending, setMutationPending] = useState(false);
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const requestSequenceRef = useRef(0);
  const mutationBusyRef = useRef(false);
  const createTriggerRef = useRef<HTMLButtonElement>(null);
  const createNameRef = useRef<HTMLInputElement>(null);
  const editNameRef = useRef<HTMLInputElement>(null);
  const editTriggerRefs = useRef(new Map<string, HTMLButtonElement>());
  const restoreFocusRef = useRef<HTMLButtonElement | null>(null);

  const loadBreeds = useCallback(
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
            : "견종 목록을 불러오지 못했습니다. 연결을 확인해 주세요.",
        );
        setListState(mode === "initial" ? "error" : "ready");
      }
    },
    [api, onSessionExpired],
  );

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void loadBreeds("initial"), 0);
    return () => {
      window.clearTimeout(initialLoad);
      requestSequenceRef.current += 1;
    };
  }, [loadBreeds]);

  useEffect(() => {
    if (createOpen) {
      createNameRef.current?.focus();
      return;
    }
    if (editingId) {
      editNameRef.current?.focus();
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
    setCreateDraft(EMPTY_CREATE_DRAFT);
    setCreateOpen(true);
  }

  function closeCreate() {
    restoreFocusRef.current = createTriggerRef.current;
    setActionMessage(null);
    setCreateOpen(false);
  }

  function openEdit(item: Breed) {
    setActionMessage(null);
    setSuccessMessage(null);
    setCreateOpen(false);
    setEditingId(item.id);
    setEditDraft({
      status: item.status,
      name: item.name,
      description: item.description ?? "",
      sortOrder: String(item.sortOrder),
    });
  }

  function closeEdit() {
    if (editingId) {
      restoreFocusRef.current = editTriggerRefs.current.get(editingId) ?? null;
    }
    setActionMessage(null);
    setEditingId(null);
    setEditDraft(null);
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mutationBusyRef.current || listState !== "ready") return;
    const request = buildCreateRequest(createDraft);
    if (!request) {
      setActionMessage(
        "이름, 소문자 kebab-case 슬러그와 0 이상의 정렬 순서를 확인해 주세요.",
      );
      return;
    }
    mutationBusyRef.current = true;
    requestSequenceRef.current += 1;
    setMutationPending(true);
    setActionMessage(null);
    try {
      const created = await api.create(request);
      setItems((current) => applyContentMutationResult(current, created));
      setSuccessMessage("견종을 생성했습니다.");
      restoreFocusRef.current = createTriggerRef.current;
      setCreateOpen(false);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadBreeds("post-mutation");
    } catch (error) {
      if (isSessionExpired(error)) onSessionExpired();
      else setActionMessage(mutationErrorMessage(error, "create"));
    } finally {
      mutationBusyRef.current = false;
      setMutationPending(false);
    }
  }

  async function handleUpdate(event: FormEvent<HTMLFormElement>, item: Breed) {
    event.preventDefault();
    if (mutationBusyRef.current || !editDraft || listState !== "ready") return;
    const request = buildUpdateRequest(editDraft);
    if (!request) {
      setActionMessage("이름과 0 이상의 정렬 순서를 확인해 주세요.");
      return;
    }
    mutationBusyRef.current = true;
    requestSequenceRef.current += 1;
    setMutationPending(true);
    setActionMessage(null);
    try {
      const updated = await api.update(item.id, request);
      if (updated.slug !== item.slug) {
        throw new Error("immutable content contract drift");
      }
      setItems((current) => applyContentMutationResult(current, updated));
      setSuccessMessage("견종을 수정했습니다.");
      restoreFocusRef.current = editTriggerRefs.current.get(item.id) ?? null;
      setEditingId(null);
      setEditDraft(null);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadBreeds("post-mutation");
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

  return (
    <section className={styles.manager} aria-labelledby="breed-title">
      <div className={styles.topBar}>
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
          onClick={() => void loadBreeds("refresh")}
        >
          {listState === "refreshing" ? "새로고침 중" : "새로고침"}
        </button>
      </div>

      <header className={styles.header}>
        <p>Breed content</p>
        <h2 id="breed-title">견종 관리</h2>
        <span>슬러그는 생성 뒤 변경할 수 없으며 보관한 견종은 복구할 수 있습니다.</span>
      </header>

      <div className={styles.headerRow}>
        <div>
          <h3>견종 콘텐츠</h3>
          <p className={styles.help}>
            서버가 정한 정렬 순서, 이름, 식별자 순서를 그대로 표시합니다.
          </p>
        </div>
        <button
          ref={createTriggerRef}
          className={styles.primaryButton}
          type="button"
          disabled={interactionDisabled || createOpen || editingId !== null}
          onClick={openCreate}
        >
          새 견종
        </button>
      </div>

      {successMessage ? (
        <p className={styles.success} role="status">{successMessage}</p>
      ) : null}

      {createOpen ? (
        <section className={styles.createPanel} aria-labelledby="breed-create-title">
          <div className={styles.headerRow}>
            <div>
              <h3 id="breed-create-title">새 견종</h3>
              <p className={styles.help}>새 항목은 항상 초안으로 생성됩니다.</p>
            </div>
          </div>
          <form className={styles.form} noValidate onSubmit={handleCreate}>
            <div className={styles.fieldGrid}>
              <label className={styles.field}>
                견종 이름
                <input
                  ref={createNameRef}
                  value={createDraft.name}
                  maxLength={100}
                  onChange={(event) => setCreateDraft({ ...createDraft, name: event.target.value })}
                />
              </label>
              <div className={styles.field}>
                <label htmlFor="breed-create-slug">슬러그</label>
                <input
                  id="breed-create-slug"
                  aria-describedby="breed-create-slug-help"
                  value={createDraft.slug}
                  maxLength={120}
                  inputMode="url"
                  placeholder="bichon-frise"
                  onChange={(event) => setCreateDraft({ ...createDraft, slug: event.target.value })}
                />
                <span id="breed-create-slug-help" className={styles.help}>
                  영문 소문자·숫자와 단어 사이 하이픈만 사용합니다.
                </span>
              </div>
              <label className={styles.wideField}>
                설명
                <textarea
                  value={createDraft.description}
                  onChange={(event) => setCreateDraft({ ...createDraft, description: event.target.value })}
                />
              </label>
              <label className={styles.field}>
                정렬 순서
                <input
                  type="number"
                  min="0"
                  step="1"
                  placeholder="기본값 100"
                  value={createDraft.sortOrder}
                  onChange={(event) => setCreateDraft({ ...createDraft, sortOrder: event.target.value })}
                />
              </label>
            </div>
            {actionMessage ? <p className={styles.alert} role="alert">{actionMessage}</p> : null}
            <div className={styles.formActions}>
              <button className={styles.secondaryButton} type="button" disabled={mutationPending} onClick={closeCreate}>
                생성 취소
              </button>
              <button className={styles.primaryButton} type="submit" disabled={interactionDisabled}>
                {mutationPending ? "생성 중" : "견종 생성"}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {listState === "loading" ? <p className={styles.statePanel} role="status">견종 목록을 불러오고 있습니다.</p> : null}
      {listState === "error" ? (
        <div className={styles.statePanel}>
          <p className={styles.alert} role="alert">{listMessage}</p>
          <button className={styles.secondaryButton} type="button" onClick={() => void loadBreeds("initial")}>다시 시도</button>
        </div>
      ) : null}
      {listState !== "loading" && listState !== "error" && listMessage ? <p className={styles.alert} role="alert">{listMessage}</p> : null}
      {listState !== "loading" && listState !== "error" && items.length === 0 ? <p className={styles.emptyState}>등록된 견종이 없습니다. 새 견종을 초안으로 만들어 주세요.</p> : null}

      {items.length > 0 ? (
        <ul className={styles.contentList} aria-label="견종 목록" aria-busy={listState === "refreshing"}>
          {items.map((item) => (
            <li key={item.id}>
              <div className={styles.cardHeading}>
                <div>
                  <h3>{item.name}</h3>
                  <code className={styles.slug}>{item.slug}</code>
                </div>
                <button
                  ref={(element) => {
                    if (element) editTriggerRefs.current.set(item.id, element);
                    else editTriggerRefs.current.delete(item.id);
                  }}
                  className={styles.editButton}
                  type="button"
                  aria-label={`${item.name} 수정`}
                  disabled={interactionDisabled || createOpen || (editingId !== null && editingId !== item.id)}
                  onClick={() => openEdit(item)}
                >
                  수정
                </button>
              </div>
              <div className={styles.metadataRow}>
                <strong className={styles.badge}>{statusLabel(item.status)}</strong>
                <span>정렬 {item.sortOrder}</span>
                <span>수정 {new Date(item.updatedAt).toLocaleString()}</span>
              </div>
              <p className={styles.description}>{item.description ?? "설명 없음"}</p>

              {editingId === item.id && editDraft ? (
                <section className={styles.editPanel} aria-label={`${item.name} 수정 form`}>
                  <form className={styles.form} noValidate onSubmit={(event) => void handleUpdate(event, item)}>
                    <div className={styles.fieldGrid}>
                      <label className={styles.field}>
                        상태
                        <select value={editDraft.status} onChange={(event) => setEditDraft({ ...editDraft, status: event.target.value as ContentStatus })}>
                          {CONTENT_STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
                        </select>
                      </label>
                      <label className={styles.field}>
                        견종 이름
                        <input ref={editNameRef} value={editDraft.name} maxLength={100} onChange={(event) => setEditDraft({ ...editDraft, name: event.target.value })} />
                      </label>
                      <label className={styles.field}>
                        슬러그 (변경 불가)
                        <input value={item.slug} disabled readOnly />
                      </label>
                      <label className={styles.field}>
                        정렬 순서
                        <input type="number" min="0" step="1" value={editDraft.sortOrder} onChange={(event) => setEditDraft({ ...editDraft, sortOrder: event.target.value })} />
                      </label>
                      <label className={styles.wideField}>
                        설명
                        <textarea value={editDraft.description} onChange={(event) => setEditDraft({ ...editDraft, description: event.target.value })} />
                      </label>
                    </div>
                    {actionMessage ? <p className={styles.alert} role="alert">{actionMessage}</p> : null}
                    <p className={styles.help}>보관은 영구 삭제가 아니며 초안 또는 게시 상태로 복구할 수 있습니다.</p>
                    <div className={styles.formActions}>
                      <button className={styles.secondaryButton} type="button" disabled={mutationPending} onClick={closeEdit}>수정 취소</button>
                      <button className={styles.primaryButton} type="submit" disabled={interactionDisabled}>{mutationPending ? "저장 중" : "변경 저장"}</button>
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
