import {
  hasExactKeys,
  isContentStatus,
  isNonNegativeInteger,
  isNormalizedNullableText,
  isRecord,
  isSlug,
  isTimestamp,
  isUuid,
  type ContentStatus,
} from "@/features/admin-content/types";

export type GroomingService = Readonly<{
  id: string;
  status: ContentStatus;
  name: string;
  slug: string;
  description: string | null;
  priceText: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}>;

export type CreateServiceRequest = Readonly<{
  name: string;
  slug: string;
  description: string | null;
  priceText: string | null;
  sortOrder: number | null;
}>;

export type UpdateServiceRequest = Readonly<{
  status: ContentStatus;
  name: string;
  description: string | null;
  priceText: string | null;
  sortOrder: number;
}>;

const SERVICE_RESPONSE_KEYS: ReadonlySet<string> = new Set([
  "id",
  "status",
  "name",
  "slug",
  "description",
  "priceText",
  "sortOrder",
  "createdAt",
  "updatedAt",
  "createdBy",
  "updatedBy",
]);

export function isGroomingService(value: unknown): value is GroomingService {
  return (
    isRecord(value) &&
    hasExactKeys(value, SERVICE_RESPONSE_KEYS) &&
    isUuid(value.id) &&
    isContentStatus(value.status) &&
    typeof value.name === "string" &&
    value.name.length > 0 &&
    value.name.length <= 100 &&
    value.name === value.name.trim() &&
    isSlug(value.slug) &&
    isNormalizedNullableText(value.description) &&
    isNormalizedNullableText(value.priceText) &&
    (value.priceText === null || value.priceText.length <= 100) &&
    isNonNegativeInteger(value.sortOrder) &&
    isTimestamp(value.createdAt) &&
    isTimestamp(value.updatedAt) &&
    isUuid(value.createdBy) &&
    isUuid(value.updatedBy)
  );
}

export function isGroomingServiceList(
  value: unknown,
): value is readonly GroomingService[] {
  return Array.isArray(value) && value.every(isGroomingService);
}

export function isCreatedGroomingService(
  value: unknown,
): value is GroomingService {
  return isGroomingService(value) && value.status === "draft";
}
