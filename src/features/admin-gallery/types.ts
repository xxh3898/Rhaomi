import {
  hasExactKeys,
  isContentStatus,
  isNonNegativeInteger,
  isUuid,
  type ContentStatus,
} from "@/features/admin-content/types";
import {
  instantToLocalDateTimeValue,
  isMicrosecondInstant,
  localDateTimeDraftToInstant,
} from "@/features/admin-content/timestamps";

export {
  instantToLocalDateTimeValue,
  localDateTimeValueToInstant,
} from "@/features/admin-content/timestamps";

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

function isNullableTimestamp(value: unknown): value is string | null {
  return value === null || isMicrosecondInstant(value);
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
    isMicrosecondInstant(value.createdAt) &&
    isMicrosecondInstant(value.updatedAt) &&
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
  const performedAt = localDateTimeDraftToInstant(
    draft.performedAt,
    draft.performedAtOriginal,
  );
  const publishedAt = localDateTimeDraftToInstant(
    draft.publishedAt,
    draft.publishedAtOriginal,
  );
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
