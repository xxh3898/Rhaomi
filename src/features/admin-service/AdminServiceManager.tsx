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
import styles from "@/features/admin-content/AdminContentManager.module.css";
import {
  applyContentMutationResult,
  CONTENT_STATUSES,
  isSlug,
  nullableText,
  parseOptionalSortOrder,
  parseRequiredSortOrder,
  type ContentStatus,
} from "@/features/admin-content/types";

import { AdminServiceApi } from "./api";
import type {
  CreateServiceRequest,
  GroomingService,
  UpdateServiceRequest,
} from "./types";

type ListState = "loading" | "ready" | "error" | "refreshing";
type ListLoadMode = "initial" | "refresh" | "post-mutation";
type ServiceCreateDraft = {
  name: string;
  slug: string;
  description: string;
  priceText: string;
  sortOrder: string;
};
type ServiceEditDraft = {
  status: ContentStatus;
  name: string;
  description: string;
  priceText: string;
  sortOrder: string;
};

type AdminServiceManagerProps = Readonly<{
  transport: AdminApiTransport;
  onBack: () => void;
  onSessionExpired: () => void;
}>;

const EMPTY_CREATE_DRAFT: ServiceCreateDraft = {
  name: "",
  slug: "",
  description: "",
  priceText: "",
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
      return "서비스 정보가 달라졌습니다. 목록을 새로고침해 주세요.";
    }
    if (error.kind === "slug-conflict") {
      return "이미 사용 중인 슬러그입니다. 다른 값을 입력해 주세요.";
    }
    if (error.kind === "publish-validation-failed") {
      return "게시 조건을 충족하지 못했습니다. 설명과 가격 안내를 확인해 주세요.";
    }
    if (error.kind === "forbidden") {
      return "보안 요청을 확인할 수 없습니다. 다시 시도해 주세요.";
    }
  }
  return action === "create"
    ? "서비스를 생성하지 못했습니다. 다시 시도해 주세요."
    : "서비스를 수정하지 못했습니다. 다시 시도해 주세요.";
}

function validateName(value: string): string | null {
  const normalized = value.trim();
  if (normalized.length === 0 || normalized.length > 100) {
    return null;
  }
  return normalized;
}

function buildCreateRequest(
  draft: ServiceCreateDraft,
): CreateServiceRequest | null {
  const name = validateName(draft.name);
  const slug = draft.slug.trim();
  const priceText = nullableText(draft.priceText);
  const sortOrder = parseOptionalSortOrder(draft.sortOrder);
  if (
    !name ||
    !isSlug(slug) ||
    (priceText !== null && priceText.length > 100) ||
    sortOrder === undefined
  ) {
    return null;
  }
  return {
    name,
    slug,
    description: nullableText(draft.description),
    priceText,
    sortOrder,
  };
}

function buildUpdateRequest(
  draft: ServiceEditDraft,
): UpdateServiceRequest | null {
  const name = validateName(draft.name);
  const description = nullableText(draft.description);
  const priceText = nullableText(draft.priceText);
  const sortOrder = parseRequiredSortOrder(draft.sortOrder);
  if (
    !name ||
    (priceText !== null && priceText.length > 100) ||
    sortOrder === undefined
  ) {
    return null;
  }
  if (draft.status === "published" && (description === null || priceText === null)) {
    return null;
  }
  return {
    status: draft.status,
    name,
    description,
    priceText,
    sortOrder,
  };
}

export function AdminServiceManager({
  transport,
  onBack,
  onSessionExpired,
}: AdminServiceManagerProps) {
  const api = useMemo(() => new AdminServiceApi(transport), [transport]);
  const [items, setItems] = useState<readonly GroomingService[]>([]);
  const [listState, setListState] = useState<ListState>("loading");
  const [listMessage, setListMessage] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createDraft, setCreateDraft] =
    useState<ServiceCreateDraft>(EMPTY_CREATE_DRAFT);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState<ServiceEditDraft | null>(null);
  const [mutationPending, setMutationPending] = useState(false);
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const requestSequenceRef = useRef(0);
  const mutationBusyRef = useRef(false);
  const createTriggerRef = useRef<HTMLButtonElement>(null);
  const createNameRef = useRef<HTMLInputElement>(null);
  const editNameRef = useRef<HTMLInputElement>(null);
  const editTriggerRefs = useRef(new Map<string, HTMLButtonElement>());
  const restoreFocusRef = useRef<HTMLElement | null>(null);

  const loadServices = useCallback(
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
            : "서비스 목록을 불러오지 못했습니다. 연결을 확인해 주세요.",
        );
        setListState(mode === "initial" ? "error" : "ready");
      }
    },
    [api, onSessionExpired],
  );

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void loadServices("initial"), 0);
    return () => {
      window.clearTimeout(initialLoad);
      requestSequenceRef.current += 1;
    };
  }, [loadServices]);

  useEffect(() => {
    if (createOpen) {
      createNameRef.current?.focus();
      return;
    }
    if (editingId) {
      editNameRef.current?.focus();
      return;
    }
    const target = restoreFocusRef.current;
    restoreFocusRef.current = null;
    target?.focus();
  }, [createOpen, editingId]);

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

  function openEdit(item: GroomingService) {
    setActionMessage(null);
    setSuccessMessage(null);
    setCreateOpen(false);
    setEditingId(item.id);
    setEditDraft({
      status: item.status,
      name: item.name,
      description: item.description ?? "",
      priceText: item.priceText ?? "",
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
        "이름, 소문자 kebab-case 슬러그, 100자 이내 가격과 정렬 순서를 확인해 주세요.",
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
      setSuccessMessage("서비스를 생성했습니다.");
      restoreFocusRef.current = createTriggerRef.current;
      setCreateOpen(false);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadServices("post-mutation");
    } catch (error) {
      if (isSessionExpired(error)) onSessionExpired();
      else setActionMessage(mutationErrorMessage(error, "create"));
    } finally {
      mutationBusyRef.current = false;
      setMutationPending(false);
    }
  }

  async function handleUpdate(
    event: FormEvent<HTMLFormElement>,
    item: GroomingService,
  ) {
    event.preventDefault();
    if (mutationBusyRef.current || !editDraft || listState !== "ready") return;
    const description = nullableText(editDraft.description);
    const priceText = nullableText(editDraft.priceText);
    if (
      editDraft.status === "published" &&
      (description === null || priceText === null)
    ) {
      setActionMessage("게시하려면 설명과 가격 안내가 필요합니다.");
      return;
    }
    const request = buildUpdateRequest(editDraft);
    if (!request) {
      setActionMessage("이름, 100자 이내 가격과 0 이상의 정렬 순서를 확인해 주세요.");
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
      setSuccessMessage("서비스를 수정했습니다.");
      restoreFocusRef.current = editTriggerRefs.current.get(item.id) ?? null;
      setEditingId(null);
      setEditDraft(null);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadServices("post-mutation");
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
    <section className={styles.manager} aria-labelledby="service-title">
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
          onClick={() => void loadServices("refresh")}
        >
          {listState === "refreshing" ? "새로고침 중" : "새로고침"}
        </button>
      </div>

      <header className={styles.header}>
        <p>Grooming service content</p>
        <h2 id="service-title">서비스 관리</h2>
        <span>
          게시 서비스에는 설명과 가격 안내가 필요하며 보관 뒤에도 복구할 수 있습니다.
        </span>
      </header>

      <div className={styles.headerRow}>
        <div>
          <h3>미용 서비스 콘텐츠</h3>
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
          새 서비스
        </button>
      </div>

      {successMessage ? (
        <p className={styles.success} role="status">
          {successMessage}
        </p>
      ) : null}

      {createOpen ? (
        <section className={styles.createPanel} aria-labelledby="service-create-title">
          <div className={styles.headerRow}>
            <div>
              <h3 id="service-create-title">새 서비스</h3>
              <p className={styles.help}>새 항목은 항상 초안으로 생성됩니다.</p>
            </div>
          </div>
          <form className={styles.form} noValidate onSubmit={handleCreate}>
            <div className={styles.fieldGrid}>
              <label className={styles.field}>
                서비스 이름
                <input
                  ref={createNameRef}
                  value={createDraft.name}
                  maxLength={100}
                  onChange={(event) =>
                    setCreateDraft({ ...createDraft, name: event.target.value })
                  }
                />
              </label>
              <div className={styles.field}>
                <label htmlFor="service-create-slug">슬러그</label>
                <input
                  id="service-create-slug"
                  aria-describedby="service-create-slug-help"
                  value={createDraft.slug}
                  maxLength={120}
                  inputMode="url"
                  placeholder="basic-grooming"
                  onChange={(event) =>
                    setCreateDraft({ ...createDraft, slug: event.target.value })
                  }
                />
                <span id="service-create-slug-help" className={styles.help}>
                  영문 소문자·숫자와 단어 사이 하이픈만 사용합니다.
                </span>
              </div>
              <label className={styles.wideField}>
                설명
                <textarea
                  value={createDraft.description}
                  onChange={(event) =>
                    setCreateDraft({ ...createDraft, description: event.target.value })
                  }
                />
              </label>
              <div className={styles.field}>
                <label htmlFor="service-create-price">가격 안내</label>
                <input
                  id="service-create-price"
                  aria-describedby="service-create-price-help"
                  value={createDraft.priceText}
                  maxLength={100}
                  onChange={(event) =>
                    setCreateDraft({ ...createDraft, priceText: event.target.value })
                  }
                />
                <span id="service-create-price-help" className={styles.help}>
                  숫자 계산 없이 화면에 표시할 자유 문구입니다.
                </span>
              </div>
              <label className={styles.field}>
                정렬 순서
                <input
                  type="number"
                  min="0"
                  step="1"
                  placeholder="기본값 100"
                  value={createDraft.sortOrder}
                  onChange={(event) =>
                    setCreateDraft({ ...createDraft, sortOrder: event.target.value })
                  }
                />
              </label>
            </div>
            {actionMessage ? (
              <p className={styles.alert} role="alert">
                {actionMessage}
              </p>
            ) : null}
            <div className={styles.formActions}>
              <button
                className={styles.secondaryButton}
                type="button"
                disabled={interactionDisabled}
                onClick={closeCreate}
              >
                생성 취소
              </button>
              <button
                className={styles.primaryButton}
                type="submit"
                disabled={mutationPending}
              >
                {mutationPending ? "생성 중" : "서비스 생성"}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {listState === "loading" ? (
        <p className={styles.statePanel} role="status">
          서비스 목록을 불러오고 있습니다.
        </p>
      ) : null}
      {listState === "error" ? (
        <div className={styles.statePanel}>
          <p className={styles.alert} role="alert">
            {listMessage}
          </p>
          <button
            className={styles.secondaryButton}
            type="button"
            onClick={() => void loadServices("initial")}
          >
            다시 시도
          </button>
        </div>
      ) : null}
      {listState !== "loading" && listState !== "error" && listMessage ? (
        <p className={styles.alert} role="alert">
          {listMessage}
        </p>
      ) : null}
      {listState !== "loading" && listState !== "error" && items.length === 0 ? (
        <p className={styles.emptyState}>
          등록된 서비스가 없습니다. 새 서비스를 초안으로 만들어 주세요.
        </p>
      ) : null}

      {items.length > 0 ? (
        <ul
          className={styles.contentList}
          aria-label="서비스 목록"
          aria-busy={listState === "refreshing"}
        >
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
              <div className={styles.metadataRow}>
                <strong className={styles.badge}>{statusLabel(item.status)}</strong>
                <span>정렬 {item.sortOrder}</span>
                <span>가격 {item.priceText ?? "미입력"}</span>
                <span>수정 {new Date(item.updatedAt).toLocaleString()}</span>
              </div>
              <p className={styles.description}>{item.description ?? "설명 없음"}</p>

              {editingId === item.id && editDraft ? (
                <section
                  className={styles.editPanel}
                  aria-label={`${item.name} 수정 form`}
                >
                  <form
                    className={styles.form}
                    noValidate
                    onSubmit={(event) => void handleUpdate(event, item)}
                  >
                    <div className={styles.fieldGrid}>
                      <label className={styles.field}>
                        상태
                        <select
                          value={editDraft.status}
                          onChange={(event) =>
                            setEditDraft({
                              ...editDraft,
                              status: event.target.value as ContentStatus,
                            })
                          }
                        >
                          {CONTENT_STATUSES.map((status) => (
                            <option key={status} value={status}>
                              {statusLabel(status)}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className={styles.field}>
                        서비스 이름
                        <input
                          ref={editNameRef}
                          value={editDraft.name}
                          maxLength={100}
                          onChange={(event) =>
                            setEditDraft({ ...editDraft, name: event.target.value })
                          }
                        />
                      </label>
                      <label className={styles.field}>
                        슬러그 (변경 불가)
                        <input value={item.slug} disabled readOnly />
                      </label>
                      <label className={styles.field}>
                        정렬 순서
                        <input
                          type="number"
                          min="0"
                          step="1"
                          value={editDraft.sortOrder}
                          onChange={(event) =>
                            setEditDraft({
                              ...editDraft,
                              sortOrder: event.target.value,
                            })
                          }
                        />
                      </label>
                      <label className={styles.wideField}>
                        설명
                        <textarea
                          value={editDraft.description}
                          onChange={(event) =>
                            setEditDraft({
                              ...editDraft,
                              description: event.target.value,
                            })
                          }
                        />
                      </label>
                      <label className={styles.field}>
                        가격 안내
                        <input
                          value={editDraft.priceText}
                          maxLength={100}
                          onChange={(event) =>
                            setEditDraft({
                              ...editDraft,
                              priceText: event.target.value,
                            })
                          }
                        />
                      </label>
                    </div>
                    {actionMessage ? (
                      <p className={styles.alert} role="alert">
                        {actionMessage}
                      </p>
                    ) : null}
                    <p className={styles.help}>
                      게시 상태는 설명과 가격 안내가 모두 있어야 하며 backend가 최종
                      검증합니다.
                    </p>
                    <div className={styles.formActions}>
                      <button
                        className={styles.secondaryButton}
                        type="button"
                        disabled={interactionDisabled}
                        onClick={closeEdit}
                      >
                        수정 취소
                      </button>
                      <button
                        className={styles.primaryButton}
                        type="submit"
                        disabled={mutationPending}
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
