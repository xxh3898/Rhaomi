import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { afterEach, describe, expect, it } from "vitest";

import { snapshotFixture } from "../build-transformer/test-fixtures";
import { parseGeneratedArtifactsV2 } from "../public-site/contracts.mts";
import { validateStaticExport } from "./validator.mts";

const roots: string[] = [];
const execFileAsync = promisify(execFile);

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

function artifactsFixture() {
  const base = snapshotFixture();
  const { mediaAssets, ...content } = {
    ...base,
    shop: {
      ...base.shop,
      heroImageId: null,
      heroImageAltText: null,
      groomerImageId: null,
      groomerImageAltText: null,
    },
    galleryItems: [],
    mediaAssets: [],
  };
  void mediaAssets;
  return parseGeneratedArtifactsV2(content, {
    schemaVersion: 2,
    contentRevision: content.contentRevision,
    publishGeneration: content.publishGeneration,
    items: [],
  });
}

async function validSite() {
  const root = await mkdtemp(join(tmpdir(), "rhaomi-site-validator-"));
  roots.push(root);
  const artifacts = artifactsFixture();
  await mkdir(join(root, "admin"), { recursive: true });
  await mkdir(join(root, "notices", "synthetic-notice"), { recursive: true });
  const serviceNames = artifacts.content.services.map((item) => item.name).join(" ");
  await writeFile(
    join(root, "index.html"),
    `<!doctype html><html lang="ko"><head><link rel="canonical" href="https://site.example/"></head><body>${artifacts.content.shop.shopName} ${artifacts.content.shop.phone} ${artifacts.content.shop.address} ${serviceNames} 합성 공지 <a href="/notices/synthetic-notice/">공지</a></body></html>`,
  );
  await writeFile(
    join(root, "admin", "index.html"),
    '<!doctype html><html lang="ko"><head><meta name="robots" content="noindex,nofollow,noarchive"></head><body>관리자</body></html>',
  );
  await writeFile(
    join(root, "404.html"),
    '<!doctype html><html lang="ko"><head><title>찾을 수 없음</title></head><body>404</body></html>',
  );
  await writeFile(
    join(root, "notices", "synthetic-notice", "index.html"),
    '<!doctype html><html lang="ko"><head><link rel="canonical" href="https://site.example/notices/synthetic-notice/"></head><body>합성 공지 <a href="/">홈</a></body></html>',
  );
  await writeFile(
    join(root, "robots.txt"),
    "User-Agent: *\nAllow: /\nDisallow: /admin/\nDisallow: /api/\nDisallow: /actuator/\n\nSitemap: https://site.example/sitemap.xml\n",
  );
  await writeFile(
    join(root, "sitemap.xml"),
    "<urlset><url><loc>https://site.example/</loc></url><url><loc>https://site.example/notices/synthetic-notice/</loc></url></urlset>",
  );
  return { root, artifacts };
}

describe("validateStaticExport", () => {
  it("accepts complete content-bound static output", async () => {
    const fixture = await validSite();
    await expect(
      validateStaticExport({
        siteRoot: fixture.root,
        artifacts: fixture.artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).resolves.toBeUndefined();
  });

  it("rejects broken internal links and private build values", async () => {
    const fixture = await validSite();
    const html = await readFile(join(fixture.root, "index.html"), "utf8");
    await writeFile(
      join(fixture.root, "index.html"),
      html.replace(
        "</body>",
        '<a href="/missing/">missing</a> secret-value-123</body>',
      ),
    );
    await expect(
      validateStaticExport({
        siteRoot: fixture.root,
        artifacts: fixture.artifacts,
        publicSiteUrl: "https://site.example/",
        forbiddenValues: ["secret-value-123"],
      }),
    ).rejects.toThrowError(/validation/i);

    const internal = await validSite();
    const internalHtml = await readFile(join(internal.root, "index.html"), "utf8");
    await writeFile(
      join(internal.root, "index.html"),
      internalHtml.replace(
        "</body>",
        '<a href="https://backend.internal/private">internal</a></body>',
      ),
    );
    await expect(
      validateStaticExport({
        siteRoot: internal.root,
        artifacts: internal.artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);
  });

  it("rejects canonical, sitemap, and robots contract drift", async () => {
    const canonical = await validSite();
    const canonicalHtml = await readFile(
      join(canonical.root, "index.html"),
      "utf8",
    );
    await writeFile(
      join(canonical.root, "index.html"),
      canonicalHtml.replace("https://site.example/", "https://wrong.example/"),
    );
    await expect(
      validateStaticExport({
        siteRoot: canonical.root,
        artifacts: canonical.artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);

    const sitemap = await validSite();
    await writeFile(
      join(sitemap.root, "sitemap.xml"),
      "<urlset><url><loc>https://site.example/</loc></url><url><loc>https://site.example/admin/</loc></url></urlset>",
    );
    await expect(
      validateStaticExport({
        siteRoot: sitemap.root,
        artifacts: sitemap.artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);

    const robots = await validSite();
    await writeFile(
      join(robots.root, "robots.txt"),
      "User-Agent: *\nAllow: /\nSitemap: https://site.example/sitemap.xml\n",
    );
    await expect(
      validateStaticExport({
        siteRoot: robots.root,
        artifacts: robots.artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);
  });

  it("validates generated media filename hashes and rejects orphan files", async () => {
    const fixture = await validSite();
    const bytes = Buffer.from("synthetic-public-media");
    const hash = createHash("sha256").update(bytes).digest("hex");
    const mediaId = "00000000-0000-4000-8000-000000000041";
    const content = {
      ...fixture.artifacts.content,
      shop: {
        ...fixture.artifacts.content.shop,
        heroImageId: mediaId,
        heroImageAltText: "합성 Hero",
      },
    };
    const artifacts = parseGeneratedArtifactsV2(content, {
      schemaVersion: 2,
      contentRevision: content.contentRevision,
      publishGeneration: content.publishGeneration,
      items: [
        {
          mediaId,
          variants: [
            {
              profile: "HERO",
              format: "jpeg",
              width: 1,
              height: 1,
              byteSize: bytes.length,
              publicPath: `/generated/media/${hash}.jpeg`,
            },
          ],
        },
      ],
    });
    const mediaRoot = join(fixture.root, "generated", "media");
    await mkdir(mediaRoot, { recursive: true });
    await writeFile(join(mediaRoot, `${hash}.jpeg`), bytes);
    const html = await import("node:fs/promises").then(({ readFile }) =>
      readFile(join(fixture.root, "index.html"), "utf8"),
    );
    await writeFile(
      join(fixture.root, "index.html"),
      html.replace(
        "</body>",
        `<img src="/generated/media/${hash}.jpeg" alt="합성 Hero"></body>`,
      ),
    );
    await expect(
      validateStaticExport({
        siteRoot: fixture.root,
        artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).resolves.toBeUndefined();

    await writeFile(join(mediaRoot, `${hash}.jpeg`), Buffer.from("corrupted"));
    await expect(
      validateStaticExport({
        siteRoot: fixture.root,
        artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);
    await writeFile(join(mediaRoot, `${hash}.jpeg`), bytes);

    await writeFile(join(mediaRoot, `${"f".repeat(64)}.jpeg`), bytes);
    await expect(
      validateStaticExport({
        siteRoot: fixture.root,
        artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);
  });

  it("rejects missing generated media", async () => {
    const fixture = await validSite();
    const bytes = Buffer.from("missing-public-media");
    const hash = createHash("sha256").update(bytes).digest("hex");
    const mediaId = "00000000-0000-4000-8000-000000000041";
    const content = {
      ...fixture.artifacts.content,
      shop: {
        ...fixture.artifacts.content.shop,
        heroImageId: mediaId,
        heroImageAltText: "합성 Hero",
      },
    };
    const artifacts = parseGeneratedArtifactsV2(content, {
      schemaVersion: 2,
      contentRevision: content.contentRevision,
      publishGeneration: content.publishGeneration,
      items: [
        {
          mediaId,
          variants: [
            {
              profile: "HERO",
              format: "jpeg",
              width: 1,
              height: 1,
              byteSize: bytes.length,
              publicPath: `/generated/media/${hash}.jpeg`,
            },
          ],
        },
      ],
    });

    await expect(
      validateStaticExport({
        siteRoot: fixture.root,
        artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);
  });

  it("rejects unexpected symlink and special files", async () => {
    const symlinkFixture = await validSite();
    await symlink("index.html", join(symlinkFixture.root, "unexpected-link"));
    await expect(
      validateStaticExport({
        siteRoot: symlinkFixture.root,
        artifacts: symlinkFixture.artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);

    const specialFixture = await validSite();
    await execFileAsync("mkfifo", [join(specialFixture.root, "unexpected-fifo")]);
    await expect(
      validateStaticExport({
        siteRoot: specialFixture.root,
        artifacts: specialFixture.artifacts,
        publicSiteUrl: "https://site.example/",
      }),
    ).rejects.toThrowError(/validation/i);
  });
});
