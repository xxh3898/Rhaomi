import { createHash, randomUUID } from "node:crypto";
import {
  lstat,
  mkdir,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { basename, dirname, join, parse, resolve } from "node:path";

import sharp from "sharp";

import {
  BUILD_MEDIA_LIMITS,
  parseBuildSnapshotV2,
  type BuildMediaAssetV1,
  type BuildSnapshotV2,
} from "./contracts.mts";
import {
  BuildTransformError,
  MediaContentNotFoundError,
  fail,
  isBuildTransformError,
} from "./errors.mts";
import {
  DERIVATIVE_PROFILE_ORDER,
  DERIVATIVE_PROFILES,
  type DerivativeFormat,
  type DerivativeProfile,
} from "./profiles.mts";

export type MediaContent = Readonly<{
  contentType: string;
  bytes: Uint8Array;
}>;

export interface MediaContentProvider {
  get(mediaId: string): Promise<MediaContent>;
}

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

export type GeneratedContentV2 = Omit<BuildSnapshotV2, "mediaAssets">;

export type TransformResult = Readonly<{
  content: GeneratedContentV2;
  mediaManifest: PublicMediaManifestV2;
  publicFileCount: number;
}>;

type ValidatedMedia = Readonly<{
  asset: BuildMediaAssetV1;
  bytes: Buffer;
  displayWidth: number;
}>;

type EncodedVariant = Readonly<{
  bytes: Buffer;
  width: number;
  height: number;
}>;

const PROFILE_INDEX = new Map(
  DERIVATIVE_PROFILE_ORDER.map((profile, index) => [profile, index]),
);

function isJpegSignature(bytes: Buffer): boolean {
  return (
    bytes.length >= 3 &&
    bytes[0] === 0xff &&
    bytes[1] === 0xd8 &&
    bytes[2] === 0xff
  );
}

function isPngSignature(bytes: Buffer): boolean {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  return (
    bytes.length >= signature.length &&
    signature.every((value, index) => bytes[index] === value)
  );
}

function hasPngAnimationControl(bytes: Buffer): boolean {
  let offset = 8;
  while (offset + 12 <= bytes.length) {
    const dataLength = bytes.readUInt32BE(offset);
    const nextOffset = offset + 12 + dataLength;
    if (nextOffset > bytes.length || nextOffset <= offset) return true;
    const type = bytes.subarray(offset + 4, offset + 8).toString("ascii");
    if (type === "acTL") return true;
    if (type === "IEND") return false;
    offset = nextOffset;
  }
  return false;
}

function isAvifSignature(bytes: Buffer): boolean {
  return (
    bytes.length >= 12 &&
    bytes.subarray(4, 8).toString("ascii") === "ftyp" &&
    ["avif", "avis"].includes(bytes.subarray(8, 12).toString("ascii"))
  );
}

function displayWidth(
  width: number,
  height: number,
  orientation: number | undefined,
): number {
  return orientation !== undefined && orientation >= 5 && orientation <= 8
    ? height
    : width;
}

async function providerContent(
  provider: MediaContentProvider,
  mediaId: string,
): Promise<MediaContent> {
  try {
    return await provider.get(mediaId);
  } catch (error) {
    if (error instanceof MediaContentNotFoundError) {
      fail("MEDIA_NOT_FOUND");
    }
    if (isBuildTransformError(error)) throw error;
    fail("MEDIA_NOT_FOUND");
  }
}

async function validateMedia(
  asset: BuildMediaAssetV1,
  provider: MediaContentProvider,
): Promise<ValidatedMedia> {
  const content = await providerContent(provider, asset.id);
  if (
    typeof content !== "object" ||
    content === null ||
    typeof content.contentType !== "string" ||
    !(content.bytes instanceof Uint8Array)
  ) {
    fail("MEDIA_INVALID");
  }
  const bytes = Buffer.from(content.bytes);
  if (
    content.contentType !== asset.contentType ||
    bytes.length !== asset.byteSize ||
    bytes.length === 0 ||
    bytes.length > BUILD_MEDIA_LIMITS.maxBytes ||
    (asset.contentType === "image/jpeg" && !isJpegSignature(bytes)) ||
    (asset.contentType === "image/png" &&
      (!isPngSignature(bytes) || hasPngAnimationControl(bytes)))
  ) {
    fail("MEDIA_INVALID");
  }

  try {
    const metadata = await sharp(bytes, {
      failOn: "error",
      limitInputPixels: BUILD_MEDIA_LIMITS.maxPixels,
      sequentialRead: true,
    }).metadata();
    const expectedFormat =
      asset.contentType === "image/jpeg" ? "jpeg" : "png";
    if (
      metadata.format !== expectedFormat ||
      metadata.width !== asset.width ||
      metadata.height !== asset.height ||
      (metadata.pages ?? 1) !== 1 ||
      metadata.width <= 0 ||
      metadata.height <= 0 ||
      metadata.width > BUILD_MEDIA_LIMITS.maxAxis ||
      metadata.height > BUILD_MEDIA_LIMITS.maxAxis ||
      metadata.width * metadata.height > BUILD_MEDIA_LIMITS.maxPixels
    ) {
      fail("MEDIA_INVALID");
    }
    return {
      asset,
      bytes,
      displayWidth: displayWidth(
        metadata.width,
        metadata.height,
        metadata.orientation,
      ),
    };
  } catch (error) {
    if (isBuildTransformError(error)) throw error;
    fail("MEDIA_INVALID");
  }
}

function outputWidths(
  candidates: readonly number[],
  sourceWidth: number,
): readonly number[] {
  return [...new Set(candidates.map((width) => Math.min(width, sourceWidth)))].sort(
    (left, right) => left - right,
  );
}

async function encodeVariant(
  media: ValidatedMedia,
  width: number,
  format: DerivativeFormat,
): Promise<EncodedVariant> {
  try {
    let pipeline = sharp(media.bytes, {
      failOn: "error",
      limitInputPixels: BUILD_MEDIA_LIMITS.maxPixels,
      sequentialRead: true,
    })
      .rotate()
      .resize({
        width,
        fit: "inside",
        withoutEnlargement: true,
      })
      .toColourspace("srgb");

    if (format === "avif") {
      pipeline = pipeline.avif({ quality: 50, effort: 4 });
    } else if (format === "webp") {
      pipeline = pipeline.webp({ quality: 80, effort: 4 });
    } else {
      pipeline = pipeline
        .flatten({ background: { r: 255, g: 255, b: 255 } })
        .jpeg({ quality: 82, progressive: true, mozjpeg: false });
    }

    const encoded = await pipeline.toBuffer({ resolveWithObject: true });
    const metadata = await sharp(encoded.data, {
      failOn: "error",
      limitInputPixels: BUILD_MEDIA_LIMITS.maxPixels,
    }).metadata();
    const expectedSharpFormat = format === "avif" ? "heif" : format;
    if (
      metadata.format !== expectedSharpFormat ||
      (format === "avif" && !isAvifSignature(encoded.data)) ||
      metadata.width !== encoded.info.width ||
      metadata.height !== encoded.info.height ||
      (metadata.pages ?? 1) !== 1 ||
      metadata.width === undefined ||
      metadata.height === undefined ||
      metadata.width > media.displayWidth ||
      metadata.exif !== undefined ||
      metadata.iptc !== undefined ||
      metadata.xmp !== undefined ||
      metadata.orientation !== undefined ||
      (metadata.comments?.length ?? 0) > 0
    ) {
      fail("MEDIA_TRANSFORM_FAILED");
    }
    return {
      bytes: encoded.data,
      width: metadata.width,
      height: metadata.height,
    };
  } catch (error) {
    if (isBuildTransformError(error)) throw error;
    fail("MEDIA_TRANSFORM_FAILED");
  }
}

function profileBindings(
  snapshot: BuildSnapshotV2,
): ReadonlyMap<string, ReadonlySet<DerivativeProfile>> {
  const bindings = new Map<string, Set<DerivativeProfile>>();
  const add = (mediaId: string | null, profile: DerivativeProfile): void => {
    if (mediaId === null) return;
    const profiles = bindings.get(mediaId) ?? new Set<DerivativeProfile>();
    profiles.add(profile);
    bindings.set(mediaId, profiles);
  };

  add(snapshot.shop.heroImageId, "HERO");
  add(snapshot.shop.groomerImageId, "PUBLIC_FALLBACK");
  add(snapshot.shop.ogImageId, "PUBLIC_FALLBACK");
  for (const item of snapshot.galleryItems) {
    add(item.coverImageId, "GALLERY_CARD");
    add(item.coverImageId, "GALLERY_LARGE");
    add(item.beforeImageId, "GALLERY_LARGE");
    add(item.afterImageId, "GALLERY_LARGE");
  }
  return bindings;
}

function generatedContent(snapshot: BuildSnapshotV2): GeneratedContentV2 {
  return {
    schemaVersion: snapshot.schemaVersion,
    contentRevision: snapshot.contentRevision,
    publishGeneration: snapshot.publishGeneration,
    generatedAt: snapshot.generatedAt,
    shop: snapshot.shop,
    services: snapshot.services,
    breeds: snapshot.breeds,
    galleryItems: snapshot.galleryItems,
    notices: snapshot.notices,
  };
}

function json(value: unknown): string {
  return `${JSON.stringify(value, null, 2)}\n`;
}

async function pathExists(path: string): Promise<boolean> {
  try {
    await lstat(path);
    return true;
  } catch (error) {
    if (
      typeof error === "object" &&
      error !== null &&
      "code" in error &&
      error.code === "ENOENT"
    ) {
      return false;
    }
    fail("OUTPUT_FAILED");
  }
}

function safeTarget(value: string): string {
  const target = resolve(value);
  if (target === parse(target).root || basename(target).length === 0) {
    fail("OUTPUT_FAILED");
  }
  return target;
}

async function createArtifacts(
  snapshot: BuildSnapshotV2,
  mediaContentProvider: MediaContentProvider,
  tempRoot: string,
): Promise<TransformResult> {
  const mediaDirectory = join(tempRoot, "public", "generated", "media");
  const generatedDirectory = join(tempRoot, "src", "generated");
  await mkdir(mediaDirectory, { recursive: true });
  await mkdir(generatedDirectory, { recursive: true });

  const bindings = profileBindings(snapshot);
  const assets = new Map(snapshot.mediaAssets.map((asset) => [asset.id, asset]));
  const mediaItems: PublicMediaManifestItem[] = [];
  const written = new Map<string, Buffer>();

  for (const mediaId of [...bindings.keys()].sort()) {
    const asset = assets.get(mediaId);
    if (asset === undefined) fail("SNAPSHOT_INVALID");
    const media = await validateMedia(asset, mediaContentProvider);
    const profiles = [...(bindings.get(mediaId) ?? [])].sort(
      (left, right) =>
        (PROFILE_INDEX.get(left) ?? Number.MAX_SAFE_INTEGER) -
        (PROFILE_INDEX.get(right) ?? Number.MAX_SAFE_INTEGER),
    );
    const variants: PublicMediaVariant[] = [];

    for (const profile of profiles) {
      const definition = DERIVATIVE_PROFILES[profile];
      const widths = outputWidths(definition.widths, media.displayWidth);
      for (const format of definition.formats) {
        for (const width of widths) {
          const encoded = await encodeVariant(media, width, format);
          const hash = createHash("sha256").update(encoded.bytes).digest("hex");
          const filename = `${hash}.${format}`;
          const existing = written.get(filename);
          if (existing !== undefined && !existing.equals(encoded.bytes)) {
            fail("MEDIA_TRANSFORM_FAILED");
          }
          if (existing === undefined) {
            await writeFile(join(mediaDirectory, filename), encoded.bytes, {
              flag: "wx",
            });
            written.set(filename, encoded.bytes);
          }
          variants.push({
            profile,
            format,
            width: encoded.width,
            height: encoded.height,
            byteSize: encoded.bytes.length,
            publicPath: `/generated/media/${filename}`,
          });
        }
      }
    }
    mediaItems.push({ mediaId, variants });
  }

  const content = generatedContent(snapshot);
  const mediaManifest: PublicMediaManifestV2 = {
    schemaVersion: 2,
    contentRevision: snapshot.contentRevision,
    publishGeneration: snapshot.publishGeneration,
    items: mediaItems,
  };
  await writeFile(join(generatedDirectory, "content.json"), json(content), {
    flag: "wx",
  });
  await writeFile(
    join(generatedDirectory, "media-manifest.json"),
    json(mediaManifest),
    { flag: "wx" },
  );
  return { content, mediaManifest, publicFileCount: written.size };
}

export async function transformBuildSnapshot(input: Readonly<{
  snapshot: unknown;
  mediaContentProvider: MediaContentProvider;
  stagingOutputRoot: string;
}>): Promise<TransformResult> {
  const snapshot = parseBuildSnapshotV2(input.snapshot);
  const target = safeTarget(input.stagingOutputRoot);
  const parent = dirname(target);
  const tempRoot = join(parent, `.${basename(target)}.tmp-${randomUUID()}`);
  let tempCreated = false;

  try {
    await mkdir(parent, { recursive: true });
    if (await pathExists(target)) fail("OUTPUT_FAILED");
    await mkdir(tempRoot, { recursive: false });
    tempCreated = true;
    const result = await createArtifacts(
      snapshot,
      input.mediaContentProvider,
      tempRoot,
    );
    await rename(tempRoot, target);
    tempCreated = false;
    return result;
  } catch (error) {
    if (tempCreated) {
      try {
        await rm(tempRoot, { recursive: true, force: true });
      } catch {
        throw new BuildTransformError("OUTPUT_FAILED");
      }
    }
    if (isBuildTransformError(error)) throw error;
    throw new BuildTransformError("OUTPUT_FAILED");
  }
}
