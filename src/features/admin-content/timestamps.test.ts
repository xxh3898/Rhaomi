import { describe, expect, it } from "vitest";

import {
  compareMicrosecondInstants,
  instantToLocalDateTimeValue,
  isMicrosecondInstant,
  localDateTimeDraftToInstant,
  localDateTimeValueToInstant,
} from "./timestamps";

describe("admin content timestamp 경계", () => {
  it("local datetime과 UTC Instant를 microsecond 정밀도로 변환한다", () => {
    const localValue = "2026-08-30T12:34:56.123456";
    const instant = localDateTimeValueToInstant(localValue);

    expect(instant).toMatch(/\.123456Z$/);
    expect(instantToLocalDateTimeValue(instant ?? null)).toBe(
      "2026-08-30T12:34:56.123",
    );
  });

  it("null과 존재하지 않는 local date를 구분한다", () => {
    expect(localDateTimeValueToInstant("")).toBe(null);
    expect(localDateTimeValueToInstant("2026-02-30T12:00")).toBeUndefined();
    expect(instantToLocalDateTimeValue(null)).toBe("");
  });

  it("사용자가 변경하지 않은 backend microsecond 원본을 full PUT에 보존한다", () => {
    const original = "2026-08-30T03:34:56.123456Z";
    const displayed = instantToLocalDateTimeValue(original);

    expect(localDateTimeDraftToInstant(displayed, original)).toBe(original);
    expect(
      localDateTimeDraftToInstant("2026-08-30T12:34:56.124", original),
    ).not.toBe(original);
  });

  it("정확히 1 microsecond 차이와 같은 시각을 구분한다", () => {
    const earlier = "2026-08-30T00:00:00.000000Z";
    const later = "2026-08-30T00:00:00.000001Z";

    expect(compareMicrosecondInstants(earlier, later)).toBe(-1);
    expect(compareMicrosecondInstants(later, earlier)).toBe(1);
    expect(compareMicrosecondInstants(earlier, "2026-08-30T00:00:00Z")).toBe(0);
    expect(isMicrosecondInstant("2026-08-30T00:00:00.1234567Z")).toBe(false);
  });
});
