// @vitest-environment node

import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import sharp from "sharp";
import { afterEach, describe, expect, it } from "vitest";

import type { BuildSnapshotV1 } from "./contracts.mts";
import {
  BuildTransformError,
  MediaContentNotFoundError,
} from "./errors.mts";
import {
  transformBuildSnapshot,
  type MediaContent,
  type MediaContentProvider,
  type TransformResult,
} from "./transformer.mts";
import {
  IDS,
  galleryItem,
  mediaAsset,
  snapshotFixture,
} from "./test-fixtures";

const createdRoots: string[] = [];

afterEach(async () => {
  await Promise.all(
    createdRoots.splice(0).map((path) => rm(path, { recursive: true, force: true })),
  );
});

async function taskRoot(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), "rhaomi-transformer-test-"));
  createdRoots.push(path);
  return path;
}

class CountingProvider implements MediaContentProvider {
  readonly #content: ReadonlyMap<string, MediaContent>;
  readonly counts = new Map<string, number>();

  constructor(content: ReadonlyMap<string, MediaContent>) {
    this.#content = content;
  }

  async get(mediaId: string): Promise<MediaContent> {
    this.counts.set(mediaId, (this.counts.get(mediaId) ?? 0) + 1);
    const content = this.#content.get(mediaId);
    if (content === undefined) throw new MediaContentNotFoundError();
    return content;
  }
}

async function syntheticJpeg(width: number, height: number): Promise<Buffer> {
  return sharp({
    create: {
      width,
      height,
      channels: 3,
      background: { r: 212, g: 102, b: 84 },
    },
  })
    .jpeg({ quality: 90 })
    .withExif({
      IFD0: {
        Copyright: "synthetic-private-copyright",
        ImageDescription: "synthetic-private-description",
      },
      IFD3: {
        GPSLatitudeRef: "N",
        GPSLatitude: "37/1 33/1 0/1",
        GPSLongitudeRef: "E",
        GPSLongitude: "126/1 59/1 0/1",
      },
    })
    .withXmp(
      '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description synthetic="private-xmp" /></rdf:RDF></x:xmpmeta>',
    )
    .toBuffer();
}

async function syntheticPng(width: number, height: number): Promise<Buffer> {
  return sharp({
    create: {
      width,
      height,
      channels: 4,
      background: { r: 42, g: 132, b: 170, alpha: 0.7 },
    },
  })
    .png()
    .withXmp(
      '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description synthetic="private-png-xmp" /></rdf:RDF></x:xmpmeta>',
    )
    .toBuffer();
}

async function syntheticOrientedJpeg(): Promise<Buffer> {
  return sharp({
    create: {
      width: 320,
      height: 640,
      channels: 3,
      background: { r: 88, g: 132, b: 74 },
    },
  })
    .jpeg({ quality: 90 })
    .withMetadata({ orientation: 6 })
    .toBuffer();
}

function pngWithAnimationControl(png: Buffer): Buffer {
  const animationControl = Buffer.alloc(20);
  animationControl.writeUInt32BE(8, 0);
  animationControl.write("acTL", 4, "ascii");
  animationControl.writeUInt32BE(2, 8);
  animationControl.writeUInt32BE(0, 12);
  return Buffer.concat([png.subarray(0, 33), animationControl, png.subarray(33)]);
}

function mediaSnapshot(jpeg: Buffer, png: Buffer): BuildSnapshotV1 {
  const base = snapshotFixture();
  return snapshotFixture({
    shop: {
      ...base.shop,
      heroImageId: IDS.mediaJpeg,
      heroImageAltText: "합성 Hero 이미지",
      groomerImageId: IDS.mediaPng,
      groomerImageAltText: "합성 미용사 이미지",
      ogImageId: null,
    },
    galleryItems: [
      galleryItem({
        id: IDS.galleryA,
        coverImageId: IDS.mediaJpeg,
        beforeImageId: IDS.mediaPng,
      }),
      galleryItem({
        id: IDS.galleryB,
        coverImageId: IDS.mediaPng,
        beforeImageId: null,
        sortOrder: 1,
      }),
    ],
    mediaAssets: [
      mediaAsset(IDS.mediaJpeg, "image/jpeg", jpeg.length, 1_920, 1_080),
      mediaAsset(IDS.mediaPng, "image/png", png.length, 320, 240),
    ],
  });
}

function singleJpegSnapshot(
  jpeg: Buffer,
  width = 640,
  height = 360,
): BuildSnapshotV1 {
  const base = snapshotFixture();
  return snapshotFixture({
    shop: {
      ...base.shop,
      heroImageId: null,
      heroImageAltText: null,
      groomerImageId: IDS.mediaJpeg,
      groomerImageAltText: "합성 fallback 이미지",
      ogImageId: null,
    },
    galleryItems: [],
    mediaAssets: [
      mediaAsset(IDS.mediaJpeg, "image/jpeg", jpeg.length, width, height),
    ],
  });
}

async function generatedFile(
  stagingRoot: string,
  publicPath: string,
): Promise<Buffer> {
  return readFile(join(stagingRoot, "public", publicPath.replace(/^\//u, "")));
}

async function expectSafeFailure(
  operation: Promise<unknown>,
  code: BuildTransformError["code"],
): Promise<void> {
  await expect(operation).rejects.toEqual(
    expect.objectContaining<Partial<BuildTransformError>>({ code }),
  );
}

async function transform(
  snapshot: BuildSnapshotV1,
  provider: MediaContentProvider,
  stagingRoot: string,
): Promise<TransformResult> {
  return transformBuildSnapshot({
    snapshot,
    mediaContentProvider: provider,
    stagingOutputRoot: stagingRoot,
  });
}

describe("build snapshot media transformer", () => {
  it("JPEG/PNG를 metadata-free AVIF/WebP/JPEG responsive derivative와 deterministic manifest로 만든다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg(1_920, 1_080);
    const png = await syntheticPng(320, 240);
    const jpegMetadata = await sharp(jpeg).metadata();
    const pngMetadata = await sharp(png).metadata();
    expect(jpegMetadata.exif).toBeDefined();
    expect(jpegMetadata.xmp).toBeDefined();
    expect(pngMetadata.xmp).toBeDefined();

    const snapshot = mediaSnapshot(jpeg, png);
    const providerA = new CountingProvider(
      new Map([
        [IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }],
        [IDS.mediaPng, { contentType: "image/png", bytes: png }],
      ]),
    );
    const outputA = join(root, "staging-a");
    const first = await transform(snapshot, providerA, outputA);

    expect(providerA.counts.get(IDS.mediaJpeg)).toBe(1);
    expect(providerA.counts.get(IDS.mediaPng)).toBe(1);
    expect(first.content.services.map((item) => item.id)).toEqual([
      IDS.serviceB,
      IDS.serviceA,
    ]);
    expect(JSON.stringify(first.content)).not.toMatch(
      /mediaAssets|storage|sha256|original|authorization|claim|lease/iu,
    );

    const jpegItem = first.mediaManifest.items.find(
      (item) => item.mediaId === IDS.mediaJpeg,
    );
    const pngItem = first.mediaManifest.items.find(
      (item) => item.mediaId === IDS.mediaPng,
    );
    expect(jpegItem).toBeDefined();
    expect(pngItem).toBeDefined();
    expect(
      jpegItem?.variants
        .filter((variant) => variant.profile === "HERO" && variant.format === "avif")
        .map((variant) => variant.width),
    ).toEqual([768, 1280, 1920]);
    expect(
      jpegItem?.variants
        .filter(
          (variant) =>
            variant.profile === "GALLERY_CARD" && variant.format === "webp",
        )
        .map((variant) => variant.width),
    ).toEqual([360, 640, 960]);
    expect(
      jpegItem?.variants
        .filter(
          (variant) =>
            variant.profile === "GALLERY_LARGE" && variant.format === "jpeg",
        )
        .map((variant) => variant.width),
    ).toEqual([768, 1200, 1600]);
    expect(new Set(pngItem?.variants.map((variant) => variant.format))).toEqual(
      new Set(["avif", "webp", "jpeg"]),
    );
    expect(pngItem?.variants.every((variant) => variant.width === 320)).toBe(true);

    const publicPaths = [
      ...new Set(
        first.mediaManifest.items.flatMap((item) =>
          item.variants.map((variant) => variant.publicPath),
        ),
      ),
    ];
    expect(publicPaths).toHaveLength(first.publicFileCount);
    for (const publicPath of publicPaths) {
      expect(publicPath).toMatch(
        /^\/generated\/media\/[0-9a-f]{64}\.(?:avif|webp|jpeg)$/u,
      );
      expect(publicPath).not.toContain("..");
      const bytes = await generatedFile(outputA, publicPath);
      const expectedHash = publicPath.match(/[0-9a-f]{64}/u)?.[0];
      expect(createHash("sha256").update(bytes).digest("hex")).toBe(expectedHash);
      const metadata = await sharp(bytes).metadata();
      expect(metadata.format).toBe(
        publicPath.endsWith(".avif")
          ? "heif"
          : publicPath.endsWith(".webp")
            ? "webp"
            : "jpeg",
      );
      expect(metadata.exif).toBeUndefined();
      expect(metadata.iptc).toBeUndefined();
      expect(metadata.xmp).toBeUndefined();
      expect(metadata.orientation).toBeUndefined();
      expect(metadata.comments ?? []).toHaveLength(0);
      expect(metadata.space).toBe("srgb");
    }

    expect(
      jpegItem?.variants.every(
        (variant) => Math.abs(variant.width / variant.height - 16 / 9) < 0.01,
      ),
    ).toBe(true);
    expect(
      pngItem?.variants.every(
        (variant) => Math.abs(variant.width / variant.height - 4 / 3) < 0.01,
      ),
    ).toBe(true);

    const providerB = new CountingProvider(
      new Map([
        [IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }],
        [IDS.mediaPng, { contentType: "image/png", bytes: png }],
      ]),
    );
    const outputB = join(root, "staging-b");
    await transform(snapshot, providerB, outputB);
    expect(
      await readFile(join(outputB, "src/generated/content.json"), "utf8"),
    ).toBe(await readFile(join(outputA, "src/generated/content.json"), "utf8"));
    expect(
      await readFile(join(outputB, "src/generated/media-manifest.json"), "utf8"),
    ).toBe(
      await readFile(join(outputA, "src/generated/media-manifest.json"), "utf8"),
    );
    const filesA = (
      await readdir(join(outputA, "public/generated/media"))
    ).sort();
    const filesB = (
      await readdir(join(outputB, "public/generated/media"))
    ).sort();
    expect(filesB).toEqual(filesA);
    for (const filename of filesA) {
      expect(await readFile(join(outputB, "public/generated/media", filename))).toEqual(
        await readFile(join(outputA, "public/generated/media", filename)),
      );
    }
  }, 120_000);

  it("EXIF orientation을 적용한 뒤 sRGB·metadata-free output으로 고정한다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticOrientedJpeg();
    const sourceMetadata = await sharp(jpeg).metadata();
    expect(sourceMetadata.width).toBe(320);
    expect(sourceMetadata.height).toBe(640);
    expect(sourceMetadata.orientation).toBe(6);

    const result = await transform(
      singleJpegSnapshot(jpeg, 320, 640),
      new CountingProvider(
        new Map([[IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }]]),
      ),
      join(root, "oriented-staging"),
    );
    const variants = result.mediaManifest.items[0]?.variants ?? [];
    expect(variants).toHaveLength(1);
    expect(variants[0]).toEqual(
      expect.objectContaining({
        profile: "PUBLIC_FALLBACK",
        format: "jpeg",
        width: 640,
        height: 320,
      }),
    );
    const output = await generatedFile(
      join(root, "oriented-staging"),
      variants[0].publicPath,
    );
    const outputMetadata = await sharp(output).metadata();
    expect(outputMetadata.orientation).toBeUndefined();
    expect(outputMetadata.exif).toBeUndefined();
    expect(outputMetadata.xmp).toBeUndefined();
    expect(outputMetadata.space).toBe("srgb");
  });

  it.each([
    ["declared type mismatch", "image/png", Buffer.from([0xff, 0xd8, 0xff, 0x00])],
    ["corrupt/truncated JPEG", "image/jpeg", Buffer.from([0xff, 0xd8, 0xff, 0x00])],
  ])("%s를 MEDIA_INVALID로 거부하고 temp를 정리한다", async (_name, contentType, bytes) => {
    const root = await taskRoot();
    const snapshot = singleJpegSnapshot(bytes);
    const output = join(root, "failed-staging");
    const provider = new CountingProvider(
      new Map([[IDS.mediaJpeg, { contentType, bytes }]]),
    );

    await expectSafeFailure(transform(snapshot, provider, output), "MEDIA_INVALID");
    expect(await readdir(root)).toEqual([]);
  });

  it("byteSize와 decoded dimension mismatch를 MEDIA_INVALID로 거부한다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg(640, 360);
    const provider = new CountingProvider(
      new Map([[IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }]]),
    );
    const byteMismatch = singleJpegSnapshot(jpeg);
    const dimensionMismatch = singleJpegSnapshot(jpeg);

    await expectSafeFailure(
      transform(
        {
          ...byteMismatch,
          mediaAssets: [
            { ...byteMismatch.mediaAssets[0], byteSize: jpeg.length + 1 },
          ],
        },
        provider,
        join(root, "byte-mismatch"),
      ),
      "MEDIA_INVALID",
    );
    await expectSafeFailure(
      transform(
        {
          ...dimensionMismatch,
          mediaAssets: [
            { ...dimensionMismatch.mediaAssets[0], width: 639 },
          ],
        },
        provider,
        join(root, "dimension-mismatch"),
      ),
      "MEDIA_INVALID",
    );
    expect((await readdir(root)).filter((name) => name.includes(".tmp-"))).toEqual([]);
  });

  it("PNG animation control을 multi-page 입력으로 거부한다", async () => {
    const root = await taskRoot();
    const png = await syntheticPng(320, 240);
    const animated = pngWithAnimationControl(png);
    const base = snapshotFixture();
    const snapshot = snapshotFixture({
      shop: {
        ...base.shop,
        heroImageId: null,
        heroImageAltText: null,
        groomerImageId: IDS.mediaPng,
        groomerImageAltText: "합성 PNG 이미지",
        ogImageId: null,
      },
      galleryItems: [],
      mediaAssets: [
        mediaAsset(IDS.mediaPng, "image/png", animated.length, 320, 240),
      ],
    });

    await expectSafeFailure(
      transform(
        snapshot,
        new CountingProvider(
          new Map([[IDS.mediaPng, { contentType: "image/png", bytes: animated }]]),
        ),
        join(root, "animated-staging"),
      ),
      "MEDIA_INVALID",
    );
    expect(await readdir(root)).toEqual([]);
  });

  it("중간 media 실패 시 incomplete staging을 제거한다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg(1_920, 1_080);
    const png = await syntheticPng(320, 240);
    const snapshot = mediaSnapshot(jpeg, png);
    const provider = new CountingProvider(
      new Map([
        [IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }],
        [IDS.mediaPng, { contentType: "image/png", bytes: Buffer.from(png.subarray(0, 16)) }],
      ]),
    );

    await expectSafeFailure(
      transform(snapshot, provider, join(root, "failed-staging")),
      "MEDIA_INVALID",
    );
    expect(await readdir(root)).toEqual([]);
  }, 120_000);

  it("기존 성공 staging target을 덮거나 손상시키지 않는다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg(640, 360);
    const snapshot = singleJpegSnapshot(jpeg);
    const output = join(root, "existing-staging");
    await mkdir(output);
    await writeFile(join(output, "marker.txt"), "previous-success\n");

    await expectSafeFailure(
      transform(
        snapshot,
        new CountingProvider(
          new Map([[IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }]]),
        ),
        output,
      ),
      "OUTPUT_FAILED",
    );
    expect(await readFile(join(output, "marker.txt"), "utf8")).toBe(
      "previous-success\n",
    );
    expect((await readdir(root)).filter((name) => name.includes(".tmp-"))).toEqual([]);
  });

  it("output parent write failure를 safe OUTPUT_FAILED로 종료하고 success artifact를 만들지 않는다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg(640, 360);
    const blockedParent = join(root, "not-a-directory");
    await writeFile(blockedParent, "blocked\n");

    await expectSafeFailure(
      transform(
        singleJpegSnapshot(jpeg),
        new CountingProvider(
          new Map([[IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }]]),
        ),
        join(blockedParent, "staging"),
      ),
      "OUTPUT_FAILED",
    );
    expect(await readFile(blockedParent, "utf8")).toBe("blocked\n");
    expect((await readdir(root)).filter((name) => name.includes(".tmp-"))).toEqual([]);
  });

  it("CLI가 성공 staging을 만들고 실패 시 safe code만 non-zero로 반환한다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg(640, 360);
    const snapshot = singleJpegSnapshot(jpeg);
    const snapshotPath = join(root, "snapshot.json");
    const mediaRoot = join(root, "media-input");
    const output = join(root, "cli-staging");
    await mkdir(mediaRoot);
    await writeFile(snapshotPath, JSON.stringify(snapshot));
    await writeFile(join(mediaRoot, `${IDS.mediaJpeg}.jpg`), jpeg);
    const cli = resolve("scripts/transform-build-snapshot.mts");

    const success = spawnSync(
      process.execPath,
      [
        cli,
        "--snapshot",
        snapshotPath,
        "--media-root",
        mediaRoot,
        "--output",
        output,
      ],
      { encoding: "utf8" },
    );
    expect(success.status).toBe(0);
    expect(success.stdout).toContain(
      "Build snapshot transform completed: contentRevision=14 publishGeneration=7",
    );
    expect(await readdir(join(output, "src/generated"))).toEqual([
      "content.json",
      "media-manifest.json",
    ]);
    expect((await readdir(output)).some((name) => /success/iu.test(name))).toBe(false);

    const failure = spawnSync(process.execPath, [cli], { encoding: "utf8" });
    expect(failure.status).toBe(1);
    expect(failure.stderr.trim()).toBe(
      "SNAPSHOT_INVALID: Build snapshot contract is invalid.",
    );
    expect(failure.stderr).not.toContain(root);
    expect(failure.stdout).not.toContain("completed");
  }, 120_000);
});
