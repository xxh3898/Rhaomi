"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, Ref } from "react";

import { isAdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";
import {
  AdminMediaPicker,
  type AdminMediaPickerState,
} from "@/features/admin-media/AdminMediaPicker";
import { AdminMediaApi } from "@/features/admin-media/api";

import { AdminShopSettingsApi } from "./api";
import {
  buildShopSettingsRequest,
  codePointLength,
  EMPTY_SHOP_SETTINGS_DRAFT,
  shopSettingsResponseToDraft,
  SHOP_WEEKDAYS,
  type ShopSettingsDraft,
  type ShopWeekday,
} from "./types";
import styles from "./AdminShopSettingsManager.module.css";

type ShopState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{ kind: "error" }>
  | Readonly<{ kind: "uninitialized"; draft: ShopSettingsDraft }>
  | Readonly<{ kind: "ready"; draft: ShopSettingsDraft }>
  | Readonly<{
      kind: "saving";
      draft: ShopSettingsDraft;
      previousKind: "uninitialized" | "ready";
    }>;

type PickerSlot = "hero" | "groomer" | "og";
type RelationState =
  | "none"
  | "loading"
  | "unavailable"
  | "active"
  | "archived"
  | "missing";

type AdminShopSettingsManagerProps = Readonly<{
  transport: AdminApiTransport;
  onBack: () => void;
  onSessionExpired: () => void;
}>;

type FormInputProps = Readonly<{
  id: string;
  label: string;
  value: string;
  required?: boolean;
  type?: "text" | "tel" | "time" | "url";
  autoComplete?: string;
  inputMode?: "tel" | "url";
  help?: string;
  onChange: (value: string) => void;
}>;

type FormTextareaProps = Readonly<{
  id: string;
  label: string;
  value: string;
  rows: number;
  help?: string;
  onChange: (value: string) => void;
}>;

type MediaRelationControlProps = Readonly<{
  id: string;
  label: string;
  selectedId: string | null;
  relationState: RelationState;
  disabled: boolean;
  altValue?: string;
  onAltChange?: (value: string) => void;
  triggerRef: Ref<HTMLButtonElement>;
  onOpen: () => void;
  onClear: () => void;
}>;

const WEEKDAY_LABELS: Readonly<Record<ShopWeekday, string>> = {
  MONDAY: "월요일",
  TUESDAY: "화요일",
  WEDNESDAY: "수요일",
  THURSDAY: "목요일",
  FRIDAY: "금요일",
  SATURDAY: "토요일",
  SUNDAY: "일요일",
};

const REQUIRED_TEXT_FIELDS = [
  "shopName",
  "regionLabel",
  "businessType",
  "phone",
  "address",
  "openingTime",
  "closingTime",
] as const satisfies readonly (keyof ShopSettingsDraft)[];

function isSessionExpired(error: unknown): boolean {
  return isAdminApiError(error) && error.kind === "session-expired";
}

function saveErrorMessage(error: unknown): string {
  if (isAdminApiError(error)) {
    if (error.kind === "invalid-request") {
      return "입력 형식을 확인해 주세요.";
    }
    if (error.kind === "business-hours-invalid") {
      return "영업 종료 시간은 시작 시간보다 늦어야 합니다.";
    }
    if (error.kind === "shop-media-relation-invalid") {
      return "Hero·미용사 이미지 설명과 선택한 미디어의 활성 상태를 확인해 주세요.";
    }
    if (error.kind === "forbidden") {
      return "보안 요청을 확인할 수 없습니다. 다시 저장해 주세요.";
    }
  }
  return "매장정보를 저장하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.";
}

function relationState(
  selectedId: string | null,
  mediaState: AdminMediaPickerState,
): RelationState {
  if (selectedId === null) {
    return "none";
  }
  if (mediaState.kind === "loading") {
    return "loading";
  }
  if (mediaState.kind === "error") {
    return "unavailable";
  }
  const item = mediaState.items.find((candidate) => candidate.id === selectedId);
  if (!item) {
    return "missing";
  }
  return item.status;
}

function relationStatusText(state: RelationState): string {
  if (state === "none") {
    return "선택된 이미지 없음";
  }
  if (state === "loading") {
    return "선택된 미디어 상태 확인 중";
  }
  if (state === "unavailable") {
    return "선택된 미디어 상태 확인 불가";
  }
  if (state === "active") {
    return "활성 미디어 선택됨";
  }
  if (state === "archived") {
    return "보관된 미디어 — 저장하려면 제거하거나 활성 미디어로 교체해 주세요.";
  }
  return "목록에서 찾을 수 없는 미디어 — 저장하려면 제거하거나 교체해 주세요.";
}

function validateDraft(
  draft: ShopSettingsDraft,
  mediaState: AdminMediaPickerState,
): string | null {
  if (REQUIRED_TEXT_FIELDS.some((key) => String(draft[key]).trim().length === 0)) {
    return "필수 입력값을 모두 확인해 주세요.";
  }
  if (draft.parkingAvailable === null) {
    return "주차 가능 여부를 선택해 주세요.";
  }
  if (draft.heroImageId !== null && draft.heroImageAltText.trim().length === 0) {
    return "Hero 이미지의 대체텍스트를 입력해 주세요.";
  }
  if (
    draft.groomerImageId !== null &&
    draft.groomerImageAltText.trim().length === 0
  ) {
    return "미용사 이미지의 대체텍스트를 입력해 주세요.";
  }
  if (
    codePointLength(draft.heroImageAltText) > 300 ||
    codePointLength(draft.groomerImageAltText) > 300
  ) {
    return "이미지 대체텍스트는 300자 이하여야 합니다.";
  }
  if (mediaState.kind === "ready") {
    for (const selectedId of [
      draft.heroImageId,
      draft.groomerImageId,
      draft.ogImageId,
    ]) {
      if (selectedId === null) {
        continue;
      }
      const item = mediaState.items.find((candidate) => candidate.id === selectedId);
      if (!item || item.status !== "active") {
        return "보관됐거나 찾을 수 없는 미디어 관계를 제거하거나 교체해 주세요.";
      }
    }
  }
  return null;
}

function FormInput({
  id,
  label,
  value,
  required = false,
  type = "text",
  autoComplete,
  inputMode,
  help,
  onChange,
}: FormInputProps) {
  const helpId = help ? `${id}-help` : undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id}>
        {label} <span>{required ? "필수" : "선택"}</span>
      </label>
      <input
        id={id}
        name={id}
        type={type}
        value={value}
        required={required}
        autoComplete={autoComplete}
        inputMode={inputMode}
        step={type === "time" ? 60 : undefined}
        aria-describedby={helpId}
        onChange={(event) => onChange(event.target.value)}
      />
      {help ? (
        <p id={helpId} className={styles.help}>
          {help}
        </p>
      ) : null}
    </div>
  );
}

function FormTextarea({
  id,
  label,
  value,
  rows,
  help,
  onChange,
}: FormTextareaProps) {
  const helpId = help ? `${id}-help` : undefined;
  return (
    <div className={styles.field}>
      <label htmlFor={id}>
        {label} <span>선택</span>
      </label>
      <textarea
        id={id}
        name={id}
        value={value}
        rows={rows}
        aria-describedby={helpId}
        onChange={(event) => onChange(event.target.value)}
      />
      {help ? (
        <p id={helpId} className={styles.help}>
          {help}
        </p>
      ) : null}
    </div>
  );
}

function MediaRelationControl({
  id,
  label,
  selectedId,
  relationState: currentRelationState,
  disabled,
  altValue,
  onAltChange,
  triggerRef,
  onOpen,
  onClear,
}: MediaRelationControlProps) {
  const statusId = `${id}-status`;
  const invalid =
    currentRelationState === "archived" || currentRelationState === "missing";
  return (
    <fieldset className={styles.mediaRelation}>
      <legend>{label}</legend>
      <div className={styles.relationSummary}>
        <p id={statusId} role={invalid ? "alert" : undefined}>
          {relationStatusText(currentRelationState)}
        </p>
        {selectedId ? <code>{selectedId}</code> : null}
      </div>
      <div className={styles.relationActions}>
        <button
          id={`${id}-picker-trigger`}
          ref={triggerRef}
          type="button"
          disabled={disabled}
          aria-describedby={statusId}
          onClick={onOpen}
        >
          미디어 선택
        </button>
        {selectedId ? (
          <button type="button" disabled={disabled} onClick={onClear}>
            선택 해제
          </button>
        ) : null}
      </div>
      {selectedId !== null && altValue !== undefined && onAltChange ? (
        <div className={styles.field}>
          <label htmlFor={`${id}-alt`}>
            대체텍스트 <span>필수</span>
          </label>
          <input
            id={`${id}-alt`}
            name={`${id}AltText`}
            value={altValue}
            required
            aria-describedby={`${id}-alt-help`}
            onChange={(event) => onAltChange(event.target.value)}
          />
          <p id={`${id}-alt-help`} className={styles.help}>
            향후 공개 이미지가 전달하는 의미를 300자 이하로 설명해 주세요.
          </p>
        </div>
      ) : null}
    </fieldset>
  );
}

export function AdminShopSettingsManager({
  transport,
  onBack,
  onSessionExpired,
}: AdminShopSettingsManagerProps) {
  const shopApi = useMemo(() => new AdminShopSettingsApi(transport), [transport]);
  const mediaApi = useMemo(() => new AdminMediaApi(transport), [transport]);
  const [shopState, setShopState] = useState<ShopState>({ kind: "loading" });
  const [mediaState, setMediaState] = useState<AdminMediaPickerState>({
    kind: "loading",
  });
  const [pickerSlot, setPickerSlot] = useState<PickerSlot | null>(null);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const shopRequestSequenceRef = useRef(0);
  const mediaRequestSequenceRef = useRef(0);
  const saveBusyRef = useRef(false);
  const heroPickerTriggerRef = useRef<HTMLButtonElement>(null);
  const groomerPickerTriggerRef = useRef<HTMLButtonElement>(null);
  const ogPickerTriggerRef = useRef<HTMLButtonElement>(null);
  const pickerInitialFocusRef = useRef<HTMLButtonElement>(null);
  const restoreFocusSlotRef = useRef<PickerSlot | null>(null);

  const loadShop = useCallback(async () => {
    if (saveBusyRef.current) {
      return;
    }
    const requestSequence = ++shopRequestSequenceRef.current;
    setShopState({ kind: "loading" });
    setPickerSlot(null);
    setSaveMessage(null);
    setSaveError(null);
    try {
      const response = await shopApi.get();
      if (requestSequence !== shopRequestSequenceRef.current) {
        return;
      }
      setShopState({ kind: "ready", draft: shopSettingsResponseToDraft(response) });
    } catch (error) {
      if (requestSequence !== shopRequestSequenceRef.current) {
        return;
      }
      if (isSessionExpired(error)) {
        onSessionExpired();
        return;
      }
      if (isAdminApiError(error) && error.kind === "not-found") {
        setShopState({ kind: "uninitialized", draft: EMPTY_SHOP_SETTINGS_DRAFT });
        return;
      }
      setShopState({ kind: "error" });
    }
  }, [onSessionExpired, shopApi]);

  const loadMedia = useCallback(async () => {
    const requestSequence = ++mediaRequestSequenceRef.current;
    setMediaState({ kind: "loading" });
    try {
      const items = await mediaApi.list();
      if (requestSequence === mediaRequestSequenceRef.current) {
        setMediaState({ kind: "ready", items });
      }
    } catch (error) {
      if (requestSequence !== mediaRequestSequenceRef.current) {
        return;
      }
      if (isSessionExpired(error)) {
        onSessionExpired();
        return;
      }
      setMediaState({ kind: "error" });
    }
  }, [mediaApi, onSessionExpired]);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => {
      if (!active) {
        return;
      }
      void loadShop();
      void loadMedia();
    });
    return () => {
      active = false;
      shopRequestSequenceRef.current += 1;
      mediaRequestSequenceRef.current += 1;
    };
  }, [loadMedia, loadShop]);

  useEffect(() => {
    if (pickerSlot !== null) {
      pickerInitialFocusRef.current?.focus();
      return;
    }

    const restoreSlot = restoreFocusSlotRef.current;
    restoreFocusSlotRef.current = null;
    if (restoreSlot === "hero") {
      heroPickerTriggerRef.current?.focus();
    } else if (restoreSlot === "groomer") {
      groomerPickerTriggerRef.current?.focus();
    } else if (restoreSlot === "og") {
      ogPickerTriggerRef.current?.focus();
    }
  }, [pickerSlot]);

  const draft =
    shopState.kind === "uninitialized" ||
    shopState.kind === "ready" ||
    shopState.kind === "saving"
      ? shopState.draft
      : null;
  const saving = shopState.kind === "saving";
  const uninitialized =
    shopState.kind === "uninitialized" ||
    (shopState.kind === "saving" && shopState.previousKind === "uninitialized");

  function updateDraft(patch: Partial<ShopSettingsDraft>) {
    setSaveMessage(null);
    setSaveError(null);
    setShopState((current) => {
      if (current.kind !== "uninitialized" && current.kind !== "ready") {
        return current;
      }
      return { ...current, draft: { ...current.draft, ...patch } };
    });
  }

  function selectMedia(slot: PickerSlot, selectedId: string | null) {
    if (!draft) {
      return;
    }
    if (slot === "hero") {
      updateDraft({
        heroImageId: selectedId,
        heroImageAltText:
          selectedId !== null && selectedId === draft.heroImageId
            ? draft.heroImageAltText
            : "",
      });
      return;
    }
    if (slot === "groomer") {
      updateDraft({
        groomerImageId: selectedId,
        groomerImageAltText:
          selectedId !== null && selectedId === draft.groomerImageId
            ? draft.groomerImageAltText
            : "",
      });
      return;
    }
    updateDraft({ ogImageId: selectedId });
  }

  function openPicker(slot: PickerSlot) {
    restoreFocusSlotRef.current = null;
    setPickerSlot(slot);
  }

  function closePicker(slot: PickerSlot) {
    restoreFocusSlotRef.current = slot;
    setPickerSlot(null);
  }

  async function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (
      saveBusyRef.current ||
      (shopState.kind !== "uninitialized" && shopState.kind !== "ready")
    ) {
      return;
    }

    const currentDraft = shopState.draft;
    const validationMessage = validateDraft(currentDraft, mediaState);
    const request = buildShopSettingsRequest(currentDraft);
    if (validationMessage || !request) {
      setSaveMessage(null);
      setSaveError(validationMessage ?? "필수 입력값을 모두 확인해 주세요.");
      return;
    }

    saveBusyRef.current = true;
    const requestSequence = ++shopRequestSequenceRef.current;
    const previousKind = shopState.kind;
    setPickerSlot(null);
    setSaveMessage(null);
    setSaveError(null);
    setShopState({ kind: "saving", draft: currentDraft, previousKind });

    try {
      const response = await shopApi.put(request);
      if (requestSequence !== shopRequestSequenceRef.current) {
        return;
      }
      setShopState({ kind: "ready", draft: shopSettingsResponseToDraft(response) });
      setSaveMessage("매장정보를 저장했습니다.");
    } catch (error) {
      if (requestSequence !== shopRequestSequenceRef.current) {
        return;
      }
      if (isSessionExpired(error)) {
        onSessionExpired();
        return;
      }
      setShopState({ kind: previousKind, draft: currentDraft });
      setSaveError(saveErrorMessage(error));
    } finally {
      saveBusyRef.current = false;
    }
  }

  function renderPicker(
    slot: PickerSlot,
    slotLabel: string,
    selectedId: string | null,
  ) {
    if (pickerSlot !== slot) {
      return null;
    }
    return (
      <AdminMediaPicker
        api={mediaApi}
        id={`shop-${slot}-media-picker`}
        slotLabel={slotLabel}
        state={mediaState}
        selectedId={selectedId}
        disabled={saving}
        initialFocusRef={pickerInitialFocusRef}
        onSelect={(nextSelectedId) => selectMedia(slot, nextSelectedId)}
        onRetry={() => void loadMedia()}
        onClose={() => closePicker(slot)}
        onSessionExpired={onSessionExpired}
      />
    );
  }

  return (
    <section className={styles.manager} aria-labelledby="shop-settings-title">
      <div className={styles.topBar}>
        <button type="button" disabled={saving} onClick={onBack}>
          관리 홈으로
        </button>
      </div>

      <header className={styles.header}>
        <p>Singleton content</p>
        <h2 id="shop-settings-title">매장정보 관리</h2>
        <span>저장할 때 모든 항목을 한 번의 전체 PUT으로 전송합니다.</span>
      </header>

      {shopState.kind === "loading" ? (
        <div className={styles.statePanel} role="status" aria-live="polite">
          매장정보를 불러오고 있습니다.
        </div>
      ) : null}

      {shopState.kind === "error" ? (
        <div className={styles.errorPanel} role="alert">
          <p>매장정보를 불러오지 못했습니다. 연결을 확인해 주세요.</p>
          <button type="button" onClick={() => void loadShop()}>
            다시 시도
          </button>
        </div>
      ) : null}

      {draft ? (
        <form className={styles.form} onSubmit={handleSave} aria-busy={saving}>
          {uninitialized ? (
            <p className={styles.notice} role="status">
              아직 매장정보가 등록되지 않았습니다. 실제 값을 입력해 처음 저장해 주세요.
            </p>
          ) : null}
          {saveError ? (
            <p className={styles.alert} role="alert">
              {saveError}
            </p>
          ) : null}
          {saveMessage ? (
            <p className={styles.success} role="status" aria-live="polite">
              {saveMessage}
            </p>
          ) : null}

          <fieldset className={styles.formFields} disabled={saving}>
            <legend className={styles.visuallyHidden}>매장정보 전체 입력 항목</legend>
            <section className={styles.formSection} aria-labelledby="shop-basic-title">
              <h3 id="shop-basic-title">기본 정보</h3>
              <div className={styles.fieldGrid}>
                <FormInput
                  id="shopName"
                  label="매장명"
                  value={draft.shopName}
                  required
                  autoComplete="organization"
                  onChange={(value) => updateDraft({ shopName: value })}
                />
                <FormInput
                  id="regionLabel"
                  label="지역 표시"
                  value={draft.regionLabel}
                  required
                  onChange={(value) => updateDraft({ regionLabel: value })}
                />
                <FormInput
                  id="businessType"
                  label="업종"
                  value={draft.businessType}
                  required
                  onChange={(value) => updateDraft({ businessType: value })}
                />
                <FormInput
                  id="phone"
                  label="전화번호"
                  value={draft.phone}
                  required
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  onChange={(value) => updateDraft({ phone: value })}
                />
                <FormInput
                  id="address"
                  label="주소"
                  value={draft.address}
                  required
                  autoComplete="street-address"
                  onChange={(value) => updateDraft({ address: value })}
                />
              </div>
            </section>

            <section className={styles.formSection} aria-labelledby="shop-hours-title">
              <h3 id="shop-hours-title">영업·주차</h3>
              <div className={styles.fieldGrid}>
                <FormInput
                  id="openingTime"
                  label="영업 시작"
                  value={draft.openingTime}
                  required
                  type="time"
                  onChange={(value) => updateDraft({ openingTime: value })}
                />
                <FormInput
                  id="closingTime"
                  label="영업 종료"
                  value={draft.closingTime}
                  required
                  type="time"
                  onChange={(value) => updateDraft({ closingTime: value })}
                />
                <div className={styles.field}>
                  <label htmlFor="closedWeekday">
                    정기 휴무일 <span>선택</span>
                  </label>
                  <select
                    id="closedWeekday"
                    name="closedWeekday"
                    value={draft.closedWeekday ?? ""}
                    onChange={(event) =>
                      updateDraft({
                        closedWeekday:
                          event.target.value === ""
                            ? null
                            : (event.target.value as ShopWeekday),
                      })
                    }
                  >
                    <option value="">정기 휴무 없음</option>
                    {SHOP_WEEKDAYS.map((weekday) => (
                      <option key={weekday} value={weekday}>
                        {WEEKDAY_LABELS[weekday]}
                      </option>
                    ))}
                  </select>
                </div>
                <fieldset className={styles.radioGroup}>
                  <legend>주차 가능 여부 필수</legend>
                  <label>
                    <input
                      type="radio"
                      name="parkingAvailable"
                      value="true"
                      checked={draft.parkingAvailable === true}
                      onChange={() => updateDraft({ parkingAvailable: true })}
                    />
                    가능
                  </label>
                  <label>
                    <input
                      type="radio"
                      name="parkingAvailable"
                      value="false"
                      checked={draft.parkingAvailable === false}
                      onChange={() => updateDraft({ parkingAvailable: false })}
                    />
                    불가
                  </label>
                </fieldset>
                <FormTextarea
                  id="parkingNote"
                  label="주차 안내"
                  value={draft.parkingNote}
                  rows={3}
                  onChange={(value) => updateDraft({ parkingNote: value })}
                />
              </div>
            </section>

            <section className={styles.formSection} aria-labelledby="shop-hero-title">
              <h3 id="shop-hero-title">Hero</h3>
              <div className={styles.fieldGrid}>
                <FormInput
                  id="heroTitle"
                  label="Hero 제목"
                  value={draft.heroTitle}
                  onChange={(value) => updateDraft({ heroTitle: value })}
                />
                <FormTextarea
                  id="heroDescription"
                  label="Hero 설명"
                  value={draft.heroDescription}
                  rows={4}
                  onChange={(value) => updateDraft({ heroDescription: value })}
                />
              </div>
              <MediaRelationControl
                id="heroImage"
                label="Hero 이미지"
                selectedId={draft.heroImageId}
                relationState={relationState(draft.heroImageId, mediaState)}
                disabled={saving}
                altValue={draft.heroImageAltText}
                onAltChange={(value) => updateDraft({ heroImageAltText: value })}
                triggerRef={heroPickerTriggerRef}
                onOpen={() => openPicker("hero")}
                onClear={() => selectMedia("hero", null)}
              />
              {renderPicker("hero", "Hero 이미지", draft.heroImageId)}
            </section>

            <section className={styles.formSection} aria-labelledby="shop-groomer-title">
              <h3 id="shop-groomer-title">미용사 소개</h3>
              <div className={styles.fieldGrid}>
                <FormInput
                  id="groomerName"
                  label="미용사 이름"
                  value={draft.groomerName}
                  onChange={(value) => updateDraft({ groomerName: value })}
                />
                <FormTextarea
                  id="groomerIntro"
                  label="미용사 소개"
                  value={draft.groomerIntro}
                  rows={5}
                  onChange={(value) => updateDraft({ groomerIntro: value })}
                />
              </div>
              <MediaRelationControl
                id="groomerImage"
                label="미용사 이미지"
                selectedId={draft.groomerImageId}
                relationState={relationState(draft.groomerImageId, mediaState)}
                disabled={saving}
                altValue={draft.groomerImageAltText}
                onAltChange={(value) => updateDraft({ groomerImageAltText: value })}
                triggerRef={groomerPickerTriggerRef}
                onOpen={() => openPicker("groomer")}
                onClear={() => selectMedia("groomer", null)}
              />
              {renderPicker("groomer", "미용사 이미지", draft.groomerImageId)}
            </section>

            <section className={styles.formSection} aria-labelledby="shop-reservation-title">
              <h3 id="shop-reservation-title">예약 안내</h3>
              <FormTextarea
                id="reservationNotice"
                label="예약 전 안내"
                value={draft.reservationNotice}
                rows={6}
                onChange={(value) => updateDraft({ reservationNotice: value })}
              />
            </section>

            <section className={styles.formSection} aria-labelledby="shop-channel-title">
              <h3 id="shop-channel-title">외부 채널</h3>
              <p className={styles.sectionHelp}>
                URL은 `https://`로 시작하는 전체 주소를 입력해 주세요. 빈 값은 null로
                저장됩니다.
              </p>
              <div className={styles.fieldGrid}>
                {(
                  [
                    ["instagramUrl", "Instagram"],
                    ["naverBlogUrl", "네이버 블로그"],
                    ["naverMapUrl", "네이버 지도"],
                    ["kakaoMapUrl", "카카오맵"],
                    ["naverTalktalkUrl", "네이버톡톡"],
                    ["kakaoChannelUrl", "카카오 채널"],
                  ] as const
                ).map(([key, label]) => (
                  <FormInput
                    key={key}
                    id={key}
                    label={label}
                    value={draft[key]}
                    type="url"
                    inputMode="url"
                    onChange={(value) => updateDraft({ [key]: value })}
                  />
                ))}
              </div>
            </section>

            <section className={styles.formSection} aria-labelledby="shop-sharing-title">
              <h3 id="shop-sharing-title">공유 이미지</h3>
              <p className={styles.sectionHelp}>
                OG 이미지는 대체텍스트 field가 없는 backend 계약을 그대로 사용합니다.
              </p>
              <MediaRelationControl
                id="ogImage"
                label="OG 이미지"
                selectedId={draft.ogImageId}
                relationState={relationState(draft.ogImageId, mediaState)}
                disabled={saving}
                triggerRef={ogPickerTriggerRef}
                onOpen={() => openPicker("og")}
                onClear={() => selectMedia("og", null)}
              />
              {renderPicker("og", "OG 이미지", draft.ogImageId)}
            </section>
          </fieldset>

          <button className={styles.saveButton} type="submit" disabled={saving}>
            {saving ? "저장 중" : "매장정보 저장"}
          </button>
        </form>
      ) : null}
    </section>
  );
}
