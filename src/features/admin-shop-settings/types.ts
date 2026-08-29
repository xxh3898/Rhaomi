export const SHOP_WEEKDAYS = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
] as const;

export type ShopWeekday = (typeof SHOP_WEEKDAYS)[number];

export const SHOP_SETTINGS_MUTABLE_KEYS = [
  "shopName",
  "regionLabel",
  "businessType",
  "phone",
  "address",
  "openingTime",
  "closingTime",
  "closedWeekday",
  "parkingAvailable",
  "parkingNote",
  "heroTitle",
  "heroDescription",
  "groomerName",
  "groomerIntro",
  "reservationNotice",
  "heroImageId",
  "heroImageAltText",
  "groomerImageId",
  "groomerImageAltText",
  "ogImageId",
  "instagramUrl",
  "naverBlogUrl",
  "naverMapUrl",
  "kakaoMapUrl",
  "naverTalktalkUrl",
  "kakaoChannelUrl",
] as const;

export const SHOP_SETTINGS_AUDIT_KEYS = [
  "createdAt",
  "updatedAt",
  "createdBy",
  "updatedBy",
] as const;

export type ShopSettingsRequest = Readonly<{
  shopName: string;
  regionLabel: string;
  businessType: string;
  phone: string;
  address: string;
  openingTime: string;
  closingTime: string;
  closedWeekday: ShopWeekday | null;
  parkingAvailable: boolean;
  parkingNote: string | null;
  heroTitle: string | null;
  heroDescription: string | null;
  groomerName: string | null;
  groomerIntro: string | null;
  reservationNotice: string | null;
  heroImageId: string | null;
  heroImageAltText: string | null;
  groomerImageId: string | null;
  groomerImageAltText: string | null;
  ogImageId: string | null;
  instagramUrl: string | null;
  naverBlogUrl: string | null;
  naverMapUrl: string | null;
  kakaoMapUrl: string | null;
  naverTalktalkUrl: string | null;
  kakaoChannelUrl: string | null;
}>;

export type ShopSettingsResponse = Readonly<
  ShopSettingsRequest & {
    createdAt: string;
    updatedAt: string;
    createdBy: string;
    updatedBy: string;
  }
>;

export type ShopSettingsDraft = Readonly<{
  shopName: string;
  regionLabel: string;
  businessType: string;
  phone: string;
  address: string;
  openingTime: string;
  closingTime: string;
  closedWeekday: ShopWeekday | null;
  parkingAvailable: boolean | null;
  parkingNote: string;
  heroTitle: string;
  heroDescription: string;
  groomerName: string;
  groomerIntro: string;
  reservationNotice: string;
  heroImageId: string | null;
  heroImageAltText: string;
  groomerImageId: string | null;
  groomerImageAltText: string;
  ogImageId: string | null;
  instagramUrl: string;
  naverBlogUrl: string;
  naverMapUrl: string;
  kakaoMapUrl: string;
  naverTalktalkUrl: string;
  kakaoChannelUrl: string;
}>;

export const EMPTY_SHOP_SETTINGS_DRAFT: ShopSettingsDraft = {
  shopName: "",
  regionLabel: "",
  businessType: "",
  phone: "",
  address: "",
  openingTime: "",
  closingTime: "",
  closedWeekday: null,
  parkingAvailable: null,
  parkingNote: "",
  heroTitle: "",
  heroDescription: "",
  groomerName: "",
  groomerIntro: "",
  reservationNotice: "",
  heroImageId: null,
  heroImageAltText: "",
  groomerImageId: null,
  groomerImageAltText: "",
  ogImageId: null,
  instagramUrl: "",
  naverBlogUrl: "",
  naverMapUrl: "",
  kakaoMapUrl: "",
  naverTalktalkUrl: "",
  kakaoChannelUrl: "",
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
const TIMESTAMP_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
const EXPECTED_RESPONSE_KEYS: ReadonlySet<string> = new Set([
  ...SHOP_SETTINGS_MUTABLE_KEYS,
  ...SHOP_SETTINGS_AUDIT_KEYS,
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isNullableUuid(value: unknown): value is string | null {
  return value === null || (typeof value === "string" && UUID_PATTERN.test(value));
}

function isTimestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    TIMESTAMP_PATTERN.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

function hasExactResponseKeys(value: Record<string, unknown>): boolean {
  const keys = Object.keys(value);
  return (
    keys.length === EXPECTED_RESPONSE_KEYS.size &&
    keys.every((key) => EXPECTED_RESPONSE_KEYS.has(key))
  );
}

export function isShopSettingsResponse(value: unknown): value is ShopSettingsResponse {
  return (
    isRecord(value) &&
    hasExactResponseKeys(value) &&
    typeof value.shopName === "string" &&
    typeof value.regionLabel === "string" &&
    typeof value.businessType === "string" &&
    typeof value.phone === "string" &&
    typeof value.address === "string" &&
    typeof value.openingTime === "string" &&
    TIME_PATTERN.test(value.openingTime) &&
    typeof value.closingTime === "string" &&
    TIME_PATTERN.test(value.closingTime) &&
    (value.closedWeekday === null ||
      SHOP_WEEKDAYS.includes(value.closedWeekday as ShopWeekday)) &&
    typeof value.parkingAvailable === "boolean" &&
    isNullableString(value.parkingNote) &&
    isNullableString(value.heroTitle) &&
    isNullableString(value.heroDescription) &&
    isNullableString(value.groomerName) &&
    isNullableString(value.groomerIntro) &&
    isNullableString(value.reservationNotice) &&
    isNullableUuid(value.heroImageId) &&
    isNullableString(value.heroImageAltText) &&
    isNullableUuid(value.groomerImageId) &&
    isNullableString(value.groomerImageAltText) &&
    isNullableUuid(value.ogImageId) &&
    isNullableString(value.instagramUrl) &&
    isNullableString(value.naverBlogUrl) &&
    isNullableString(value.naverMapUrl) &&
    isNullableString(value.kakaoMapUrl) &&
    isNullableString(value.naverTalktalkUrl) &&
    isNullableString(value.kakaoChannelUrl) &&
    isTimestamp(value.createdAt) &&
    isTimestamp(value.updatedAt) &&
    typeof value.createdBy === "string" &&
    UUID_PATTERN.test(value.createdBy) &&
    typeof value.updatedBy === "string" &&
    UUID_PATTERN.test(value.updatedBy)
  );
}

export function shopSettingsResponseToDraft(
  response: ShopSettingsResponse,
): ShopSettingsDraft {
  return {
    shopName: response.shopName,
    regionLabel: response.regionLabel,
    businessType: response.businessType,
    phone: response.phone,
    address: response.address,
    openingTime: response.openingTime,
    closingTime: response.closingTime,
    closedWeekday: response.closedWeekday,
    parkingAvailable: response.parkingAvailable,
    parkingNote: response.parkingNote ?? "",
    heroTitle: response.heroTitle ?? "",
    heroDescription: response.heroDescription ?? "",
    groomerName: response.groomerName ?? "",
    groomerIntro: response.groomerIntro ?? "",
    reservationNotice: response.reservationNotice ?? "",
    heroImageId: response.heroImageId,
    heroImageAltText: response.heroImageAltText ?? "",
    groomerImageId: response.groomerImageId,
    groomerImageAltText: response.groomerImageAltText ?? "",
    ogImageId: response.ogImageId,
    instagramUrl: response.instagramUrl ?? "",
    naverBlogUrl: response.naverBlogUrl ?? "",
    naverMapUrl: response.naverMapUrl ?? "",
    kakaoMapUrl: response.kakaoMapUrl ?? "",
    naverTalktalkUrl: response.naverTalktalkUrl ?? "",
    kakaoChannelUrl: response.kakaoChannelUrl ?? "",
  };
}

function nullableText(value: string): string | null {
  return value.trim().length === 0 ? null : value;
}

export function buildShopSettingsRequest(
  draft: ShopSettingsDraft,
): ShopSettingsRequest | null {
  if (draft.parkingAvailable === null) {
    return null;
  }

  return {
    shopName: draft.shopName,
    regionLabel: draft.regionLabel,
    businessType: draft.businessType,
    phone: draft.phone,
    address: draft.address,
    openingTime: draft.openingTime,
    closingTime: draft.closingTime,
    closedWeekday: draft.closedWeekday,
    parkingAvailable: draft.parkingAvailable,
    parkingNote: nullableText(draft.parkingNote),
    heroTitle: nullableText(draft.heroTitle),
    heroDescription: nullableText(draft.heroDescription),
    groomerName: nullableText(draft.groomerName),
    groomerIntro: nullableText(draft.groomerIntro),
    reservationNotice: nullableText(draft.reservationNotice),
    heroImageId: draft.heroImageId,
    heroImageAltText:
      draft.heroImageId === null ? null : nullableText(draft.heroImageAltText),
    groomerImageId: draft.groomerImageId,
    groomerImageAltText:
      draft.groomerImageId === null
        ? null
        : nullableText(draft.groomerImageAltText),
    ogImageId: draft.ogImageId,
    instagramUrl: nullableText(draft.instagramUrl),
    naverBlogUrl: nullableText(draft.naverBlogUrl),
    naverMapUrl: nullableText(draft.naverMapUrl),
    kakaoMapUrl: nullableText(draft.kakaoMapUrl),
    naverTalktalkUrl: nullableText(draft.naverTalktalkUrl),
    kakaoChannelUrl: nullableText(draft.kakaoChannelUrl),
  };
}

export function codePointLength(value: string): number {
  return Array.from(value).length;
}
