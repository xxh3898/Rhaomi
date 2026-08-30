import { isTimestamp } from "./types";

const LOCAL_DATE_TIME_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d)(?::([0-5]\d)(?:\.(\d{1,6}))?)?$/;
const MICROSECOND_INSTANT_PATTERN =
  /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?Z$/;

function pad(value: number, length = 2): string {
  return String(value).padStart(length, "0");
}

export function isMicrosecondInstant(value: unknown): value is string {
  return (
    typeof value === "string" &&
    MICROSECOND_INSTANT_PATTERN.test(value) &&
    isTimestamp(value)
  );
}

export function instantToLocalDateTimeValue(value: string | null): string {
  if (value === null || !isMicrosecondInstant(value)) {
    return "";
  }
  const date = new Date(value);
  const fraction =
    MICROSECOND_INSTANT_PATTERN.exec(value)?.[2]?.padEnd(3, "0").slice(0, 3) ??
    "000";
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

export function localDateTimeDraftToInstant(
  value: string,
  original: string | null,
): string | null | undefined {
  if (
    original !== null &&
    instantToLocalDateTimeValue(original) === value
  ) {
    return original;
  }
  return localDateTimeValueToInstant(value);
}

export function compareMicrosecondInstants(left: string, right: string): number {
  const leftMatch = MICROSECOND_INSTANT_PATTERN.exec(left);
  const rightMatch = MICROSECOND_INSTANT_PATTERN.exec(right);
  if (
    !leftMatch ||
    !rightMatch ||
    !isMicrosecondInstant(left) ||
    !isMicrosecondInstant(right)
  ) {
    throw new TypeError("microsecond Instant가 필요합니다.");
  }
  const leftKey = `${leftMatch[1]}.${(leftMatch[2] ?? "").padEnd(6, "0")}Z`;
  const rightKey = `${rightMatch[1]}.${(rightMatch[2] ?? "").padEnd(6, "0")}Z`;
  return leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : 0;
}
