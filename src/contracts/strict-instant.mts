const MICROSECOND_UTC_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.(\d{1,6}))?Z$/u;

export type StrictMicrosecondUtcInstant = Readonly<{
  value: string;
  fraction: string;
}>;

export function parseStrictMicrosecondUtcInstant(
  value: unknown,
): StrictMicrosecondUtcInstant | null {
  if (typeof value !== "string") return null;
  const match = MICROSECOND_UTC_INSTANT_PATTERN.exec(value);
  if (match === null) return null;
  const parsed = new Date(value);
  if (
    !Number.isFinite(parsed.getTime()) ||
    parsed.getUTCFullYear() !== Number(match[1]) ||
    parsed.getUTCMonth() + 1 !== Number(match[2]) ||
    parsed.getUTCDate() !== Number(match[3]) ||
    parsed.getUTCHours() !== Number(match[4]) ||
    parsed.getUTCMinutes() !== Number(match[5]) ||
    parsed.getUTCSeconds() !== Number(match[6])
  ) {
    return null;
  }
  return { value, fraction: match[7] ?? "" };
}
