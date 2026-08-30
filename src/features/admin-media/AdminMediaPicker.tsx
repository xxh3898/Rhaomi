"use client";

import type { Ref } from "react";

import { AdminMediaPreview } from "./AdminMediaPreview";
import { AdminMediaApi } from "./api";
import type { MediaItem } from "./types";
import styles from "./AdminMediaPicker.module.css";

export type AdminMediaPickerState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{ kind: "error" }>
  | Readonly<{ kind: "ready"; items: readonly MediaItem[] }>;

export type AdminMediaSelectionPolicy = "active-only" | "all-existing";

type AdminMediaPickerProps = Readonly<{
  api: AdminMediaApi;
  id: string;
  slotLabel: string;
  state: AdminMediaPickerState;
  selectedId: string | null;
  disabled: boolean;
  selectionPolicy?: AdminMediaSelectionPolicy;
  initialFocusRef?: Ref<HTMLButtonElement>;
  onSelect: (id: string | null) => void;
  onRetry: () => void;
  onClose: () => void;
  onSessionExpired: () => void;
}>;

export function AdminMediaPicker({
  api,
  id,
  slotLabel,
  state,
  selectedId,
  disabled,
  selectionPolicy = "active-only",
  initialFocusRef,
  onSelect,
  onRetry,
  onClose,
  onSessionExpired,
}: AdminMediaPickerProps) {
  const titleId = `${id}-title`;
  const selectableItems =
    state.kind === "ready"
      ? state.items.filter(
          (item) => selectionPolicy === "all-existing" || item.status === "active",
        )
      : [];

  function select(idValue: string | null) {
    if (disabled) {
      return;
    }
    onSelect(idValue);
    onClose();
  }

  return (
    <section className={styles.picker} aria-labelledby={titleId}>
      <div className={styles.heading}>
        <div>
          <p>Private media library</p>
          <h4 id={titleId}>{slotLabel} 선택</h4>
        </div>
        <button
          ref={initialFocusRef}
          type="button"
          disabled={disabled}
          onClick={onClose}
        >
          선택 닫기
        </button>
      </div>

      <p className={styles.help}>
        {selectionPolicy === "active-only"
          ? "활성 미디어만 새 관계로 선택할 수 있습니다. 새 파일은 미디어 관리에서 먼저 업로드해 주세요."
          : "활성·보관 미디어를 관계로 선택할 수 있습니다. 게시 전에는 활성 미디어로 교체해 주세요."}
      </p>

      {state.kind === "loading" ? (
        <p className={styles.state} role="status" aria-live="polite">
          미디어 목록을 불러오고 있습니다.
        </p>
      ) : null}

      {state.kind === "error" ? (
        <div className={styles.error} role="alert">
          <p>미디어 목록을 불러오지 못했습니다.</p>
          <button type="button" disabled={disabled} onClick={onRetry}>
            다시 시도
          </button>
        </div>
      ) : null}

      {state.kind === "ready" ? (
        <>
          <button
            className={styles.clearButton}
            type="button"
            disabled={disabled}
            aria-pressed={selectedId === null}
            onClick={() => select(null)}
          >
            없음 — 현재 이미지 관계 해제
          </button>

          {selectableItems.length === 0 ? (
            <p className={styles.state}>
              {selectionPolicy === "active-only"
                ? "선택할 수 있는 활성 미디어가 없습니다. 미디어 관리에서 먼저 업로드하거나 복구해 주세요."
                : "선택할 수 있는 미디어가 없습니다. 미디어 관리에서 먼저 업로드해 주세요."}
            </p>
          ) : (
            <ul className={styles.grid}>
              {selectableItems.map((item, index) => (
                <li key={item.id}>
                  <AdminMediaPreview
                    api={api}
                    item={item}
                    alt={`${slotLabel} 선택 후보 ${index + 1}번`}
                    onSessionExpired={onSessionExpired}
                  />
                  <div className={styles.cardBody}>
                    <span>
                      {item.status === "active" ? "활성" : "보관됨"} · {item.contentType}
                    </span>
                    <code>{item.id}</code>
                    <button
                      type="button"
                      disabled={disabled}
                      aria-pressed={selectedId === item.id}
                      onClick={() => select(item.id)}
                    >
                      {selectedId === item.id ? "현재 선택됨" : "이 미디어 선택"}
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}
    </section>
  );
}
