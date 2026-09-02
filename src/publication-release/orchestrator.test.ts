import {
  access,
  mkdir,
  mkdtemp,
  readFile,
  readlink,
  rm,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import sharp from "sharp";
import { afterEach, describe, expect, it } from "vitest";

import {
  IDS,
  galleryItem,
  mediaAsset,
  snapshotFixture,
} from "../build-transformer/test-fixtures";
import type { BuildSnapshotV2 } from "../build-transformer/contracts.mts";
import { releaseIdFor } from "./contracts.mts";
import { publishStaticRelease } from "./orchestrator.mts";

const roots: string[] = [];
const CODE_SHA = "a".repeat(40);
const DIGEST = `sha256:${"b".repeat(64)}`;

afterEach(async () => {
  await Promise.all(
    roots.splice(0).map((root) => rm(root, { recursive: true, force: true })),
  );
});

function snapshot(
  generation: string,
  options: Readonly<{
    notices?: BuildSnapshotV2["notices"];
    media?: Readonly<{ jpeg: Buffer; png: Buffer }>;
  }> = {},
): BuildSnapshotV2 {
  const base = snapshotFixture();
  const withMedia = options.media !== undefined;
  return {
    ...base,
    contentRevision: generation,
    publishGeneration: generation,
    shop: {
      ...base.shop,
      heroImageId: withMedia ? IDS.mediaJpeg : null,
      heroImageAltText: withMedia ? "합성 Hero 이미지" : null,
      groomerImageId: withMedia ? IDS.mediaPng : null,
      groomerImageAltText: withMedia ? "합성 미용사 이미지" : null,
      ogImageId: withMedia ? IDS.mediaJpeg : null,
    },
    galleryItems: withMedia
      ? [
          galleryItem({
            coverImageId: IDS.mediaJpeg,
            beforeImageId: IDS.mediaPng,
            afterImageId: null,
          }),
        ]
      : [],
    mediaAssets: withMedia
      ? [
          mediaAsset(
            IDS.mediaJpeg,
            "image/jpeg",
            options.media?.jpeg.length ?? 0,
            320,
            180,
          ),
          mediaAsset(
            IDS.mediaPng,
            "image/png",
            options.media?.png.length ?? 0,
            160,
            120,
          ),
        ]
      : [],
    notices: options.notices ?? [
      {
        ...base.notices[0],
        bodyMarkdown:
          "## 합성 공지\n\n**합성 Markdown 본문**\n\n<script>alert(1)</script>\n\n![원격](https://evil.example/x.jpg)",
      },
    ],
  };
}

function snapshotFetch(
  value: BuildSnapshotV2,
  media: ReadonlyMap<
    string,
    Readonly<{ contentType: string; bytes: Buffer }>
  > = new Map(),
) {
  return async (input: string | URL | Request) => {
    const url = input instanceof Request ? input.url : String(input);
    if (url.includes("/api/build/snapshot?")) {
      const body = JSON.stringify(value);
      return new Response(body, {
        status: 200,
        headers: {
          "content-type": "application/json",
          "content-length": String(Buffer.byteLength(body)),
        },
      });
    }
    const mediaId = /\/api\/build\/media\/([0-9a-f-]+)\/content\?/u.exec(url)?.[1];
    const content = mediaId === undefined ? undefined : media.get(mediaId);
    if (content !== undefined) {
      return new Response(new Uint8Array(content.bytes), {
        status: 200,
        headers: {
          "content-type": content.contentType,
          "content-length": String(content.bytes.length),
        },
      });
    }
    return new Response('{"code":"NOT_FOUND"}', {
      status: 404,
      headers: { "content-type": "application/json" },
    });
  };
}

async function syntheticMedia() {
  const jpeg = await sharp({
    create: {
      width: 320,
      height: 180,
      channels: 3,
      background: { r: 132, g: 84, b: 64 },
    },
  })
    .jpeg({ quality: 90 })
    .toBuffer();
  const png = await sharp({
    create: {
      width: 160,
      height: 120,
      channels: 4,
      background: { r: 48, g: 132, b: 174, alpha: 0.8 },
    },
  })
    .png()
    .toBuffer();
  return { jpeg, png };
}

describe("publishStaticRelease", () => {
  it(
    "preserves full int64 strings, rejects stale switch, rolls back smoke failure, and supports no notices",
    async () => {
      const root = await mkdtemp(join(tmpdir(), "rhaomi-publication-e2e-"));
      roots.push(root);
      const publicRoot = join(root, "public");
      const environment = {
        BUILD_API_INTERNAL_URL: "https://build.example/",
        BUILD_API_CREDENTIAL: "c".repeat(64),
        RHAOMI_PUBLISHER_SOURCE_ROOT: process.cwd(),
        RHAOMI_PUBLISHER_WORK_ROOT: join(root, "state", "publisher"),
        RHAOMI_PUBLIC_RELEASE_ROOT: join(publicRoot, "releases"),
        RHAOMI_PUBLIC_CURRENT_LINK: join(publicRoot, "current"),
        RHAOMI_PUBLIC_PREVIOUS_LINK: join(publicRoot, "previous"),
        PUBLIC_SITE_URL: "https://site.example/",
        RHAOMI_CODE_SHA: CODE_SHA,
        RHAOMI_CODE_IMAGE_TAG: `sha-${CODE_SHA}`,
        RHAOMI_CODE_IMAGE_DIGEST: DIGEST,
        RHAOMI_FLYWAY_VERSION: "10",
        RHAOMI_SBOM_REFERENCE: DIGEST,
        RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS: "120000",
      };
      const large = "9007199254740993";
      const media = await syntheticMedia();
      const largeSnapshot = snapshot(large, { media });
      const mediaResponses = new Map([
        [IDS.mediaJpeg, { contentType: "image/jpeg", bytes: media.jpeg }],
        [IDS.mediaPng, { contentType: "image/png", bytes: media.png }],
      ]);
      const published = await publishStaticRelease({
        publishGeneration: large,
        environment,
        fetchImpl: snapshotFetch(largeSnapshot, mediaResponses),
      });
      expect(published).toMatchObject({
        status: "PUBLISHED",
        retentionStatus: "COMPLETE",
        contentRevision: large,
        publishGeneration: large,
      });
      const currentTarget = await readlink(environment.RHAOMI_PUBLIC_CURRENT_LINK);
      const manifestPath = join(
        dirname(environment.RHAOMI_PUBLIC_CURRENT_LINK),
        currentTarget,
        "..",
        "release-manifest.json",
      );
      const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
      expect(manifest.contentRevision).toBe(large);
      expect(manifest.publishGeneration).toBe(large);
      expect(typeof manifest.publishGeneration).toBe("string");
      const currentSite = join(
        dirname(environment.RHAOMI_PUBLIC_CURRENT_LINK),
        currentTarget,
      );
      const home = await readFile(join(currentSite, "index.html"), "utf8");
      const detail = await readFile(
        join(currentSite, "notices", "synthetic-notice", "index.html"),
        "utf8",
      );
      const sitemap = await readFile(join(currentSite, "sitemap.xml"), "utf8");
      const robots = await readFile(join(currentSite, "robots.txt"), "utf8");
      expect(home).toContain("라오미펫");
      expect(home).toContain("전체 미용");
      expect(home).toContain("미용을 마친 강아지 합성 이미지");
      expect(home).toContain("정기 휴무");
      expect(home).toContain("<picture");
      expect(home).toContain("image/avif");
      expect(home).toMatch(/\/generated\/media\/[0-9a-f]{64}\.jpeg/u);
      expect(home).toMatch(
        /<meta property="og:image" content="https:\/\/site\.example\/generated\/media\/[0-9a-f]{64}\.jpeg"/u,
      );
      expect(home).toContain('href="/notices/synthetic-notice/"');
      expect(home).toContain(
        '<time dateTime="2026-08-29T11:00:00.123456Z">2026-08-29</time>',
      );
      expect(detail).toContain("<strong>합성 Markdown 본문</strong>");
      expect(detail).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
      expect(detail).not.toContain("<script>alert(1)</script>");
      expect(detail).not.toContain("evil.example");
      expect(detail).toContain(
        '<link rel="canonical" href="https://site.example/notices/synthetic-notice/"',
      );
      expect(sitemap).toContain("https://site.example/");
      expect(sitemap).toContain("https://site.example/notices/synthetic-notice/");
      expect(sitemap).not.toContain("/admin/");
      expect(robots).toContain("Disallow: /admin/");

      const noop = await publishStaticRelease({
        publishGeneration: large,
        environment,
        fetchImpl: snapshotFetch(largeSnapshot, mediaResponses),
      });
      expect(noop.status).toBe("NO_PUBLIC_CHANGE");
      expect(noop.retentionStatus).toBe("NOT_APPLICABLE");
      expect(await readlink(environment.RHAOMI_PUBLIC_CURRENT_LINK)).toBe(
        currentTarget,
      );

      const rollbackGeneration = "9007199254740994";
      const malformedHistory = join(
        environment.RHAOMI_PUBLIC_RELEASE_ROOT,
        "malformed-history",
      );
      await mkdir(malformedHistory);
      await expect(
        publishStaticRelease({
          publishGeneration: rollbackGeneration,
          environment,
          fetchImpl: snapshotFetch(snapshot(rollbackGeneration)),
          postSwitchSmoke: async () => {
            throw new Error("synthetic post-switch failure");
          },
        }),
      ).rejects.toMatchObject({ code: "RELEASE_POST_SWITCH_FAILED" });
      expect(await readlink(environment.RHAOMI_PUBLIC_CURRENT_LINK)).toBe(
        currentTarget,
      );
      await expect(
        access(
          join(
            environment.RHAOMI_PUBLIC_RELEASE_ROOT,
            releaseIdFor(rollbackGeneration, rollbackGeneration, CODE_SHA),
          ),
        ),
      ).rejects.toMatchObject({ code: "ENOENT" });
      await expect(access(malformedHistory)).resolves.toBeUndefined();

      const maximum = "9223372036854775807";
      const maximumResult = await publishStaticRelease({
        publishGeneration: maximum,
        environment,
        fetchImpl: snapshotFetch(snapshot(maximum, { notices: [] })),
      });
      expect(maximumResult).toMatchObject({
        status: "PUBLISHED",
        retentionStatus: "DEFERRED",
        contentRevision: maximum,
        publishGeneration: maximum,
      });
      await expect(access(malformedHistory)).resolves.toBeUndefined();
      await rm(malformedHistory, { recursive: true, force: false });
      const maximumTarget = await readlink(environment.RHAOMI_PUBLIC_CURRENT_LINK);
      const maximumSite = join(
        dirname(environment.RHAOMI_PUBLIC_CURRENT_LINK),
        maximumTarget,
      );
      await expect(
        readFile(join(maximumSite, "notices", "synthetic-notice", "index.html")),
      ).rejects.toMatchObject({ code: "ENOENT" });
    },
    120_000,
  );
});
