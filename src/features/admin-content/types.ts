export const CONTENT_STATUSES = ["draft", "published", "archived"] as const;
export type ContentStatus = (typeof CONTENT_STATUSES)[number];

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TIMESTAMP_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.\d{1,9})?Z$/;
const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const MAX_JAVA_INTEGER = 2_147_483_647;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function hasExactKeys(
  value: Record<string, unknown>,
  expected: ReadonlySet<string>,
): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.size && keys.every((key) => expected.has(key));
}

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function isTimestamp(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = TIMESTAMP_PATTERN.exec(value);
  if (!match) return false;
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return false;
  const parsed = new Date(timestamp);
  return (
    parsed.getUTCFullYear() === Number(match[1]) &&
    parsed.getUTCMonth() + 1 === Number(match[2]) &&
    parsed.getUTCDate() === Number(match[3]) &&
    parsed.getUTCHours() === Number(match[4]) &&
    parsed.getUTCMinutes() === Number(match[5]) &&
    parsed.getUTCSeconds() === Number(match[6])
  );
}

export function isContentStatus(value: unknown): value is ContentStatus {
  return CONTENT_STATUSES.includes(value as ContentStatus);
}

export function isSlug(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length <= 120 &&
    SLUG_PATTERN.test(value)
  );
}

export function isNonNegativeInteger(value: unknown): value is number {
  return (
    typeof value === "number" &&
    Number.isInteger(value) &&
    value >= 0 &&
    value <= MAX_JAVA_INTEGER
  );
}

export function isNormalizedNullableText(value: unknown): value is string | null {
  return (
    value === null ||
    (typeof value === "string" && value.length > 0 && value === value.trim())
  );
}

export function nullableText(value: string): string | null {
  const normalized = value.trim();
  return normalized.length === 0 ? null : normalized;
}

export function parseOptionalSortOrder(value: string): number | null | undefined {
  const normalized = value.trim();
  if (normalized.length === 0) {
    return null;
  }
  const parsed = Number(normalized);
  return isNonNegativeInteger(parsed) ? parsed : undefined;
}

export function parseRequiredSortOrder(value: string): number | undefined {
  const parsed = parseOptionalSortOrder(value);
  return parsed === null ? undefined : parsed;
}

export function applyContentMutationResult<T extends Readonly<{ id: string }>>(
  items: readonly T[],
  item: T,
): readonly T[] {
  const existingIndex = items.findIndex((current) => current.id === item.id);
  if (existingIndex === -1) {
    return [...items, item];
  }
  return items.map((current, index) => (index === existingIndex ? item : current));
}
