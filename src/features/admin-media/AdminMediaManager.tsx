"use client";

import {
  ChangeEvent,
  FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import { isAdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import { AdminMediaApi } from "./api";
import type { MediaItem, MediaStatus } from "./types";
import styles from "./AdminMediaManager.module.css";

const MAX_SOURCE_BYTES = 20 * 1024 * 1024;
const FILE_ACCEPT =
  "image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif";

type MediaFilter = "all" | MediaStatus;
type ListState = "loading" | "ready" | "error" | "refreshing";

type AdminMediaManagerProps = Readonly<{
  transport: AdminApiTransport;
  onBack: () => void;
  onSessionExpired: () => void;
}>;

type MediaPreviewProps = Readonly<{
  api: AdminMediaApi;
  item: MediaItem;
  position: number;
  onSessionExpired: () => void;
}>;

function formatByteSize(value: number): string {
  if (value >= 1024 * 1024) {
    return `${(value / (1024 * 1024)).toFixed(1)} MiB`;
  }
  return `${Math.max(0.1, value / 1024).toFixed(1)} KiB`;
}

function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function insertByServerOrdering(
  current: readonly MediaItem[],
  created: MediaItem,
): readonly MediaItem[] {
  return [created, ...current.filter((item) => item.id !== created.id)].sort(
    (left, right) =>
      right.createdAt.localeCompare(left.createdAt) || left.id.localeCompare(right.id),
  );
}

function isSessionExpired(error: unknown): boolean {
  return isAdminApiError(error) && error.kind === "session-expired";
}

function uploadErrorMessage(error: unknown): string {
  if (isAdminApiError(error)) {
    if (error.kind === "invalid-request") {
      return "파일 요청 형식을 확인해 주세요.";
    }
    if (error.kind === "too-large") {
      return "파일은 20 MiB 이하만 업로드할 수 있습니다.";
    }
    if (error.kind === "type-unsupported") {
      return "JPEG, PNG, HEIC 또는 HEIF 파일을 선택해 주세요.";
    }
    if (error.kind === "invalid-image") {
      return "손상됐거나 지원하지 않는 이미지입니다.";
    }
    if (error.kind === "processor-unavailable") {
      return "이미지 처리기를 일시적으로 사용할 수 없습니다.";
    }
    if (error.kind === "forbidden") {
      return "보안 요청을 확인할 수 없습니다. 다시 시도해 주세요.";
    }
  }
  return "업로드하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.";
}

function statusErrorMessage(error: unknown): string {
  if (isAdminApiError(error)) {
    if (error.kind === "not-found") {
      return "미디어 상태가 달라졌습니다. 목록을 새로고침해 주세요.";
    }
    if (error.kind === "forbidden") {
      return "보안 요청을 확인할 수 없습니다. 다시 시도해 주세요.";
    }
  }
  return "상태를 변경하지 못했습니다. 다시 시도해 주세요.";
}

function MediaPreview({
  api,
  item,
  position,
  onSessionExpired,
}: MediaPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [shouldLoad, setShouldLoad] = useState(false);
  const [preview, setPreview] = useState<
    | Readonly<{ kind: "waiting" | "error" }>
    | Readonly<{ kind: "ready"; url: string }>
  >({ kind: "waiting" });

  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof IntersectionObserver === "undefined") {
      setShouldLoad(true);
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setShouldLoad(true);
          observer.disconnect();
        }
      },
      { rootMargin: "160px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!shouldLoad) {
      return;
    }

    let active = true;
    let objectUrl: string | null = null;

    void api.content(item.id).then(
      (blob) => {
        if (!active) {
          return;
        }
        try {
          objectUrl = URL.createObjectURL(blob);
          setPreview({ kind: "ready", url: objectUrl });
        } catch {
          setPreview({ kind: "error" });
        }
      },
      (error: unknown) => {
        if (!active) {
          return;
        }
        if (isSessionExpired(error)) {
          onSessionExpired();
          return;
        }
        setPreview({ kind: "error" });
      },
    );

    return () => {
      active = false;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [api, item.id, onSessionExpired, shouldLoad]);

  const stateLabel = item.status === "active" ? "활성" : "보관됨";
  const alt = `미디어 미리보기, ${stateLabel}, ${position}번`;
  const previewLoading = shouldLoad && preview.kind === "waiting";

  return (
    <div className={styles.preview} ref={containerRef}>
      {preview.kind === "ready" ? (
        // private authenticated Blob URL은 Next image optimizer를 거치지 않는다.
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={preview.url}
          alt={alt}
          width={item.width}
          height={item.height}
          onError={() => setPreview({ kind: "error" })}
        />
      ) : (
        <p role={previewLoading ? "status" : undefined}>
          {preview.kind === "waiting" && !previewLoading
            ? "미리보기 대기 중"
            : previewLoading
              ? "미리보기 불러오는 중"
              : "미리보기를 불러오지 못했습니다."}
        </p>
      )}
    </div>
  );
}

export function AdminMediaManager({
  transport,
  onBack,
  onSessionExpired,
}: AdminMediaManagerProps) {
  const api = useMemo(() => new AdminMediaApi(transport), [transport]);
  const [items, setItems] = useState<readonly MediaItem[]>([]);
  const [listState, setListState] = useState<ListState>("loading");
  const [listMessage, setListMessage] = useState<string | null>(null);
  const [filter, setFilter] = useState<MediaFilter>("all");
  const [previewGeneration, setPreviewGeneration] = useState(0);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadPending, setUploadPending] = useState(false);
  const [uploadMessage, setUploadMessage] = useState<string | null>(null);
  const [pendingIds, setPendingIds] = useState<ReadonlySet<string>>(new Set());
  const [itemMessages, setItemMessages] = useState<Readonly<Record<string, string>>>({});
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploadBusyRef = useRef(false);
  const pendingIdsRef = useRef(new Set<string>());
  const requestSequenceRef = useRef(0);

  const loadMedia = useCallback(
    async (mode: "initial" | "refresh") => {
      const requestSequence = ++requestSequenceRef.current;
      setListMessage(null);
      setListState(mode === "initial" ? "loading" : "refreshing");
      try {
        const nextItems = await api.list();
        if (requestSequence !== requestSequenceRef.current) {
          return;
        }
        setItems(nextItems);
        setPreviewGeneration((value) => value + 1);
        setListState("ready");
      } catch (error) {
        if (requestSequence !== requestSequenceRef.current) {
          return;
        }
        if (isSessionExpired(error)) {
          onSessionExpired();
          return;
        }
        setListMessage("미디어 목록을 불러오지 못했습니다. 연결을 확인해 주세요.");
        setListState(mode === "initial" ? "error" : "ready");
      }
    },
    [api, onSessionExpired],
  );

  useEffect(() => {
    void loadMedia("initial");
    return () => {
      requestSequenceRef.current += 1;
    };
  }, [loadMedia]);

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    setSelectedFile(event.target.files?.item(0) ?? null);
    setUploadMessage(null);
  }

  function clearSelectedFile() {
    setSelectedFile(null);
    setUploadMessage(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  }

  async function handleUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (
      !selectedFile ||
      uploadBusyRef.current ||
      listState === "loading" ||
      listState === "refreshing"
    ) {
      return;
    }
    if (selectedFile.size > MAX_SOURCE_BYTES) {
      setUploadMessage("파일은 20 MiB 이하만 업로드할 수 있습니다.");
      return;
    }

    uploadBusyRef.current = true;
    setUploadPending(true);
    setUploadMessage(null);
    try {
      const created = await api.upload(selectedFile);
      setItems((current) => insertByServerOrdering(current, created));
      setListState("ready");
      setPreviewGeneration((value) => value + 1);
      clearSelectedFile();
    } catch (error) {
      if (isSessionExpired(error)) {
        onSessionExpired();
      } else {
        setUploadMessage(uploadErrorMessage(error));
      }
    } finally {
      uploadBusyRef.current = false;
      setUploadPending(false);
    }
  }

  async function handleStatusChange(item: MediaItem) {
    if (pendingIdsRef.current.has(item.id)) {
      return;
    }
    pendingIdsRef.current.add(item.id);
    setPendingIds(new Set(pendingIdsRef.current));
    setItemMessages((current) => {
      const next = { ...current };
      delete next[item.id];
      return next;
    });

    const nextStatus: MediaStatus = item.status === "active" ? "archived" : "active";
    try {
      const updated = await api.updateStatus(item.id, nextStatus);
      setItems((current) =>
        current.map((currentItem) =>
          currentItem.id === updated.id ? updated : currentItem,
        ),
      );
      setPreviewGeneration((value) => value + 1);
    } catch (error) {
      if (isSessionExpired(error)) {
        onSessionExpired();
      } else {
        setItemMessages((current) => ({
          ...current,
          [item.id]: statusErrorMessage(error),
        }));
      }
    } finally {
      pendingIdsRef.current.delete(item.id);
      setPendingIds(new Set(pendingIdsRef.current));
    }
  }

  const visibleItems = items.filter((item) => filter === "all" || item.status === filter);

  return (
    <section className={styles.manager} aria-labelledby="media-title">
      <div className={styles.topBar}>
        <button className={styles.backButton} type="button" onClick={onBack}>
          관리 홈으로
        </button>
        <button
          className={styles.refreshButton}
          type="button"
          disabled={
            listState === "loading" ||
            listState === "refreshing" ||
            uploadPending ||
            pendingIds.size > 0
          }
          onClick={() => void loadMedia("refresh")}
        >
          {listState === "refreshing" ? "새로고침 중" : "새로고침"}
        </button>
      </div>

      <header className={styles.header}>
        <p>Private media library</p>
        <h2 id="media-title">미디어 관리</h2>
        <span>원본 파일은 관리자 session을 통해서만 조회됩니다.</span>
      </header>

      <form className={styles.upload} onSubmit={handleUpload} aria-busy={uploadPending}>
        <div className={styles.uploadHeading}>
          <div>
            <h3>새 미디어 업로드</h3>
            <p>JPEG, PNG, HEIC, HEIF · 파일 1개 · 최대 20 MiB</p>
          </div>
        </div>
        <label className={styles.filePicker}>
          <span>파일 선택</span>
          <input
            ref={fileInputRef}
            className={styles.fileInput}
            id="admin-media-file"
            name="file"
            type="file"
            accept={FILE_ACCEPT}
            disabled={uploadPending}
            onChange={handleFileChange}
          />
        </label>

        {selectedFile ? (
          <div className={styles.selectedFile}>
            <div>
              <strong>{selectedFile.name}</strong>
              <span>{formatByteSize(selectedFile.size)}</span>
            </div>
            <button type="button" disabled={uploadPending} onClick={clearSelectedFile}>
              선택 취소
            </button>
          </div>
        ) : (
          <p className={styles.fileHint}>업로드할 파일을 하나 선택해 주세요.</p>
        )}

        {uploadMessage ? (
          <p className={styles.alert} role="alert">
            {uploadMessage}
          </p>
        ) : null}

        <button
          className={styles.uploadButton}
          type="submit"
          disabled={
            !selectedFile ||
            uploadPending ||
            listState === "loading" ||
            listState === "refreshing"
          }
        >
          {uploadPending ? "업로드 중" : "업로드"}
        </button>
      </form>

      <div className={styles.libraryHeading}>
        <div>
          <h3>미디어 목록</h3>
          <span>{items.length}개</span>
        </div>
        <div className={styles.filters} role="group" aria-label="미디어 상태 필터">
          {(["all", "active", "archived"] as const).map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={filter === value}
              onClick={() => setFilter(value)}
            >
              {value === "all" ? "전체" : value === "active" ? "활성" : "보관"}
            </button>
          ))}
        </div>
      </div>

      {listMessage ? (
        <div className={styles.listAlert} role="alert">
          <p>{listMessage}</p>
          <button type="button" onClick={() => void loadMedia("refresh")}>
            다시 시도
          </button>
        </div>
      ) : null}

      {listState === "loading" ? (
        <div className={styles.listState} role="status" aria-live="polite">
          미디어 목록을 불러오고 있습니다.
        </div>
      ) : null}

      {listState !== "loading" && listState !== "error" && visibleItems.length === 0 ? (
        <div className={styles.listState}>
          {items.length === 0
            ? "업로드된 미디어가 없습니다. 위에서 첫 파일을 선택해 주세요."
            : "선택한 상태의 미디어가 없습니다."}
        </div>
      ) : null}

      {visibleItems.length > 0 ? (
        <ul className={styles.mediaGrid} aria-busy={listState === "refreshing"}>
          {visibleItems.map((item, index) => {
            const pending = pendingIds.has(item.id);
            return (
              <li key={`${item.id}:${item.updatedAt}:${previewGeneration}`}>
                <MediaPreview
                  api={api}
                  item={item}
                  position={index + 1}
                  onSessionExpired={onSessionExpired}
                />
                <div className={styles.cardBody}>
                  <div className={styles.statusRow}>
                    <strong>{item.status === "active" ? "활성" : "보관됨"}</strong>
                    <span>{item.contentType}</span>
                  </div>
                  <dl className={styles.metadata}>
                    <div>
                      <dt>원본 형식</dt>
                      <dd>{item.sourceContentType}</dd>
                    </div>
                    <div>
                      <dt>크기</dt>
                      <dd>
                        {item.width.toLocaleString()} × {item.height.toLocaleString()}
                      </dd>
                    </div>
                    <div>
                      <dt>저장 용량</dt>
                      <dd>{formatByteSize(item.byteSize)}</dd>
                    </div>
                    <div>
                      <dt>업로드</dt>
                      <dd>{formatTimestamp(item.createdAt)}</dd>
                    </div>
                  </dl>
                  {itemMessages[item.id] ? (
                    <p className={styles.cardAlert} role="alert">
                      {itemMessages[item.id]}
                    </p>
                  ) : null}
                  <button
                    className={styles.statusButton}
                    type="button"
                    disabled={pending || listState === "refreshing"}
                    aria-label={
                      pending
                        ? "미디어 상태 변경 중"
                        : item.status === "active"
                          ? "미디어 보관"
                          : "미디어 복구"
                    }
                    onClick={() => void handleStatusChange(item)}
                  >
                    {pending
                      ? "변경 중"
                      : item.status === "active"
                        ? "보관"
                        : "복구"}
                  </button>
                  <p className={styles.archiveHelp}>
                    {item.status === "active"
                      ? "보관은 파일을 삭제하지 않으며 다시 복구할 수 있습니다."
                      : "보관된 파일도 관리자 미리보기는 유지됩니다."}
                  </p>
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}
