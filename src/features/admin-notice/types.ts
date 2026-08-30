import {
  hasExactKeys,
  isContentStatus,
  isRecord,
  isUuid,
  type ContentStatus,
} from "@/features/admin-content/types";
import {
  compareMicrosecondInstants,
  instantToLocalDateTimeValue,
  isMicrosecondInstant,
  localDateTimeDraftToInstant,
} from "@/features/admin-content/timestamps";

export type Notice = Readonly<{
  id: string;
  status: ContentStatus;
  title: string;
  slug: string;
  summary: string | null;
  bodyMarkdown: string | null;
  pinned: boolean;
  publishedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}>;

export type CreateNoticeRequest = Readonly<{
  title: string;
  slug: string;
  summary: string | null;
  bodyMarkdown: string | null;
  pinned: boolean;
  publishedAt: string | null;
  expiresAt: string | null;
}>;

export type UpdateNoticeRequest = Readonly<{
  status: ContentStatus;
  title: string;
  summary: string | null;
  bodyMarkdown: string | null;
  pinned: boolean;
  publishedAt: string | null;
  expiresAt: string | null;
}>;

export type NoticeDraft = Readonly<{
  status: ContentStatus;
  title: string;
  slug: string;
  summary: string;
  bodyMarkdown: string;
  pinned: boolean;
  publishedAt: string;
  expiresAt: string;
  publishedAtOriginal: string | null;
  expiresAtOriginal: string | null;
}>;

export type NoticeDraftValidationKind =
  | "invalid-fields"
  | "window-invalid"
  | "publish-invalid";

export const EMPTY_NOTICE_DRAFT: NoticeDraft = {
  status: "draft",
  title: "",
  slug: "",
  summary: "",
  bodyMarkdown: "",
  pinned: false,
  publishedAt: "",
  expiresAt: "",
  publishedAtOriginal: null,
  expiresAtOriginal: null,
};

const NOTICE_RESPONSE_KEYS: ReadonlySet<string> = new Set([
  "id",
  "status",
  "title",
  "slug",
  "summary",
  "bodyMarkdown",
  "pinned",
  "publishedAt",
  "expiresAt",
  "createdAt",
  "updatedAt",
  "createdBy",
  "updatedBy",
]);
const NOTICE_SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

function isNormalizedRequiredText(
  value: unknown,
  maxCodePoints: number,
): value is string {
  return (
    typeof value === "string" &&
    value === value.trim() &&
    /\S/u.test(value) &&
    Array.from(value).length <= maxCodePoints
  );
}

function isNormalizedOptionalText(
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

function isNoticeSlug(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length <= 160 &&
    NOTICE_SLUG_PATTERN.test(value)
  );
}

function isNullableMicrosecondInstant(value: unknown): value is string | null {
  return value === null || isMicrosecondInstant(value);
}

function hasValidWindow(
  publishedAt: string | null,
  expiresAt: string | null,
): boolean {
  return (
    expiresAt === null ||
    (publishedAt !== null &&
      compareMicrosecondInstants(publishedAt, expiresAt) < 0)
  );
}

export function isNotice(value: unknown): value is Notice {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, NOTICE_RESPONSE_KEYS) ||
    !isUuid(value.id) ||
    !isContentStatus(value.status) ||
    !isNormalizedRequiredText(value.title, 200) ||
    !isNoticeSlug(value.slug) ||
    !isNormalizedOptionalText(value.summary, 300) ||
    !isNormalizedOptionalText(value.bodyMarkdown, 50_000) ||
    typeof value.pinned !== "boolean" ||
    !isNullableMicrosecondInstant(value.publishedAt) ||
    !isNullableMicrosecondInstant(value.expiresAt) ||
    !isMicrosecondInstant(value.createdAt) ||
    !isMicrosecondInstant(value.updatedAt) ||
    !isUuid(value.createdBy) ||
    !isUuid(value.updatedBy)
  ) {
    return false;
  }
  if (!hasValidWindow(value.publishedAt, value.expiresAt)) {
    return false;
  }
  return (
    value.status !== "published" ||
    (value.publishedAt !== null &&
      value.bodyMarkdown !== null &&
      /\S/u.test(value.bodyMarkdown))
  );
}

export function isNoticeList(value: unknown): value is readonly Notice[] {
  return Array.isArray(value) && value.every(isNotice);
}

export function isCreatedNotice(value: unknown): value is Notice {
  return isNotice(value) && value.status === "draft";
}

function normalizeRequiredText(
  value: string,
  maxCodePoints: number,
): string | undefined {
  const normalized = value.trim();
  return /\S/u.test(normalized) && Array.from(normalized).length <= maxCodePoints
    ? normalized
    : undefined;
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

type ParsedNoticeDraft = Readonly<{
  title: string;
  slug: string;
  summary: string | null;
  bodyMarkdown: string | null;
  pinned: boolean;
  publishedAt: string | null;
  expiresAt: string | null;
}>;

type ParseResult =
  | Readonly<{ value: ParsedNoticeDraft; error: null }>
  | Readonly<{ value: null; error: NoticeDraftValidationKind }>;

function parseNoticeDraft(
  draft: NoticeDraft,
  mode: "create" | "update",
): ParseResult {
  const title = normalizeRequiredText(draft.title, 200);
  const slug = draft.slug.trim();
  const summary = normalizeOptionalText(draft.summary, 300);
  const bodyMarkdown = normalizeOptionalText(draft.bodyMarkdown, 50_000);
  const publishedAt = localDateTimeDraftToInstant(
    draft.publishedAt,
    draft.publishedAtOriginal,
  );
  const expiresAt = localDateTimeDraftToInstant(
    draft.expiresAt,
    draft.expiresAtOriginal,
  );
  if (
    title === undefined ||
    !isNoticeSlug(slug) ||
    summary === undefined ||
    bodyMarkdown === undefined ||
    publishedAt === undefined ||
    expiresAt === undefined
  ) {
    return { value: null, error: "invalid-fields" };
  }
  if (!hasValidWindow(publishedAt, expiresAt)) {
    return { value: null, error: "window-invalid" };
  }
  if (
    mode === "update" &&
    draft.status === "published" &&
    (publishedAt === null || bodyMarkdown === null || !/\S/u.test(bodyMarkdown))
  ) {
    return { value: null, error: "publish-invalid" };
  }
  return {
    value: {
      title,
      slug,
      summary,
      bodyMarkdown,
      pinned: draft.pinned,
      publishedAt,
      expiresAt,
    },
    error: null,
  };
}

export function validateNoticeDraft(
  draft: NoticeDraft,
  mode: "create" | "update",
): NoticeDraftValidationKind | null {
  return parseNoticeDraft(draft, mode).error;
}

export function buildNoticeCreateRequest(
  draft: NoticeDraft,
): CreateNoticeRequest | null {
  const parsed = parseNoticeDraft(draft, "create");
  return parsed.value;
}

export function buildNoticeUpdateRequest(
  draft: NoticeDraft,
): UpdateNoticeRequest | null {
  const parsed = parseNoticeDraft(draft, "update");
  if (!parsed.value) {
    return null;
  }
  return {
    status: draft.status,
    title: parsed.value.title,
    summary: parsed.value.summary,
    bodyMarkdown: parsed.value.bodyMarkdown,
    pinned: parsed.value.pinned,
    publishedAt: parsed.value.publishedAt,
    expiresAt: parsed.value.expiresAt,
  };
}

export function noticeToDraft(notice: Notice): NoticeDraft {
  return {
    status: notice.status,
    title: notice.title,
    slug: notice.slug,
    summary: notice.summary ?? "",
    bodyMarkdown: notice.bodyMarkdown ?? "",
    pinned: notice.pinned,
    publishedAt: instantToLocalDateTimeValue(notice.publishedAt),
    expiresAt: instantToLocalDateTimeValue(notice.expiresAt),
    publishedAtOriginal: notice.publishedAt,
    expiresAtOriginal: notice.expiresAt,
  };
}
