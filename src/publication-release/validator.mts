import { createHash } from "node:crypto";
import { access, readFile } from "node:fs/promises";
import { extname, join } from "node:path";

import { parse } from "parse5";

import type { GeneratedArtifactsV2 } from "../public-site/contracts.mts";
import { mediaPathHash } from "../public-site/contracts.mts";
import { releaseFail } from "./errors.mts";
import { regularFileTree } from "./file-tree.mts";

type HtmlAttribute = Readonly<{ name: string; value: string }>;
type HtmlNode = Readonly<{
  nodeName: string;
  value?: string;
  attrs?: readonly HtmlAttribute[];
  childNodes?: readonly HtmlNode[];
}>;

export type StaticExportValidationInput = Readonly<{
  siteRoot: string;
  artifacts: GeneratedArtifactsV2;
  publicSiteUrl: string;
  forbiddenValues?: readonly string[];
}>;

const TEXT_EXTENSIONS = new Set([
  ".css",
  ".html",
  ".js",
  ".json",
  ".txt",
  ".xml",
]);
const FIXED_FORBIDDEN = [
  "BUILD_API_CREDENTIAL",
  "RHAOMI_BUILD_SERVICE_TOKEN",
  "jdbc:postgresql:",
  "/private/var/lib/rhaomi",
  "/var/lib/rhaomi",
  "/srv/rhaomi/state",
  ".env.dev.local",
  "BUILD_API_INTERNAL_URL",
  "Authorization: Bearer",
] as const;
const INTERNAL_URL_PATTERN =
  /https?:\/\/(?:localhost|127(?:\.[0-9]{1,3}){3}|backend(?=[:/])|[^/\s"']+\.internal(?=[:/]))/iu;

function children(node: HtmlNode): readonly HtmlNode[] {
  return node.childNodes ?? [];
}

function descendants(node: HtmlNode): HtmlNode[] {
  const result: HtmlNode[] = [];
  for (const child of children(node)) {
    result.push(child, ...descendants(child));
  }
  return result;
}

function attribute(node: HtmlNode, name: string): string | null {
  return node.attrs?.find((item) => item.name === name)?.value ?? null;
}

function elements(document: HtmlNode, name: string): HtmlNode[] {
  return descendants(document).filter((node) => node.nodeName === name);
}

function textContent(node: HtmlNode): string {
  if (node.nodeName === "#text") return node.value ?? "";
  return children(node).map(textContent).join("");
}

function canonicalHref(document: HtmlNode): string | null {
  return (
    elements(document, "link")
      .find((node) =>
        (attribute(node, "rel") ?? "")
          .split(/\s+/u)
          .includes("canonical"),
      )
      ?.attrs?.find((item) => item.name === "href")?.value ?? null
  );
}

function robotsContent(document: HtmlNode): string {
  return (
    elements(document, "meta")
      .filter((node) => attribute(node, "name")?.toLowerCase() === "robots")
      .map((node) => attribute(node, "content") ?? "")
      .join(",")
      .toLowerCase()
  );
}

function routeFile(pathname: string): string {
  if (pathname === "/") return "index.html";
  const value = pathname.slice(1);
  if (pathname.endsWith("/")) return `${value}index.html`;
  return value;
}

function referencedPaths(
  document: HtmlNode,
  routeUrl: URL,
  publicOrigin: string,
): string[] {
  const values: string[] = [];
  for (const node of descendants(document)) {
    for (const name of ["href", "src"] as const) {
      const value = attribute(node, name);
      if (value !== null) values.push(value);
    }
    const srcSet = attribute(node, "srcset");
    if (srcSet !== null) {
      values.push(
        ...srcSet
          .split(",")
          .map((candidate) => candidate.trim().split(/\s+/u)[0]),
      );
    }
  }
  const paths: string[] = [];
  for (const value of values) {
    if (
      value.length === 0 ||
      value.startsWith("#") ||
      /^(?:mailto|tel):/iu.test(value)
    ) {
      continue;
    }
    let parsed: URL;
    try {
      parsed = new URL(value, routeUrl);
    } catch {
      releaseFail("RELEASE_VALIDATION_FAILED");
    }
    if (!["http:", "https:", "mailto:", "tel:"].includes(parsed.protocol)) {
      releaseFail("RELEASE_VALIDATION_FAILED");
    }
    if (parsed.protocol === "mailto:" || parsed.protocol === "tel:") continue;
    if (parsed.origin !== publicOrigin) continue;
    let pathname: string;
    try {
      pathname = decodeURIComponent(parsed.pathname);
    } catch {
      releaseFail("RELEASE_VALIDATION_FAILED");
    }
    if (
      !pathname.startsWith("/") ||
      pathname.includes("\\") ||
      pathname.split("/").includes("..")
    ) {
      releaseFail("RELEASE_VALIDATION_FAILED");
    }
    paths.push(routeFile(pathname));
  }
  return paths;
}

async function parseHtml(path: string): Promise<{ html: string; document: HtmlNode }> {
  const html = await readFile(path, "utf8").catch(() =>
    releaseFail("RELEASE_VALIDATION_FAILED"),
  );
  const document = parse(html) as unknown as HtmlNode;
  if (
    elements(document, "html").length !== 1 ||
    elements(document, "head").length !== 1 ||
    elements(document, "body").length !== 1
  ) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }
  return { html, document };
}

function sitemapLocations(xml: string): string[] {
  const values = [...xml.matchAll(/<loc>([^<]+)<\/loc>/gu)].map((match) =>
    (match[1] ?? "")
      .replaceAll("&amp;", "&")
      .replaceAll("&lt;", "<")
      .replaceAll("&gt;", ">")
      .replaceAll("&quot;", '"')
      .replaceAll("&apos;", "'"),
  );
  if (!xml.includes("<urlset") || values.length === 0) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }
  return values;
}

async function validateMedia(
  siteRoot: string,
  artifacts: GeneratedArtifactsV2,
): Promise<void> {
  const expectedPaths = new Set<string>();
  for (const item of artifacts.mediaManifest.items) {
    for (const variant of item.variants) {
      expectedPaths.add(variant.publicPath.slice(1));
      const bytes = await readFile(join(siteRoot, variant.publicPath.slice(1))).catch(
        () => releaseFail("RELEASE_VALIDATION_FAILED"),
      );
      const digest = createHash("sha256").update(bytes).digest("hex");
      if (digest !== mediaPathHash(variant.publicPath) || bytes.length !== variant.byteSize) {
        releaseFail("RELEASE_VALIDATION_FAILED");
      }
    }
  }
  const actualPaths = new Set(
    (await regularFileTree(siteRoot))
      .map((entry) => entry.relativePath)
      .filter((path) => path.startsWith("generated/media/")),
  );
  if (
    expectedPaths.size !== actualPaths.size ||
    [...expectedPaths].some((path) => !actualPaths.has(path))
  ) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }
}

export async function validateStaticExport(
  input: StaticExportValidationInput,
): Promise<void> {
  const files = await regularFileTree(input.siteRoot);
  const filePaths = new Set(files.map((entry) => entry.relativePath));
  if (files.length === 0 || files.some((entry) => entry.relativePath.endsWith(".map"))) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }

  const siteUrl = new URL(input.publicSiteUrl);
  const expectedHtml = new Map<string, string>([["index.html", siteUrl.toString()]]);
  for (const notice of input.artifacts.content.notices) {
    expectedHtml.set(
      `notices/${notice.slug}/index.html`,
      new URL(`/notices/${notice.slug}/`, siteUrl).toString(),
    );
  }
  for (const required of [
    ...expectedHtml.keys(),
    "admin/index.html",
    "404.html",
    "robots.txt",
    "sitemap.xml",
  ]) {
    if (!filePaths.has(required)) releaseFail("RELEASE_VALIDATION_FAILED");
  }

  const forbiddenValues = [
    ...FIXED_FORBIDDEN,
    ...(input.forbiddenValues ?? []).filter((value) => value.length >= 8),
  ];
  for (const file of files) {
    if (!TEXT_EXTENSIONS.has(extname(file.relativePath))) continue;
    const value = await readFile(file.absolutePath, "utf8").catch(() =>
      releaseFail("RELEASE_VALIDATION_FAILED"),
    );
    if (
      forbiddenValues.some((forbidden) => value.includes(forbidden)) ||
      INTERNAL_URL_PATTERN.test(value)
    ) {
      releaseFail("RELEASE_VALIDATION_FAILED");
    }
  }

  for (const [relativePath, expectedCanonical] of expectedHtml) {
    const { document } = await parseHtml(join(input.siteRoot, relativePath));
    if (canonicalHref(document) !== expectedCanonical) {
      releaseFail("RELEASE_VALIDATION_FAILED");
    }
    const routeUrl = new URL(expectedCanonical);
    for (const referenced of referencedPaths(document, routeUrl, siteUrl.origin)) {
      if (!filePaths.has(referenced)) releaseFail("RELEASE_VALIDATION_FAILED");
    }
  }

  const home = await parseHtml(join(input.siteRoot, "index.html"));
  const homeText = textContent(home.document).replace(/\s+/gu, " ");
  const requiredText = [
    input.artifacts.content.shop.shopName,
    input.artifacts.content.shop.phone,
    input.artifacts.content.shop.address,
    ...input.artifacts.content.services.map((service) => service.name),
    ...input.artifacts.content.notices.map((notice) => notice.title),
  ];
  if (requiredText.some((value) => !homeText.includes(value))) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }
  const homeAlt = new Set(
    elements(home.document, "img").map((image) => attribute(image, "alt") ?? ""),
  );
  if (
    input.artifacts.content.galleryItems.some(
      (item) => !homeAlt.has(item.altText),
    )
  ) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }

  const admin = await parseHtml(join(input.siteRoot, "admin/index.html"));
  const adminRobots = robotsContent(admin.document);
  if (
    !adminRobots.includes("noindex") ||
    !adminRobots.includes("nofollow") ||
    canonicalHref(admin.document) !== null
  ) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }

  const robots = await readFile(join(input.siteRoot, "robots.txt"), "utf8");
  const robotsLines = robots
    .split(/\r?\n/gu)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  const expectedRobots = [
    "User-Agent: *",
    "Allow: /",
    "Disallow: /admin/",
    "Disallow: /api/",
    "Disallow: /actuator/",
    `Sitemap: ${new URL("/sitemap.xml", siteUrl).toString()}`,
  ];
  if (
    robotsLines.length !== expectedRobots.length ||
    expectedRobots.some(
      (line) => !robotsLines.some((actual) => actual.toLowerCase() === line.toLowerCase()),
    )
  ) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }
  const sitemap = await readFile(join(input.siteRoot, "sitemap.xml"), "utf8");
  const sitemapUrls = [
    siteUrl.toString(),
    ...input.artifacts.content.notices.map((notice) =>
      new URL(`/notices/${notice.slug}/`, siteUrl).toString(),
    ),
  ];
  const actualSitemapUrls = sitemapLocations(sitemap);
  if (
    actualSitemapUrls.length !== sitemapUrls.length ||
    new Set(actualSitemapUrls).size !== actualSitemapUrls.length ||
    sitemapUrls.some((url) => !actualSitemapUrls.includes(url))
  ) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }

  await validateMedia(input.siteRoot, input.artifacts);
  for (const file of files) {
    await access(file.absolutePath).catch(() =>
      releaseFail("RELEASE_VALIDATION_FAILED"),
    );
  }
}
