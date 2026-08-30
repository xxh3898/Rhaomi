import {
  hasExactKeys,
  isContentStatus,
  isNonNegativeInteger,
  isTimestamp,
  isUuid,
  type ContentStatus,
} from "@/features/admin-content/types";

export type GalleryItem = Readonly<{
  id: string;
  status: ContentStatus;
  dogName: string | null;
  breedId: string | null;
  primaryServiceId: string | null;
  coverImageId: string | null;
  beforeImageId: string | null;
  afterImageId: string | null;
  summary: string | null;
  altText: string | null;
  featured: boolean;
  sortOrder: number;
  performedAt: string | null;
  publishedAt: string | null;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}>;

export type CreateGalleryRequest = Readonly<{
  dogName: string | null;
  breedId: string | null;
  primaryServiceId: string | null;
  coverImageId: string | null;
  beforeImageId: string | null;
  afterImageId: string | null;
  summary: string | null;
  altText: string | null;
  featured: boolean;
  sortOrder: number | null;
  performedAt: string | null;
  publishedAt: string | null;
}>;

export type UpdateGalleryRequest = Readonly<{
  status: ContentStatus;
  dogName: string | null;
  breedId: string | null;
  primaryServiceId: string | null;
  coverImageId: string | null;
  beforeImageId: string | null;
  afterImageId: string | null;
  summary: string | null;
  altText: string | null;
  featured: boolean;
  sortOrder: number;
  performedAt: string | null;
  publishedAt: string | null;
}>;

export type GalleryDraft = Readonly<{
  status: ContentStatus;
  dogName: string;
  breedId: string | null;
  primaryServiceId: string | null;
  coverImageId: string | null;
  beforeImageId: string | null;
  afterImageId: string | null;
  summary: string;
  altText: string;
  featured: boolean;
  sortOrder: string;
  performedAt: string;
  publishedAt: string;
  performedAtOriginal: string | null;
  publishedAtOriginal: string | null;
}>;

export const EMPTY_GALLERY_DRAFT: GalleryDraft = {
  status: "draft",
  dogName: "",
  breedId: null,
  primaryServiceId: null,
  coverImageId: null,
  beforeImageId: null,
  afterImageId: null,
  summary: "",
  altText: "",
  featured: false,
  sortOrder: "",
  performedAt: "",
  publishedAt: "",
  performedAtOriginal: null,
  publishedAtOriginal: null,
};

const RESPONSE_KEYS: ReadonlySet<string> = new Set([
  "id",
  "status",
  "dogName",
  "breedId",
  "primaryServiceId",
  "coverImageId",
  "beforeImageId",
  "afterImageId",
  "summary",
  "altText",
  "featured",
  "sortOrder",
  "performedAt",
  "publishedAt",
  "createdAt",
  "updatedAt",
  "createdBy",
  "updatedBy",
]);
const LOCAL_DATE_TIME_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d)(?::([0-5]\d)(?:\.(\d{1,6}))?)?$/;
const MICROSECOND_INSTANT_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNullableUuid(value: unknown): value is string | null {
  return value === null || isUuid(value);
}

function isNullableText(
  value: unknown,
  maxCodePoints: number,
): value is string | null {
  return (
    value === null ||
    (typeof value === "string" &&
      value.length > 0 &&
      value === value.trim() &&
      Array.from(value).length <= maxCodePoints)
  );
}

function isMicrosecondTimestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    MICROSECOND_INSTANT_PATTERN.test(value) &&
    isTimestamp(value)
  );
}

function isNullableTimestamp(value: unknown): value is string | null {
  return value === null || isMicrosecondTimestamp(value);
}

export function isGalleryItem(value: unknown): value is GalleryItem {
  return (
    isRecord(value) &&
    hasExactKeys(value, RESPONSE_KEYS) &&
    isUuid(value.id) &&
    isContentStatus(value.status) &&
    isNullableText(value.dogName, 100) &&
    isNullableUuid(value.breedId) &&
    isNullableUuid(value.primaryServiceId) &&
    isNullableUuid(value.coverImageId) &&
    isNullableUuid(value.beforeImageId) &&
    isNullableUuid(value.afterImageId) &&
    isNullableText(value.summary, 1_000) &&
    isNullableText(value.altText, 300) &&
    typeof value.featured === "boolean" &&
    isNonNegativeInteger(value.sortOrder) &&
    isNullableTimestamp(value.performedAt) &&
    isNullableTimestamp(value.publishedAt) &&
    isMicrosecondTimestamp(value.createdAt) &&
    isMicrosecondTimestamp(value.updatedAt) &&
    isUuid(value.createdBy) &&
    isUuid(value.updatedBy)
  );
}

export function isGalleryList(value: unknown): value is readonly GalleryItem[] {
  return Array.isArray(value) && value.every(isGalleryItem);
}

export function isCreatedGalleryItem(value: unknown): value is GalleryItem {
  return isGalleryItem(value) && value.status === "draft";
}

function pad(value: number, length = 2): string {
  return String(value).padStart(length, "0");
}

export function instantToLocalDateTimeValue(value: string | null): string {
  if (value === null || !isMicrosecondTimestamp(value)) {
    return "";
  }
  const date = new Date(value);
  const fraction = /\.(\d{1,6})Z$/.exec(value)?.[1]?.padEnd(3, "0").slice(0, 3) ?? "000";
  return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate(),
  )}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(
    date.getSeconds(),
  )}.${fraction}`;
}

export function localDateTimeValueToInstant(
  value: string,
): string | null | undefined {
  if (value.length === 0) {
    return null;
  }
  const match = LOCAL_DATE_TIME_PATTERN.exec(value);
  if (!match) {
    return undefined;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6] ?? "0");
  const fraction = (match[7] ?? "").padEnd(6, "0");
  const millisecond = Number(fraction.slice(0, 3));
  const date = new Date(year, month - 1, day, hour, minute, second, millisecond);
  if (
    date.getFullYear() !== year ||
    date.getMonth() + 1 !== month ||
    date.getDate() !== day ||
    date.getHours() !== hour ||
    date.getMinutes() !== minute ||
    date.getSeconds() !== second ||
    date.getMilliseconds() !== millisecond
  ) {
    return undefined;
  }
  return date.toISOString().replace(/\.\d{3}Z$/, `.${fraction}Z`);
}

function normalizeOptionalText(
  value: string,
  maxCodePoints: number,
): string | null | undefined {
  const normalized = value.trim();
  if (normalized.length === 0) {
    return null;
  }
  return Array.from(normalized).length <= maxCodePoints ? normalized : undefined;
}

function parseSortOrder(value: string): number | null | undefined {
  if (value.trim().length === 0) {
    return null;
  }
  const parsed = Number(value);
  return isNonNegativeInteger(parsed) ? parsed : undefined;
}

type CommonRequestFields = Omit<CreateGalleryRequest, "sortOrder">;

function buildCommonRequest(draft: GalleryDraft): CommonRequestFields | null {
  const dogName = normalizeOptionalText(draft.dogName, 100);
  const summary = normalizeOptionalText(draft.summary, 1_000);
  const altText = normalizeOptionalText(draft.altText, 300);
  const performedAt =
    draft.performedAtOriginal !== null &&
    instantToLocalDateTimeValue(draft.performedAtOriginal) === draft.performedAt
      ? draft.performedAtOriginal
      : localDateTimeValueToInstant(draft.performedAt);
  const publishedAt =
    draft.publishedAtOriginal !== null &&
    instantToLocalDateTimeValue(draft.publishedAtOriginal) === draft.publishedAt
      ? draft.publishedAtOriginal
      : localDateTimeValueToInstant(draft.publishedAt);
  if (
    dogName === undefined ||
    summary === undefined ||
    altText === undefined ||
    performedAt === undefined ||
    publishedAt === undefined ||
    (draft.beforeImageId !== null && draft.beforeImageId === draft.afterImageId)
  ) {
    return null;
  }
  return {
    dogName,
    breedId: draft.breedId,
    primaryServiceId: draft.primaryServiceId,
    coverImageId: draft.coverImageId,
    beforeImageId: draft.beforeImageId,
    afterImageId: draft.afterImageId,
    summary,
    altText,
    featured: draft.featured,
    performedAt,
    publishedAt,
  };
}

export function buildGalleryCreateRequest(
  draft: GalleryDraft,
): CreateGalleryRequest | null {
  const common = buildCommonRequest(draft);
  const sortOrder = parseSortOrder(draft.sortOrder);
  if (!common || sortOrder === undefined) {
    return null;
  }
  return { ...common, sortOrder };
}

export function buildGalleryUpdateRequest(
  draft: GalleryDraft,
): UpdateGalleryRequest | null {
  const common = buildCommonRequest(draft);
  const sortOrder = parseSortOrder(draft.sortOrder);
  if (!common || sortOrder === null || sortOrder === undefined) {
    return null;
  }
  return { status: draft.status, ...common, sortOrder };
}

export function galleryItemToDraft(item: GalleryItem): GalleryDraft {
  return {
    status: item.status,
    dogName: item.dogName ?? "",
    breedId: item.breedId,
    primaryServiceId: item.primaryServiceId,
    coverImageId: item.coverImageId,
    beforeImageId: item.beforeImageId,
    afterImageId: item.afterImageId,
    summary: item.summary ?? "",
    altText: item.altText ?? "",
    featured: item.featured,
    sortOrder: String(item.sortOrder),
    performedAt: instantToLocalDateTimeValue(item.performedAt),
    publishedAt: instantToLocalDateTimeValue(item.publishedAt),
    performedAtOriginal: item.performedAt,
    publishedAtOriginal: item.publishedAt,
  };
}
