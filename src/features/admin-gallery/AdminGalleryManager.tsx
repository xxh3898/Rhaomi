"use client";

import type { FormEvent, ReactNode, Ref } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { isAdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";
import { AdminBreedApi } from "@/features/admin-breed/api";
import type { Breed } from "@/features/admin-breed/types";
import {
  applyContentMutationResult,
  CONTENT_STATUSES,
  type ContentStatus,
} from "@/features/admin-content/types";
import contentStyles from "@/features/admin-content/AdminContentManager.module.css";
import {
  AdminMediaPicker,
  type AdminMediaPickerState,
} from "@/features/admin-media/AdminMediaPicker";
import { AdminMediaPreview } from "@/features/admin-media/AdminMediaPreview";
import { AdminMediaApi } from "@/features/admin-media/api";
import type { MediaItem } from "@/features/admin-media/types";
import { AdminServiceApi } from "@/features/admin-service/api";
import type { GroomingService } from "@/features/admin-service/types";

import { AdminGalleryApi } from "./api";
import {
  buildGalleryCreateRequest,
  buildGalleryUpdateRequest,
  EMPTY_GALLERY_DRAFT,
  galleryItemToDraft,
  type GalleryDraft,
  type GalleryItem,
} from "./types";
import styles from "./AdminGalleryManager.module.css";

type ListState = "loading" | "ready" | "error" | "refreshing";
type ListLoadMode = "initial" | "refresh" | "post-mutation";
type CatalogState<T> =
  | Readonly<{ kind: "loading" }>
  | Readonly<{ kind: "error" }>
  | Readonly<{ kind: "ready"; items: readonly T[] }>;
type PickerSlot = "cover" | "before" | "after";

type AdminGalleryManagerProps = Readonly<{
  transport: AdminApiTransport;
  onBack: () => void;
  onSessionExpired: () => void;
}>;

type MediaRelationControlProps = Readonly<{
  id: string;
  label: string;
  item: MediaItem | null;
  api: AdminMediaApi;
  selectedId: string | null;
  state: AdminMediaPickerState;
  publishedTarget: boolean;
  disabled: boolean;
  triggerRef: Ref<HTMLButtonElement>;
  onOpen: () => void;
  onClear: () => void;
  onSessionExpired: () => void;
  children: ReactNode;
}>;

const STATUS_LABELS: Readonly<Record<ContentStatus, string>> = {
  draft: "초안",
  published: "게시됨",
  archived: "보관됨",
};
const POST_MUTATION_REFRESH_FAILURE =
  "저장은 완료됐지만 목록 순서를 새로고침하지 못했습니다. 명시적으로 다시 새로고침해 주세요.";

function isSessionExpired(error: unknown): boolean {
  return isAdminApiError(error) && error.kind === "session-expired";
}

function mutationErrorMessage(error: unknown, action: "create" | "update"): string {
  if (isAdminApiError(error)) {
    if (error.kind === "invalid-request") {
      return "입력 형식을 확인해 주세요.";
    }
    if (error.kind === "gallery-item-not-found") {
      return "갤러리 항목을 찾을 수 없습니다. 목록을 새로고침해 주세요.";
    }
    if (error.kind === "gallery-relation-invalid") {
      return "선택한 관계의 존재 여부와 게시 상태를 확인해 주세요.";
    }
    if (error.kind === "gallery-publish-invalid") {
      return "게시 필수값과 시술 전·후 이미지 관계를 확인해 주세요.";
    }
    if (error.kind === "forbidden") {
      return "보안 요청을 확인할 수 없습니다. 다시 저장해 주세요.";
    }
  }
  return action === "create"
    ? "갤러리 항목을 생성하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요."
    : "갤러리 항목을 수정하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.";
}

function relationStatusLabel(status: ContentStatus): string {
  return STATUS_LABELS[status];
}

function resolveItem<T extends Readonly<{ id: string }>>(
  state: CatalogState<T>,
  id: string | null,
): T | null {
  return state.kind === "ready" && id !== null
    ? state.items.find((item) => item.id === id) ?? null
    : null;
}

function relationCardText<T extends Readonly<{ id: string; name: string; status: ContentStatus }>>(
  label: string,
  state: CatalogState<T>,
  id: string | null,
): string {
  if (id === null) return `${label} 미선택`;
  if (state.kind === "loading") return `${label} 관계 정보 확인 중`;
  if (state.kind === "error") return `${label} 관계 정보를 확인할 수 없음`;
  const item = state.items.find((candidate) => candidate.id === id);
  return item
    ? `${item.name} · ${relationStatusLabel(item.status)}`
    : `${label} 관계를 목록에서 찾을 수 없음`;
}

function selectedMediaStatusText(
  selectedId: string | null,
  item: MediaItem | null,
  state: AdminMediaPickerState,
  publishedTarget: boolean,
): string {
  if (selectedId === null) return "선택된 미디어 없음";
  if (state.kind === "loading") return "선택된 미디어 상태 확인 중";
  if (state.kind === "error") return "선택된 미디어 상태를 확인할 수 없음";
  if (!item) return "선택된 미디어를 목록에서 찾을 수 없음";
  if (item.status === "active") return "활성 미디어 선택됨";
  return publishedTarget
    ? "보관된 미디어 — 게시하려면 활성 미디어로 교체해 주세요."
    : "보관된 미디어 선택됨 — 초안·보관 상태에서 유지할 수 있습니다.";
}

function MediaRelationControl({
  id,
  label,
  item,
  api,
  selectedId,
  state,
  publishedTarget,
  disabled,
  triggerRef,
  onOpen,
  onClear,
  onSessionExpired,
  children,
}: MediaRelationControlProps) {
  const statusText = selectedMediaStatusText(
    selectedId,
    item,
    state,
    publishedTarget,
  );
  const invalid =
    selectedId !== null &&
    (state.kind === "error" || !item || (publishedTarget && item.status !== "active"));
  return (
    <div className={styles.mediaSlot}>
      <fieldset className={styles.mediaRelation}>
        <legend>{label}</legend>
        <p id={`${id}-status`} role={invalid ? "alert" : undefined}>
          {statusText}
        </p>
        {selectedId ? <code>{selectedId}</code> : null}
        {item ? (
          <div className={styles.selectedPreview}>
            <AdminMediaPreview
              api={api}
              item={item}
              alt={`${label} 현재 선택 미리보기`}
              onSessionExpired={onSessionExpired}
            />
          </div>
        ) : null}
        <div className={styles.relationActions}>
          <button
            ref={triggerRef}
            type="button"
            aria-label={`${label} 미디어 선택`}
            aria-describedby={`${id}-status`}
            disabled={disabled}
            onClick={onOpen}
          >
            미디어 선택
          </button>
          {selectedId ? (
            <button
              type="button"
              aria-label={`${label} 선택 해제`}
              disabled={disabled}
              onClick={onClear}
            >
              선택 해제
            </button>
          ) : null}
        </div>
      </fieldset>
      {children}
    </div>
  );
}

function validateDraft(
  draft: GalleryDraft,
  breeds: CatalogState<Breed>,
  services: CatalogState<GroomingService>,
  mediaState: AdminMediaPickerState,
): string | null {
  if (draft.beforeImageId !== null && draft.beforeImageId === draft.afterImageId) {
    return "시술 전과 후 이미지는 서로 달라야 합니다.";
  }
  const commonRequest =
    draft.status === "draft"
      ? buildGalleryCreateRequest(draft)
      : buildGalleryUpdateRequest(draft);
  if (!commonRequest) {
    return "문자 길이, 0 이상의 정렬 순서와 날짜·시간 입력을 확인해 주세요.";
  }
  if (breeds.kind !== "ready" || services.kind !== "ready" || mediaState.kind !== "ready") {
    return "관계 목록을 다시 불러온 뒤 저장해 주세요.";
  }
  const breed = resolveItem(breeds, draft.breedId);
  const service = resolveItem(services, draft.primaryServiceId);
  const selectedMediaIds = [
    draft.coverImageId,
    draft.beforeImageId,
    draft.afterImageId,
  ].filter((id): id is string => id !== null);
  const selectedMedia = selectedMediaIds.map(
    (id) => mediaState.items.find((item) => item.id === id) ?? null,
  );
  if (
    (draft.breedId !== null && !breed) ||
    (draft.primaryServiceId !== null && !service) ||
    selectedMedia.some((item) => item === null)
  ) {
    return "선택한 관계가 목록에 존재하는지 확인해 주세요.";
  }
  if (draft.status === "published") {
    const cover = resolveItem(mediaState, draft.coverImageId);
    const before = resolveItem(mediaState, draft.beforeImageId);
    const after = resolveItem(mediaState, draft.afterImageId);
    if (
      !breed ||
      breed.status !== "published" ||
      !service ||
      service.status !== "published" ||
      !cover ||
      cover.status !== "active" ||
      (before && before.status !== "active") ||
      (after && after.status !== "active") ||
      draft.altText.trim().length === 0 ||
      draft.publishedAt.length === 0
    ) {
      return "게시 상태의 필수값과 관계 상태를 확인해 주세요.";
    }
  }
  return null;
}

export function AdminGalleryManager({
  transport,
  onBack,
  onSessionExpired,
}: AdminGalleryManagerProps) {
  const galleryApi = useMemo(() => new AdminGalleryApi(transport), [transport]);
  const breedApi = useMemo(() => new AdminBreedApi(transport), [transport]);
  const serviceApi = useMemo(() => new AdminServiceApi(transport), [transport]);
  const mediaApi = useMemo(() => new AdminMediaApi(transport), [transport]);
  const [items, setItems] = useState<readonly GalleryItem[]>([]);
  const [listState, setListState] = useState<ListState>("loading");
  const [listMessage, setListMessage] = useState<string | null>(null);
  const [breedState, setBreedState] = useState<CatalogState<Breed>>({ kind: "loading" });
  const [serviceState, setServiceState] = useState<CatalogState<GroomingService>>({
    kind: "loading",
  });
  const [mediaState, setMediaState] = useState<AdminMediaPickerState>({ kind: "loading" });
  const [createOpen, setCreateOpen] = useState(false);
  const [createDraft, setCreateDraft] = useState<GalleryDraft>(EMPTY_GALLERY_DRAFT);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState<GalleryDraft | null>(null);
  const [pickerSlot, setPickerSlot] = useState<PickerSlot | null>(null);
  const [mutationPending, setMutationPending] = useState(false);
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const listRequestSequenceRef = useRef(0);
  const breedRequestSequenceRef = useRef(0);
  const serviceRequestSequenceRef = useRef(0);
  const mediaRequestSequenceRef = useRef(0);
  const mutationBusyRef = useRef(false);
  const createTriggerRef = useRef<HTMLButtonElement>(null);
  const createNameRef = useRef<HTMLInputElement>(null);
  const editNameRef = useRef<HTMLInputElement>(null);
  const editTriggerRefs = useRef(new Map<string, HTMLButtonElement>());
  const restoreFocusRef = useRef<HTMLButtonElement | null>(null);
  const pickerTriggerRefs = useRef(new Map<PickerSlot, HTMLButtonElement>());
  const pickerInitialFocusRef = useRef<HTMLButtonElement>(null);
  const restorePickerFocusRef = useRef<PickerSlot | null>(null);

  const loadGalleries = useCallback(
    async (mode: ListLoadMode) => {
      const sequence = ++listRequestSequenceRef.current;
      setListMessage(null);
      setListState(mode === "initial" ? "loading" : "refreshing");
      try {
        const response = await galleryApi.list();
        if (sequence !== listRequestSequenceRef.current) return;
        setItems(response);
        setListState("ready");
      } catch (error) {
        if (sequence !== listRequestSequenceRef.current) return;
        if (isSessionExpired(error)) {
          onSessionExpired();
          return;
        }
        setListMessage(
          mode === "post-mutation"
            ? POST_MUTATION_REFRESH_FAILURE
            : "갤러리 목록을 불러오지 못했습니다. 연결을 확인해 주세요.",
        );
        setListState(mode === "initial" ? "error" : "ready");
      }
    },
    [galleryApi, onSessionExpired],
  );

  const loadBreeds = useCallback(async () => {
    const sequence = ++breedRequestSequenceRef.current;
    setBreedState({ kind: "loading" });
    try {
      const response = await breedApi.list();
      if (sequence === breedRequestSequenceRef.current) {
        setBreedState({ kind: "ready", items: response });
      }
    } catch (error) {
      if (sequence !== breedRequestSequenceRef.current) return;
      if (isSessionExpired(error)) onSessionExpired();
      else setBreedState({ kind: "error" });
    }
  }, [breedApi, onSessionExpired]);

  const loadServices = useCallback(async () => {
    const sequence = ++serviceRequestSequenceRef.current;
    setServiceState({ kind: "loading" });
    try {
      const response = await serviceApi.list();
      if (sequence === serviceRequestSequenceRef.current) {
        setServiceState({ kind: "ready", items: response });
      }
    } catch (error) {
      if (sequence !== serviceRequestSequenceRef.current) return;
      if (isSessionExpired(error)) onSessionExpired();
      else setServiceState({ kind: "error" });
    }
  }, [onSessionExpired, serviceApi]);

  const loadMedia = useCallback(async () => {
    const sequence = ++mediaRequestSequenceRef.current;
    setMediaState({ kind: "loading" });
    try {
      const response = await mediaApi.list();
      if (sequence === mediaRequestSequenceRef.current) {
        setMediaState({ kind: "ready", items: response });
      }
    } catch (error) {
      if (sequence !== mediaRequestSequenceRef.current) return;
      if (isSessionExpired(error)) onSessionExpired();
      else setMediaState({ kind: "error" });
    }
  }, [mediaApi, onSessionExpired]);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => {
      if (!active) return;
      void loadGalleries("initial");
      void loadBreeds();
      void loadServices();
      void loadMedia();
    });
    return () => {
      active = false;
      listRequestSequenceRef.current += 1;
      breedRequestSequenceRef.current += 1;
      serviceRequestSequenceRef.current += 1;
      mediaRequestSequenceRef.current += 1;
    };
  }, [loadBreeds, loadGalleries, loadMedia, loadServices]);

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
    if (!target || !target.isConnected || target.disabled || target.tabIndex < 0) return;
    target.focus();
    if (document.activeElement === target) restoreFocusRef.current = null;
  }, [createOpen, editingId, listState, mutationPending]);

  useEffect(() => {
    if (pickerSlot !== null) {
      pickerInitialFocusRef.current?.focus();
      return;
    }
    const slot = restorePickerFocusRef.current;
    if (!slot) return;
    const target = pickerTriggerRefs.current.get(slot);
    if (target?.isConnected && !target.disabled) {
      target.focus();
      if (document.activeElement === target) restorePickerFocusRef.current = null;
    }
  }, [pickerSlot]);

  const catalogsReady =
    breedState.kind === "ready" &&
    serviceState.kind === "ready" &&
    mediaState.kind === "ready";
  const catalogHasError =
    breedState.kind === "error" ||
    serviceState.kind === "error" ||
    mediaState.kind === "error";
  const catalogsLoading = !catalogHasError && !catalogsReady;
  const controlsDisabled = mutationPending || listState === "refreshing";
  const interactionDisabled = controlsDisabled || listState !== "ready";

  function updateCurrentDraft(patch: Partial<GalleryDraft>) {
    setActionMessage(null);
    if (createOpen) setCreateDraft((current) => ({ ...current, ...patch }));
    else if (editingId) setEditDraft((current) => (current ? { ...current, ...patch } : current));
  }

  function resetPicker() {
    restorePickerFocusRef.current = null;
    setPickerSlot(null);
  }

  function openCreate() {
    if (!catalogsReady) return;
    setSuccessMessage(null);
    setActionMessage(null);
    setEditingId(null);
    setEditDraft(null);
    setCreateDraft(EMPTY_GALLERY_DRAFT);
    resetPicker();
    setCreateOpen(true);
  }

  function closeCreate() {
    restoreFocusRef.current = createTriggerRef.current;
    setActionMessage(null);
    resetPicker();
    setCreateOpen(false);
  }

  function openEdit(item: GalleryItem) {
    if (!catalogsReady) return;
    setSuccessMessage(null);
    setActionMessage(null);
    setCreateOpen(false);
    setEditingId(item.id);
    setEditDraft(galleryItemToDraft(item));
    resetPicker();
  }

  function closeEdit() {
    if (editingId) restoreFocusRef.current = editTriggerRefs.current.get(editingId) ?? null;
    setActionMessage(null);
    resetPicker();
    setEditingId(null);
    setEditDraft(null);
  }

  function openPicker(slot: PickerSlot) {
    restorePickerFocusRef.current = null;
    setPickerSlot(slot);
  }

  function closePicker(slot: PickerSlot) {
    restorePickerFocusRef.current = slot;
    setPickerSlot(null);
  }

  function selectMedia(slot: PickerSlot, id: string | null) {
    const key =
      slot === "cover"
        ? "coverImageId"
        : slot === "before"
          ? "beforeImageId"
          : "afterImageId";
    updateCurrentDraft({ [key]: id });
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mutationBusyRef.current || listState !== "ready" || !catalogsReady) return;
    const validationMessage = validateDraft(createDraft, breedState, serviceState, mediaState);
    const request = buildGalleryCreateRequest(createDraft);
    if (validationMessage || !request) {
      setActionMessage(validationMessage ?? "입력값을 확인해 주세요.");
      return;
    }
    mutationBusyRef.current = true;
    listRequestSequenceRef.current += 1;
    setMutationPending(true);
    setActionMessage(null);
    resetPicker();
    try {
      const created = await galleryApi.create(request);
      setItems((current) => applyContentMutationResult(current, created));
      setSuccessMessage("갤러리 항목을 생성했습니다.");
      restoreFocusRef.current = createTriggerRef.current;
      setCreateOpen(false);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadGalleries("post-mutation");
    } catch (error) {
      if (isSessionExpired(error)) onSessionExpired();
      else setActionMessage(mutationErrorMessage(error, "create"));
    } finally {
      mutationBusyRef.current = false;
      setMutationPending(false);
    }
  }

  async function handleUpdate(event: FormEvent<HTMLFormElement>, item: GalleryItem) {
    event.preventDefault();
    if (
      mutationBusyRef.current ||
      listState !== "ready" ||
      !catalogsReady ||
      !editDraft
    ) {
      return;
    }
    const validationMessage = validateDraft(editDraft, breedState, serviceState, mediaState);
    const request = buildGalleryUpdateRequest(editDraft);
    if (validationMessage || !request) {
      setActionMessage(validationMessage ?? "입력값을 확인해 주세요.");
      return;
    }
    mutationBusyRef.current = true;
    listRequestSequenceRef.current += 1;
    setMutationPending(true);
    setActionMessage(null);
    resetPicker();
    try {
      const updated = await galleryApi.update(item.id, request);
      setItems((current) => applyContentMutationResult(current, updated));
      setSuccessMessage("갤러리 항목을 수정했습니다.");
      restoreFocusRef.current = editTriggerRefs.current.get(item.id) ?? null;
      setEditingId(null);
      setEditDraft(null);
      mutationBusyRef.current = false;
      setMutationPending(false);
      await loadGalleries("post-mutation");
    } catch (error) {
      if (isSessionExpired(error)) onSessionExpired();
      else setActionMessage(mutationErrorMessage(error, "update"));
    } finally {
      mutationBusyRef.current = false;
      setMutationPending(false);
    }
  }

  function renderPicker(slot: PickerSlot, label: string, selectedId: string | null) {
    if (pickerSlot !== slot) return null;
    return (
      <AdminMediaPicker
        api={mediaApi}
        id={`gallery-${createOpen ? "create" : editingId}-${slot}-picker`}
        slotLabel={label}
        state={mediaState}
        selectedId={selectedId}
        disabled={mutationPending}
        selectionPolicy="all-existing"
        initialFocusRef={pickerInitialFocusRef}
        onSelect={(id) => selectMedia(slot, id)}
        onRetry={() => void loadMedia()}
        onClose={() => closePicker(slot)}
        onSessionExpired={onSessionExpired}
      />
    );
  }

  function renderFormFields(draft: GalleryDraft, mode: "create" | "edit") {
    const prefix = `gallery-${mode}`;
    const publishedTarget = mode === "edit" && draft.status === "published";
    const mediaFor = (id: string | null) =>
      mediaState.kind === "ready"
        ? mediaState.items.find((item) => item.id === id) ?? null
        : null;
    const triggerRef = (slot: PickerSlot) => (element: HTMLButtonElement | null) => {
      if (element) pickerTriggerRefs.current.set(slot, element);
      else pickerTriggerRefs.current.delete(slot);
    };
    return (
      <>
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
            반려견 이름
            <input
              ref={mode === "create" ? createNameRef : editNameRef}
              value={draft.dogName}
              onChange={(event) => updateCurrentDraft({ dogName: event.target.value })}
            />
          </label>
          <div className={contentStyles.field}>
            <label htmlFor={`${prefix}-breed`}>견종</label>
            <select
              id={`${prefix}-breed`}
              value={draft.breedId ?? ""}
              onChange={(event) =>
                updateCurrentDraft({ breedId: event.target.value || null })
              }
            >
              <option value="">선택 안 함</option>
              {breedState.kind === "ready"
                ? breedState.items.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name} · {STATUS_LABELS[item.status]}
                    </option>
                  ))
                : null}
            </select>
            {publishedTarget &&
            resolveItem(breedState, draft.breedId)?.status !== "published" ? (
              <span className={styles.invalidHelp}>
                게시하려면 게시됨 견종으로 교체해 주세요.
              </span>
            ) : null}
          </div>
          <div className={contentStyles.field}>
            <label htmlFor={`${prefix}-service`}>대표 서비스</label>
            <select
              id={`${prefix}-service`}
              value={draft.primaryServiceId ?? ""}
              onChange={(event) =>
                updateCurrentDraft({ primaryServiceId: event.target.value || null })
              }
            >
              <option value="">선택 안 함</option>
              {serviceState.kind === "ready"
                ? serviceState.items.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name} · {STATUS_LABELS[item.status]}
                    </option>
                  ))
                : null}
            </select>
            {publishedTarget &&
            resolveItem(serviceState, draft.primaryServiceId)?.status !== "published" ? (
              <span className={styles.invalidHelp}>
                게시하려면 게시됨 서비스로 교체해 주세요.
              </span>
            ) : null}
          </div>
          <label className={styles.checkboxField}>
            <input
              type="checkbox"
              checked={draft.featured}
              onChange={(event) => updateCurrentDraft({ featured: event.target.checked })}
            />
            대표 항목으로 표시
          </label>
          <label className={contentStyles.field}>
            정렬 순서
            <input
              type="number"
              min="0"
              step="1"
              placeholder={mode === "create" ? "기본값 100" : undefined}
              value={draft.sortOrder}
              onChange={(event) => updateCurrentDraft({ sortOrder: event.target.value })}
            />
          </label>
          <label className={contentStyles.wideField}>
            요약
            <textarea
              value={draft.summary}
              onChange={(event) => updateCurrentDraft({ summary: event.target.value })}
            />
          </label>
          <div className={contentStyles.wideField}>
            <label htmlFor={`${prefix}-alt`}>대체텍스트</label>
            <textarea
              id={`${prefix}-alt`}
              value={draft.altText}
              aria-describedby={`${prefix}-alt-help`}
              onChange={(event) => updateCurrentDraft({ altText: event.target.value })}
            />
            <span id={`${prefix}-alt-help`} className={contentStyles.help}>
              게시 상태에서는 대표 이미지의 의미를 설명하는 대체텍스트가 필요합니다.
            </span>
          </div>
          <label className={contentStyles.field}>
            시술 시각
            <input
              type="datetime-local"
              step="0.000001"
              value={draft.performedAt}
              onChange={(event) => updateCurrentDraft({ performedAt: event.target.value })}
            />
          </label>
          <label className={contentStyles.field}>
            게시 시각
            <input
              type="datetime-local"
              step="0.000001"
              value={draft.publishedAt}
              onChange={(event) => updateCurrentDraft({ publishedAt: event.target.value })}
            />
          </label>
        </div>

        <div className={styles.relationGrid}>
          {(
            [
              ["cover", "대표 이미지", draft.coverImageId],
              ["before", "시술 전 이미지", draft.beforeImageId],
              ["after", "시술 후 이미지", draft.afterImageId],
            ] as const
          ).map(([slot, label, selectedId]) => (
            <MediaRelationControl
              key={slot}
              id={`${prefix}-${slot}`}
              label={label}
              item={mediaFor(selectedId)}
              api={mediaApi}
              selectedId={selectedId}
              state={mediaState}
              publishedTarget={publishedTarget}
              disabled={mutationPending}
              triggerRef={triggerRef(slot)}
              onOpen={() => openPicker(slot)}
              onClear={() => selectMedia(slot, null)}
              onSessionExpired={onSessionExpired}
            >
              {renderPicker(slot, label, selectedId)}
            </MediaRelationControl>
          ))}
        </div>
      </>
    );
  }

  return (
    <section className={contentStyles.manager} aria-labelledby="gallery-title">
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
          onClick={() => void loadGalleries("refresh")}
        >
          {listState === "refreshing" ? "새로고침 중" : "새로고침"}
        </button>
      </div>

      <header className={contentStyles.header}>
        <p>Gallery content relations</p>
        <h2 id="gallery-title">갤러리 관리</h2>
        <span>초안 관계는 보존하고 게시 시점의 공개 가능 상태를 확인합니다.</span>
      </header>

      <div className={contentStyles.headerRow}>
        <div>
          <h3>시술 갤러리 항목</h3>
          <p className={contentStyles.help}>
            서버가 정한 대표·정렬·게시 시각·식별자 순서를 그대로 표시합니다.
          </p>
        </div>
        <button
          ref={createTriggerRef}
          className={contentStyles.primaryButton}
          type="button"
          disabled={interactionDisabled || !catalogsReady || createOpen || editingId !== null}
          onClick={openCreate}
        >
          새 갤러리 항목
        </button>
      </div>

      {catalogHasError ? (
        <div className={styles.catalogPanel} role="alert">
          <p>
            관계 목록 일부를 불러오지 못했습니다. 갤러리 목록은 유지되지만 편집은 관계
            목록을 복구한 뒤 가능합니다.
          </p>
          <button
            type="button"
            disabled={mutationPending}
            onClick={() => {
              void loadBreeds();
              void loadServices();
              void loadMedia();
            }}
          >
            관계 목록 다시 시도
          </button>
        </div>
      ) : null}
      {catalogsLoading ? (
        <div className={styles.catalogPanel} role="status" aria-live="polite">
          <p>견종·서비스·미디어 관계 목록을 불러오고 있습니다.</p>
        </div>
      ) : null}

      {successMessage ? (
        <p className={contentStyles.success} role="status">
          {successMessage}
        </p>
      ) : null}

      {createOpen ? (
        <section className={contentStyles.createPanel} aria-labelledby="gallery-create-title">
          <div className={contentStyles.headerRow}>
            <div>
              <h3 id="gallery-create-title">새 갤러리 항목</h3>
              <p className={contentStyles.help}>새 항목은 status field 없이 항상 초안으로 생성됩니다.</p>
            </div>
          </div>
          <form
            className={contentStyles.form}
            noValidate
            onSubmit={(event) => void handleCreate(event)}
          >
            {renderFormFields(createDraft, "create")}
            {actionMessage ? (
              <p className={contentStyles.alert} role="alert">
                {actionMessage}
              </p>
            ) : null}
            <div className={contentStyles.formActions}>
              <button
                className={contentStyles.secondaryButton}
                type="button"
                disabled={interactionDisabled}
                onClick={closeCreate}
              >
                생성 취소
              </button>
              <button
                className={contentStyles.primaryButton}
                type="submit"
                disabled={mutationPending}
              >
                {mutationPending ? "생성 중" : "갤러리 항목 생성"}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {listState === "loading" ? (
        <p className={contentStyles.statePanel} role="status" aria-live="polite">
          갤러리 목록을 불러오고 있습니다.
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
            onClick={() => void loadGalleries("initial")}
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
      {listState !== "loading" && listState !== "error" && items.length === 0 ? (
        <p className={contentStyles.emptyState}>
          등록된 갤러리 항목이 없습니다. 새 항목을 초안으로 만들어 주세요.
        </p>
      ) : null}

      {items.length > 0 ? (
        <ul
          className={contentStyles.contentList}
          aria-label="갤러리 목록"
          aria-busy={listState === "refreshing"}
        >
          {items.map((item) => {
            const cover =
              mediaState.kind === "ready"
                ? mediaState.items.find((candidate) => candidate.id === item.coverImageId) ?? null
                : null;
            const displayName = item.dogName ?? "이름 없음";
            return (
              <li key={item.id}>
                {cover ? (
                  <div className={styles.cardPreview}>
                    <AdminMediaPreview
                      api={mediaApi}
                      item={cover}
                      alt={`${displayName} 대표 이미지 미리보기`}
                      onSessionExpired={onSessionExpired}
                    />
                  </div>
                ) : (
                  <p className={styles.previewFallback}>대표 이미지 없음</p>
                )}
                <div className={contentStyles.cardHeading}>
                  <div>
                    <h3>{displayName}</h3>
                    <p>{item.summary ?? "요약 없음"}</p>
                  </div>
                  <button
                    ref={(element) => {
                      if (element) editTriggerRefs.current.set(item.id, element);
                      else editTriggerRefs.current.delete(item.id);
                    }}
                    className={contentStyles.editButton}
                    type="button"
                    aria-label={`${displayName} 수정`}
                    disabled={
                      interactionDisabled ||
                      !catalogsReady ||
                      createOpen ||
                      (editingId !== null && editingId !== item.id)
                    }
                    onClick={() => openEdit(item)}
                  >
                    수정
                  </button>
                </div>
                <div className={contentStyles.metadataRow}>
                  <strong className={contentStyles.badge}>{STATUS_LABELS[item.status]}</strong>
                  <span>{item.featured ? "대표 항목" : "일반 항목"}</span>
                  <span>정렬 {item.sortOrder}</span>
                  <span>시술 {item.performedAt ? new Date(item.performedAt).toLocaleString() : "미입력"}</span>
                  <span>게시 {item.publishedAt ? new Date(item.publishedAt).toLocaleString() : "미입력"}</span>
                  <span>수정 {new Date(item.updatedAt).toLocaleString()}</span>
                </div>
                <div className={styles.relationSummary}>
                  <span>{relationCardText("견종", breedState, item.breedId)}</span>
                  <span>{relationCardText("서비스", serviceState, item.primaryServiceId)}</span>
                </div>

                {editingId === item.id && editDraft ? (
                  <section className={contentStyles.editPanel} aria-label={`${displayName} 수정 form`}>
                    <form
                      className={contentStyles.form}
                      noValidate
                      onSubmit={(event) => void handleUpdate(event, item)}
                    >
                      {renderFormFields(editDraft, "edit")}
                      {actionMessage ? (
                        <p className={contentStyles.alert} role="alert">
                          {actionMessage}
                        </p>
                      ) : null}
                      <p className={contentStyles.help}>
                        게시 검증은 화면에서 보조하며 backend가 최종 authority입니다. 미래 게시 시각도
                        허용합니다.
                      </p>
                      <div className={contentStyles.formActions}>
                        <button
                          className={contentStyles.secondaryButton}
                          type="button"
                          disabled={interactionDisabled}
                          onClick={closeEdit}
                        >
                          수정 취소
                        </button>
                        <button
                          className={contentStyles.primaryButton}
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
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}
