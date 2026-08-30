import { fail } from "./errors.mts";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
const PHONE_PATTERN = /^[0-9+() -]+$/;
const ISO_CONTROL_PATTERN = /[\u0000-\u001f\u007f-\u009f]/u;
const MICROSECOND_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.(\d{1,6}))?Z$/;
const NON_NEGATIVE_INT64_DECIMAL_PATTERN = /^(?:0|[1-9][0-9]*)$/u;
const POSITIVE_INT64_DECIMAL_PATTERN = /^[1-9][0-9]*$/u;
const JAVA_LONG_MAX = BigInt("9223372036854775807");
const MAX_JAVA_INTEGER = 2_147_483_647;
const MAX_MEDIA_BYTES = 30 * 1024 * 1024;
const MAX_MEDIA_AXIS = 12_000;
const MAX_MEDIA_PIXELS = 60_000_000;

export type BuildShopV1 = Readonly<{
  shopName: string;
  regionLabel: string;
  businessType: string;
  phone: string;
  address: string;
  openingTime: string;
  closingTime: string;
  closedWeekday:
    | "MONDAY"
    | "TUESDAY"
    | "WEDNESDAY"
    | "THURSDAY"
    | "FRIDAY"
    | "SATURDAY"
    | "SUNDAY"
    | null;
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

export type BuildBreedV1 = Readonly<{
  id: string;
  name: string;
  slug: string;
  description: string | null;
  sortOrder: number;
}>;

export type BuildServiceV1 = Readonly<{
  id: string;
  name: string;
  slug: string;
  description: string;
  priceText: string;
  sortOrder: number;
}>;

export type BuildGalleryItemV1 = Readonly<{
  id: string;
  dogName: string | null;
  breedId: string;
  primaryServiceId: string;
  coverImageId: string;
  beforeImageId: string | null;
  afterImageId: string | null;
  summary: string | null;
  altText: string;
  featured: boolean;
  sortOrder: number;
  performedAt: string | null;
  publishedAt: string;
}>;

export type BuildNoticeV1 = Readonly<{
  id: string;
  title: string;
  slug: string;
  summary: string | null;
  bodyMarkdown: string;
  pinned: boolean;
  publishedAt: string;
  expiresAt: string | null;
}>;

export type BuildMediaAssetV1 = Readonly<{
  id: string;
  contentType: "image/jpeg" | "image/png";
  byteSize: number;
  width: number;
  height: number;
}>;

export type BuildSnapshotV2 = Readonly<{
  schemaVersion: 2;
  contentRevision: string;
  publishGeneration: string;
  generatedAt: string;
  shop: BuildShopV1;
  services: readonly BuildServiceV1[];
  breeds: readonly BuildBreedV1[];
  galleryItems: readonly BuildGalleryItemV1[];
  notices: readonly BuildNoticeV1[];
  mediaAssets: readonly BuildMediaAssetV1[];
}>;

const TOP_LEVEL_KEYS = [
  "schemaVersion",
  "contentRevision",
  "publishGeneration",
  "generatedAt",
  "shop",
  "services",
  "breeds",
  "galleryItems",
  "notices",
  "mediaAssets",
] as const;

const SHOP_KEYS = [
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

const BREED_KEYS = ["id", "name", "slug", "description", "sortOrder"] as const;
const SERVICE_KEYS = [
  "id",
  "name",
  "slug",
  "description",
  "priceText",
  "sortOrder",
] as const;
const GALLERY_KEYS = [
  "id",
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
] as const;
const NOTICE_KEYS = [
  "id",
  "title",
  "slug",
  "summary",
  "bodyMarkdown",
  "pinned",
  "publishedAt",
  "expiresAt",
] as const;
const MEDIA_KEYS = ["id", "contentType", "byteSize", "width", "height"] as const;

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    fail("SNAPSHOT_INVALID");
  }
  return value as Record<string, unknown>;
}

function exact(
  value: Record<string, unknown>,
  expected: readonly string[],
): void {
  const actual = Object.keys(value);
  if (
    actual.length !== expected.length ||
    actual.some((key) => !expected.includes(key))
  ) {
    fail("SNAPSHOT_INVALID");
  }
}

type CodePointPredicate = (codePoint: number) => boolean;

function isJavaWhitespace(codePoint: number): boolean {
  return (
    (codePoint >= 0x0009 && codePoint <= 0x000d) ||
    (codePoint >= 0x001c && codePoint <= 0x0020) ||
    codePoint === 0x1680 ||
    (codePoint >= 0x2000 && codePoint <= 0x2006) ||
    (codePoint >= 0x2008 && codePoint <= 0x200a) ||
    codePoint === 0x2028 ||
    codePoint === 0x2029 ||
    codePoint === 0x205f ||
    codePoint === 0x3000
  );
}

function isJavaSpaceChar(codePoint: number): boolean {
  return (
    codePoint === 0x0020 ||
    codePoint === 0x00a0 ||
    codePoint === 0x1680 ||
    (codePoint >= 0x2000 && codePoint <= 0x200a) ||
    codePoint === 0x2028 ||
    codePoint === 0x2029 ||
    codePoint === 0x202f ||
    codePoint === 0x205f ||
    codePoint === 0x3000
  );
}

function isShopGalleryWhitespace(codePoint: number): boolean {
  return isJavaWhitespace(codePoint) || isJavaSpaceChar(codePoint);
}

function stripCodePoints(
  value: string,
  isWhitespace: CodePointPredicate,
): string {
  const codePoints = Array.from(value);
  let start = 0;
  let end = codePoints.length;
  while (start < end && isWhitespace(codePoints[start].codePointAt(0)!)) {
    start += 1;
  }
  while (start < end && isWhitespace(codePoints[end - 1].codePointAt(0)!)) {
    end -= 1;
  }
  return codePoints.slice(start, end).join("");
}

function canonicalTextUsing(
  value: unknown,
  isWhitespace: CodePointPredicate,
): string {
  if (typeof value !== "string") {
    fail("SNAPSHOT_INVALID");
  }
  const normalized = stripCodePoints(value, isWhitespace);
  if (normalized.length === 0 || normalized !== value) {
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function contentFieldsCanonicalText(value: unknown): string {
  return canonicalTextUsing(value, isJavaWhitespace);
}

function contentFieldsRequiredText(
  value: unknown,
  maxJavaLength: number,
): string {
  const text = contentFieldsCanonicalText(value);
  if (text.length > maxJavaLength) {
    fail("SNAPSHOT_INVALID");
  }
  return text;
}

function nullableContentFieldsText(
  value: unknown,
  maxJavaLength: number,
): string | null {
  if (value === null) return null;
  return contentFieldsRequiredText(value, maxJavaLength);
}

function nullableContentFieldsCanonicalText(value: unknown): string | null {
  return value === null ? null : contentFieldsCanonicalText(value);
}

function shopCanonicalText(value: unknown): string {
  return canonicalTextUsing(value, isShopGalleryWhitespace);
}

function shopRequiredText(value: unknown, maxCodePoints: number): string {
  const text = shopCanonicalText(value);
  if (Array.from(text).length > maxCodePoints) {
    fail("SNAPSHOT_INVALID");
  }
  return text;
}

function nullableShopText(value: unknown, maxCodePoints: number): string | null {
  if (value === null) return null;
  return shopRequiredText(value, maxCodePoints);
}

function galleryCanonicalText(value: unknown): string {
  return canonicalTextUsing(value, isShopGalleryWhitespace);
}

function galleryRequiredText(value: unknown, maxJavaLength: number): string {
  const text = galleryCanonicalText(value);
  if (text.length > maxJavaLength) {
    fail("SNAPSHOT_INVALID");
  }
  return text;
}

function nullableGalleryText(
  value: unknown,
  maxJavaLength: number,
): string | null {
  if (value === null) return null;
  return galleryRequiredText(value, maxJavaLength);
}

function uuid(value: unknown): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function nullableUuid(value: unknown): string | null {
  return value === null ? null : uuid(value);
}

function slug(value: unknown, maxCodePoints: number): string {
  if (
    typeof value !== "string" ||
    Array.from(value).length > maxCodePoints ||
    !SLUG_PATTERN.test(value)
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function nonNegativeInteger(value: unknown, max = MAX_JAVA_INTEGER): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < 0 ||
    value > max
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function positiveInteger(value: unknown): number {
  const parsed = nonNegativeInteger(value, Number.MAX_SAFE_INTEGER);
  if (parsed === 0) fail("SNAPSHOT_INVALID");
  return parsed;
}

function canonicalInt64Decimal(
  value: unknown,
  pattern: RegExp,
): string {
  if (typeof value !== "string" || !pattern.test(value)) {
    fail("SNAPSHOT_INVALID");
  }
  let numericValue: bigint;
  try {
    numericValue = BigInt(value);
  } catch {
    fail("SNAPSHOT_INVALID");
  }
  if (numericValue > JAVA_LONG_MAX) {
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function nonNegativeInt64Decimal(value: unknown): string {
  return canonicalInt64Decimal(value, NON_NEGATIVE_INT64_DECIMAL_PATTERN);
}

function positiveInt64Decimal(value: unknown): string {
  return canonicalInt64Decimal(value, POSITIVE_INT64_DECIMAL_PATTERN);
}

function booleanValue(value: unknown): boolean {
  if (typeof value !== "boolean") fail("SNAPSHOT_INVALID");
  return value;
}

function instant(value: unknown): string {
  if (typeof value !== "string") fail("SNAPSHOT_INVALID");
  const match = MICROSECOND_INSTANT_PATTERN.exec(value);
  if (!match) fail("SNAPSHOT_INVALID");
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
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function nullableInstant(value: unknown): string | null {
  return value === null ? null : instant(value);
}

function instantKey(value: string): string {
  const match = MICROSECOND_INSTANT_PATTERN.exec(value);
  if (!match) fail("SNAPSHOT_INVALID");
  return `${value.slice(0, 19)}.${(match[7] ?? "").padEnd(6, "0")}Z`;
}

function time(value: unknown): string {
  if (typeof value !== "string" || !TIME_PATTERN.test(value)) {
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function weekday(value: unknown): BuildShopV1["closedWeekday"] {
  if (value === null) return null;
  if (typeof value !== "string") fail("SNAPSHOT_INVALID");
  switch (value) {
    case "MONDAY":
    case "TUESDAY":
    case "WEDNESDAY":
    case "THURSDAY":
    case "FRIDAY":
    case "SATURDAY":
    case "SUNDAY":
      return value;
    default:
      fail("SNAPSHOT_INVALID");
  }
}

function httpsUrl(value: unknown): string | null {
  if (value === null) return null;
  const normalized = shopRequiredText(value, 2_048);
  try {
    const parsed = new URL(normalized);
    if (
      parsed.protocol !== "https:" ||
      parsed.hostname.length === 0 ||
      parsed.username.length > 0 ||
      parsed.password.length > 0 ||
      ISO_CONTROL_PATTERN.test(normalized)
    ) {
      fail("SNAPSHOT_INVALID");
    }
  } catch {
    fail("SNAPSHOT_INVALID");
  }
  return normalized;
}

function parseShop(value: unknown): BuildShopV1 {
  const input = record(value);
  exact(input, SHOP_KEYS);
  const openingTime = time(input.openingTime);
  const closingTime = time(input.closingTime);
  if (openingTime >= closingTime) fail("SNAPSHOT_INVALID");
  const phone = shopRequiredText(input.phone, 32);
  const phoneDigits = Array.from(phone).filter((character) => /\d/u.test(character));
  if (
    Array.from(phone).length < 7 ||
    phoneDigits.length < 7 ||
    !PHONE_PATTERN.test(phone) ||
    ISO_CONTROL_PATTERN.test(phone)
  ) {
    fail("SNAPSHOT_INVALID");
  }
  const heroImageId = nullableUuid(input.heroImageId);
  const heroImageAltText = nullableShopText(input.heroImageAltText, 300);
  const groomerImageId = nullableUuid(input.groomerImageId);
  const groomerImageAltText = nullableShopText(input.groomerImageAltText, 300);
  if (
    (heroImageId === null) !== (heroImageAltText === null) ||
    (groomerImageId === null) !== (groomerImageAltText === null)
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return {
    shopName: shopRequiredText(input.shopName, 100),
    regionLabel: shopRequiredText(input.regionLabel, 100),
    businessType: shopRequiredText(input.businessType, 100),
    phone,
    address: shopRequiredText(input.address, 300),
    openingTime,
    closingTime,
    closedWeekday: weekday(input.closedWeekday),
    parkingAvailable: booleanValue(input.parkingAvailable),
    parkingNote: nullableShopText(input.parkingNote, 300),
    heroTitle: nullableShopText(input.heroTitle, 200),
    heroDescription: nullableShopText(input.heroDescription, 1_000),
    groomerName: nullableShopText(input.groomerName, 100),
    groomerIntro: nullableShopText(input.groomerIntro, 2_000),
    reservationNotice: nullableShopText(input.reservationNotice, 4_000),
    heroImageId,
    heroImageAltText,
    groomerImageId,
    groomerImageAltText,
    ogImageId: nullableUuid(input.ogImageId),
    instagramUrl: httpsUrl(input.instagramUrl),
    naverBlogUrl: httpsUrl(input.naverBlogUrl),
    naverMapUrl: httpsUrl(input.naverMapUrl),
    kakaoMapUrl: httpsUrl(input.kakaoMapUrl),
    naverTalktalkUrl: httpsUrl(input.naverTalktalkUrl),
    kakaoChannelUrl: httpsUrl(input.kakaoChannelUrl),
  };
}

function parseBreed(value: unknown): BuildBreedV1 {
  const input = record(value);
  exact(input, BREED_KEYS);
  return {
    id: uuid(input.id),
    name: contentFieldsRequiredText(input.name, 100),
    slug: slug(input.slug, 120),
    description: nullableContentFieldsCanonicalText(input.description),
    sortOrder: nonNegativeInteger(input.sortOrder),
  };
}

function parseService(value: unknown): BuildServiceV1 {
  const input = record(value);
  exact(input, SERVICE_KEYS);
  return {
    id: uuid(input.id),
    name: contentFieldsRequiredText(input.name, 100),
    slug: slug(input.slug, 120),
    description: contentFieldsCanonicalText(input.description),
    priceText: contentFieldsRequiredText(input.priceText, 100),
    sortOrder: nonNegativeInteger(input.sortOrder),
  };
}

function parseGallery(value: unknown): BuildGalleryItemV1 {
  const input = record(value);
  exact(input, GALLERY_KEYS);
  const beforeImageId = nullableUuid(input.beforeImageId);
  const afterImageId = nullableUuid(input.afterImageId);
  if (beforeImageId !== null && beforeImageId === afterImageId) {
    fail("SNAPSHOT_INVALID");
  }
  return {
    id: uuid(input.id),
    dogName: nullableGalleryText(input.dogName, 100),
    breedId: uuid(input.breedId),
    primaryServiceId: uuid(input.primaryServiceId),
    coverImageId: uuid(input.coverImageId),
    beforeImageId,
    afterImageId,
    summary: nullableGalleryText(input.summary, 1_000),
    altText: galleryRequiredText(input.altText, 300),
    featured: booleanValue(input.featured),
    sortOrder: nonNegativeInteger(input.sortOrder),
    performedAt: nullableInstant(input.performedAt),
    publishedAt: instant(input.publishedAt),
  };
}

function parseNotice(value: unknown): BuildNoticeV1 {
  const input = record(value);
  exact(input, NOTICE_KEYS);
  return {
    id: uuid(input.id),
    title: contentFieldsRequiredText(input.title, 200),
    slug: slug(input.slug, 160),
    summary: nullableContentFieldsText(input.summary, 300),
    bodyMarkdown: contentFieldsRequiredText(input.bodyMarkdown, 50_000),
    pinned: booleanValue(input.pinned),
    publishedAt: instant(input.publishedAt),
    expiresAt: nullableInstant(input.expiresAt),
  };
}

function parseMedia(value: unknown): BuildMediaAssetV1 {
  const input = record(value);
  exact(input, MEDIA_KEYS);
  let contentType: BuildMediaAssetV1["contentType"];
  if (input.contentType === "image/jpeg" || input.contentType === "image/png") {
    contentType = input.contentType;
  } else {
    fail("SNAPSHOT_INVALID");
  }
  const width = positiveInteger(input.width);
  const height = positiveInteger(input.height);
  const byteSize = positiveInteger(input.byteSize);
  if (
    width > MAX_MEDIA_AXIS ||
    height > MAX_MEDIA_AXIS ||
    width * height > MAX_MEDIA_PIXELS ||
    byteSize > MAX_MEDIA_BYTES
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return { id: uuid(input.id), contentType, byteSize, width, height };
}

function parseArray<T>(value: unknown, parser: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) fail("SNAPSHOT_INVALID");
  return value.map(parser);
}

function uniqueIds(items: readonly Readonly<{ id: string }>[]): Set<string> {
  const ids = new Set<string>();
  for (const item of items) {
    if (ids.has(item.id)) fail("SNAPSHOT_INVALID");
    ids.add(item.id);
  }
  return ids;
}

function referencedMedia(snapshot: BuildSnapshotV2): Set<string> {
  const ids = new Set<string>();
  const add = (id: string | null): void => {
    if (id !== null) ids.add(id);
  };
  add(snapshot.shop.heroImageId);
  add(snapshot.shop.groomerImageId);
  add(snapshot.shop.ogImageId);
  for (const item of snapshot.galleryItems) {
    add(item.coverImageId);
    add(item.beforeImageId);
    add(item.afterImageId);
  }
  return ids;
}

function validateSemantics(snapshot: BuildSnapshotV2): void {
  const breedIds = uniqueIds(snapshot.breeds);
  const serviceIds = uniqueIds(snapshot.services);
  uniqueIds(snapshot.galleryItems);
  uniqueIds(snapshot.notices);
  const mediaIds = uniqueIds(snapshot.mediaAssets);
  const generatedAt = instantKey(snapshot.generatedAt);

  for (const item of snapshot.galleryItems) {
    if (
      !breedIds.has(item.breedId) ||
      !serviceIds.has(item.primaryServiceId) ||
      instantKey(item.publishedAt) > generatedAt
    ) {
      fail("SNAPSHOT_INVALID");
    }
  }
  for (const notice of snapshot.notices) {
    const publishedAt = instantKey(notice.publishedAt);
    const expiresAt = notice.expiresAt === null ? null : instantKey(notice.expiresAt);
    if (
      publishedAt > generatedAt ||
      (expiresAt !== null &&
        (expiresAt <= publishedAt || expiresAt <= generatedAt))
    ) {
      fail("SNAPSHOT_INVALID");
    }
  }
  const referenced = referencedMedia(snapshot);
  if (
    referenced.size !== mediaIds.size ||
    [...referenced].some((id) => !mediaIds.has(id))
  ) {
    fail("SNAPSHOT_INVALID");
  }
}

export function parseBuildSnapshotV2(value: unknown): BuildSnapshotV2 {
  const input = record(value);
  exact(input, TOP_LEVEL_KEYS);
  if (input.schemaVersion !== 2) fail("SNAPSHOT_INVALID");
  const snapshot: BuildSnapshotV2 = {
    schemaVersion: 2,
    contentRevision: nonNegativeInt64Decimal(input.contentRevision),
    publishGeneration: positiveInt64Decimal(input.publishGeneration),
    generatedAt: instant(input.generatedAt),
    shop: parseShop(input.shop),
    services: parseArray(input.services, parseService),
    breeds: parseArray(input.breeds, parseBreed),
    galleryItems: parseArray(input.galleryItems, parseGallery),
    notices: parseArray(input.notices, parseNotice),
    mediaAssets: parseArray(input.mediaAssets, parseMedia),
  };
  validateSemantics(snapshot);
  return snapshot;
}

export const BUILD_MEDIA_LIMITS = {
  maxBytes: MAX_MEDIA_BYTES,
  maxAxis: MAX_MEDIA_AXIS,
  maxPixels: MAX_MEDIA_PIXELS,
} as const;
