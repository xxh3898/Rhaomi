export const MEDIA_STATUSES = ["active", "archived"] as const;
export type MediaStatus = (typeof MEDIA_STATUSES)[number];

export type MediaItem = Readonly<{
  id: string;
  status: MediaStatus;
  sourceContentType: string;
  contentType: "image/jpeg" | "image/png";
  sourceByteSize: number;
  byteSize: number;
  width: number;
  height: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}>;

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SOURCE_CONTENT_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/heic",
  "image/heif",
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function isTimestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

export function isMediaItem(value: unknown): value is MediaItem {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    UUID_PATTERN.test(value.id) &&
    (value.status === "active" || value.status === "archived") &&
    typeof value.sourceContentType === "string" &&
    SOURCE_CONTENT_TYPES.has(value.sourceContentType) &&
    (value.contentType === "image/jpeg" || value.contentType === "image/png") &&
    isPositiveInteger(value.sourceByteSize) &&
    isPositiveInteger(value.byteSize) &&
    isPositiveInteger(value.width) &&
    isPositiveInteger(value.height) &&
    isTimestamp(value.createdAt) &&
    isTimestamp(value.updatedAt) &&
    typeof value.createdBy === "string" &&
    UUID_PATTERN.test(value.createdBy) &&
    typeof value.updatedBy === "string" &&
    UUID_PATTERN.test(value.updatedBy)
  );
}

export function isMediaList(value: unknown): value is readonly MediaItem[] {
  return Array.isArray(value) && value.every(isMediaItem);
}
