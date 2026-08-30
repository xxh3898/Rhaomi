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

export type Breed = Readonly<{
  id: string;
  status: ContentStatus;
  name: string;
  slug: string;
  description: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}>;

export type CreateBreedRequest = Readonly<{
  name: string;
  slug: string;
  description: string | null;
  sortOrder: number | null;
}>;

export type UpdateBreedRequest = Readonly<{
  status: ContentStatus;
  name: string;
  description: string | null;
  sortOrder: number;
}>;

const BREED_RESPONSE_KEYS: ReadonlySet<string> = new Set([
  "id",
  "status",
  "name",
  "slug",
  "description",
  "sortOrder",
  "createdAt",
  "updatedAt",
  "createdBy",
  "updatedBy",
]);

export function isBreed(value: unknown): value is Breed {
  return (
    isRecord(value) &&
    hasExactKeys(value, BREED_RESPONSE_KEYS) &&
    isUuid(value.id) &&
    isContentStatus(value.status) &&
    typeof value.name === "string" &&
    value.name.length > 0 &&
    value.name.length <= 100 &&
    value.name === value.name.trim() &&
    isSlug(value.slug) &&
    isNormalizedNullableText(value.description) &&
    isNonNegativeInteger(value.sortOrder) &&
    isTimestamp(value.createdAt) &&
    isTimestamp(value.updatedAt) &&
    isUuid(value.createdBy) &&
    isUuid(value.updatedBy)
  );
}

export function isBreedList(value: unknown): value is readonly Breed[] {
  return Array.isArray(value) && value.every(isBreed);
}

export function isCreatedBreed(value: unknown): value is Breed {
  return isBreed(value) && value.status === "draft";
}
