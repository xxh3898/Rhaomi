import {
  DERIVATIVE_PROFILES,
  parseBuildSnapshotV2,
  type BuildSnapshotV2,
  type DerivativeFormat,
  type DerivativeProfile,
} from "../build-transformer/index.mts";
import { fail } from "../build-transformer/errors.mts";

const HASHED_MEDIA_PATH_PATTERN =
  /^\/generated\/media\/([0-9a-f]{64})\.(avif|webp|jpeg)$/u;
const POSITIVE_INTEGER_MAX = Number.MAX_SAFE_INTEGER;

export type GeneratedContentV2 = Omit<BuildSnapshotV2, "mediaAssets">;

export type PublicMediaVariant = Readonly<{
  profile: DerivativeProfile;
  format: DerivativeFormat;
  width: number;
  height: number;
  byteSize: number;
  publicPath: string;
}>;

export type PublicMediaManifestItem = Readonly<{
  mediaId: string;
  variants: readonly PublicMediaVariant[];
}>;

export type PublicMediaManifestV2 = Readonly<{
  schemaVersion: 2;
  contentRevision: string;
  publishGeneration: string;
  items: readonly PublicMediaManifestItem[];
}>;

export type GeneratedArtifactsV2 = Readonly<{
  content: GeneratedContentV2;
  mediaManifest: PublicMediaManifestV2;
}>;

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    fail("SNAPSHOT_INVALID");
  }
  return value as Record<string, unknown>;
}

function exact(value: Record<string, unknown>, keys: readonly string[]): void {
  const actual = Object.keys(value);
  if (
    actual.length !== keys.length ||
    actual.some((key) => !keys.includes(key))
  ) {
    fail("SNAPSHOT_INVALID");
  }
}

function positiveInteger(value: unknown): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value <= 0 ||
    value > POSITIVE_INTEGER_MAX
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return value;
}

function profile(value: unknown): DerivativeProfile {
  if (
    typeof value !== "string" ||
    !Object.prototype.hasOwnProperty.call(DERIVATIVE_PROFILES, value)
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return value as DerivativeProfile;
}

function format(value: unknown, selectedProfile: DerivativeProfile): DerivativeFormat {
  if (
    typeof value !== "string" ||
    !DERIVATIVE_PROFILES[selectedProfile].formats.includes(
      value as DerivativeFormat,
    )
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return value as DerivativeFormat;
}

function parseVariant(value: unknown): PublicMediaVariant {
  const input = record(value);
  exact(input, [
    "profile",
    "format",
    "width",
    "height",
    "byteSize",
    "publicPath",
  ]);
  const selectedProfile = profile(input.profile);
  const selectedFormat = format(input.format, selectedProfile);
  const publicPath = input.publicPath;
  if (
    typeof publicPath !== "string" ||
    !HASHED_MEDIA_PATH_PATTERN.test(publicPath) ||
    !publicPath.endsWith(`.${selectedFormat}`)
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return {
    profile: selectedProfile,
    format: selectedFormat,
    width: positiveInteger(input.width),
    height: positiveInteger(input.height),
    byteSize: positiveInteger(input.byteSize),
    publicPath,
  };
}

function parseManifestItem(value: unknown): PublicMediaManifestItem {
  const input = record(value);
  exact(input, ["mediaId", "variants"]);
  if (!Array.isArray(input.variants) || input.variants.length === 0) {
    fail("SNAPSHOT_INVALID");
  }
  const variants = input.variants.map(parseVariant);
  const bindingKeys = new Set<string>();
  for (const variant of variants) {
    const key = `${variant.profile}:${variant.format}:${variant.width}`;
    if (bindingKeys.has(key)) fail("SNAPSHOT_INVALID");
    bindingKeys.add(key);
  }
  if (typeof input.mediaId !== "string") fail("SNAPSHOT_INVALID");
  return { mediaId: input.mediaId, variants };
}

function parseManifest(value: unknown): PublicMediaManifestV2 {
  const input = record(value);
  exact(input, [
    "schemaVersion",
    "contentRevision",
    "publishGeneration",
    "items",
  ]);
  if (input.schemaVersion !== 2 || !Array.isArray(input.items)) {
    fail("SNAPSHOT_INVALID");
  }
  const items = input.items.map(parseManifestItem);
  const ids = new Set<string>();
  for (const item of items) {
    if (ids.has(item.mediaId)) fail("SNAPSHOT_INVALID");
    ids.add(item.mediaId);
  }
  if (
    typeof input.contentRevision !== "string" ||
    typeof input.publishGeneration !== "string"
  ) {
    fail("SNAPSHOT_INVALID");
  }
  return {
    schemaVersion: 2,
    contentRevision: input.contentRevision,
    publishGeneration: input.publishGeneration,
    items,
  };
}

function syntheticMediaAssets(manifest: PublicMediaManifestV2) {
  return manifest.items.map((item) => {
    const representative = item.variants[0];
    return {
      id: item.mediaId,
      contentType: "image/jpeg" as const,
      byteSize: representative.byteSize,
      width: representative.width,
      height: representative.height,
    };
  });
}

export function parseGeneratedArtifactsV2(
  contentValue: unknown,
  manifestValue: unknown,
): GeneratedArtifactsV2 {
  const mediaManifest = parseManifest(manifestValue);
  const contentInput = record(contentValue);
  const parsedSnapshot = parseBuildSnapshotV2({
    ...contentInput,
    mediaAssets: syntheticMediaAssets(mediaManifest),
  });
  if (
    parsedSnapshot.contentRevision !== mediaManifest.contentRevision ||
    parsedSnapshot.publishGeneration !== mediaManifest.publishGeneration
  ) {
    fail("SNAPSHOT_INVALID");
  }

  const { mediaAssets, ...content } = parsedSnapshot;
  void mediaAssets;
  return { content, mediaManifest };
}

export function mediaPathHash(publicPath: string): string {
  const match = HASHED_MEDIA_PATH_PATTERN.exec(publicPath);
  if (match === null) fail("SNAPSHOT_INVALID");
  return match[1];
}
