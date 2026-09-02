import { createHash } from "node:crypto";
import { lstat, readFile, readdir, readlink, realpath } from "node:fs/promises";
import {
  basename,
  dirname,
  extname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from "node:path";

import { parse } from "parse5";

const [baseUrlValue, acceptanceRootValue] = process.argv.slice(2);
if (baseUrlValue === undefined || acceptanceRootValue === undefined) {
  throw new Error("Publication serving validation arguments are required");
}

const baseUrl = new URL(baseUrlValue);
if (baseUrl.protocol !== "http:" || baseUrl.username !== "" || baseUrl.password !== "") {
  throw new Error("Publication serving validation URL is invalid");
}
const acceptanceRoot = resolve(acceptanceRootValue);
if (!isAbsolute(acceptanceRoot) || acceptanceRoot === dirname(acceptanceRoot)) {
  throw new Error("Publication acceptance root is invalid");
}

const contract = JSON.parse(
  await readFile(join(acceptanceRoot, "serving-contract.json"), "utf8"),
);
if (
  contract.schemaVersion !== 1 ||
  !Array.isArray(contract.homeText) ||
  !Array.isArray(contract.absentHomeText) ||
  typeof contract.noticePath !== "string" ||
  typeof contract.noticeText !== "string" ||
  typeof contract.noticeTitle !== "string" ||
  typeof contract.noticePublishedAt !== "string" ||
  typeof contract.mediaPath !== "string" ||
  typeof contract.expectedGeneration !== "string" ||
  typeof contract.publicSiteUrl !== "string" ||
  typeof contract.shopName !== "string" ||
  typeof contract.homeTitle !== "string" ||
  typeof contract.homeDescription !== "string" ||
  typeof contract.phone !== "string" ||
  typeof contract.address !== "string" ||
  !Array.isArray(contract.sameAs) ||
  contract.sameAs.some((value) => typeof value !== "string")
) {
  throw new Error("Publication serving contract is invalid");
}

const publicRoot = join(acceptanceRoot, "public");
const currentLink = join(publicRoot, "current");
const currentState = await lstat(currentLink);
if (!currentState.isSymbolicLink()) {
  throw new Error("Current release is not a symbolic link");
}
const currentTarget = await readlink(currentLink);
if (isAbsolute(currentTarget)) {
  throw new Error("Current release uses an absolute target");
}
const currentReal = await realpath(currentLink);
const releasesReal = await realpath(join(publicRoot, "releases"));
const currentRelative = relative(releasesReal, currentReal);
if (
  currentRelative === "" ||
  currentRelative === ".." ||
  currentRelative.startsWith(`..${sep}`) ||
  isAbsolute(currentRelative) ||
  currentRelative.split(sep).length !== 2 ||
  basename(currentReal) !== "site"
) {
  throw new Error("Current release escaped the release root");
}

const privateManifest = JSON.parse(
  await readFile(join(dirname(currentReal), "release-manifest.json"), "utf8"),
);
if (privateManifest.publishGeneration !== contract.expectedGeneration) {
  throw new Error("Serving generation does not match the release manifest");
}

async function response(path, expectedStatus) {
  const result = await fetch(new URL(path, baseUrl), {
    redirect: "manual",
    signal: AbortSignal.timeout(10_000),
  });
  if (result.status !== expectedStatus) {
    throw new Error(`Unexpected static response status for ${path}`);
  }
  return result;
}

const homeResponse = await response("/", 200);
const home = await homeResponse.text();
for (const text of contract.homeText) {
  if (typeof text !== "string" || !home.includes(text)) {
    throw new Error("Required synthetic home content is missing");
  }
}
for (const text of contract.absentHomeText) {
  if (typeof text !== "string" || home.includes(text)) {
    throw new Error("Non-public synthetic home content was served");
  }
}
const privateMarkers = [
  "BUILD_API_CREDENTIAL",
  "RHAOMI_BUILD_SERVICE_TOKEN",
  "jdbc:postgresql:",
  "/private/var/lib/rhaomi",
  "/var/lib/rhaomi",
  ".env.dev.local",
];
for (const marker of privateMarkers) {
  if (home.includes(marker)) {
    throw new Error("Private runtime marker leaked into the static home");
  }
}

const descendants = (node) => [
  ...(node.childNodes ?? []),
  ...(node.childNodes ?? []).flatMap(descendants),
];
const attr = (node, name) => node.attrs?.find((item) => item.name === name)?.value;
const textContent = (node) =>
  node.nodeName === "#text"
    ? (node.value ?? "")
    : (node.childNodes ?? []).map(textContent).join("");
const elements = (documentNodes, name) =>
  documentNodes.filter((node) => node.nodeName === name);
const metaContent = (documentNodes, attributeName, attributeValue) =>
  elements(documentNodes, "meta")
    .find(
      (node) =>
        (attr(node, attributeName) ?? "").toLowerCase() === attributeValue,
    )
    ?.attrs?.find((item) => item.name === "content")?.value;
const canonical = (documentNodes) =>
  elements(documentNodes, "link")
    .find((node) =>
      (attr(node, "rel") ?? "").split(/\s+/u).includes("canonical"),
    )
    ?.attrs?.find((item) => item.name === "href")?.value;

function assertDocumentSemantics(documentNodes) {
  const ids = documentNodes
    .map((node) => attr(node, "id"))
    .filter((value) => typeof value === "string" && value.length > 0);
  if (new Set(ids).size !== ids.length) {
    throw new Error("Static document has duplicate ids");
  }
  for (const node of documentNodes) {
    const tabindex = attr(node, "tabindex");
    if (
      attr(node, "autofocus") !== undefined ||
      (tabindex !== undefined && /^[1-9][0-9]*$/u.test(tabindex))
    ) {
      throw new Error("Static document has an unsafe focus override");
    }
  }
  for (const link of elements(documentNodes, "a")) {
    const href = attr(link, "href") ?? "";
    const imageAlt = descendants(link)
      .filter((node) => node.nodeName === "img")
      .map((node) => attr(node, "alt") ?? "")
      .join(" ");
    const accessibleName = `${attr(link, "aria-label") ?? ""} ${textContent(link)} ${imageAlt}`
      .replace(/\s+/gu, " ")
      .trim();
    if (href === "" || href === "#" || accessibleName === "") {
      throw new Error("Static link semantics are invalid");
    }
    if (href.startsWith("#") && !ids.includes(href.slice(1))) {
      throw new Error("Static fragment link target is missing");
    }
  }
}

async function regularFiles(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) result.push(...(await regularFiles(path)));
    else if (entry.isFile()) result.push(path);
  }
  return result;
}

const homeDocument = parse(home);
const homeNodes = descendants(homeDocument);
if (
  elements(homeNodes, "html").every((node) => attr(node, "lang") !== "ko") ||
  elements(homeNodes, "main").length !== 1 ||
  elements(homeNodes, "h1").length !== 1 ||
  elements(homeNodes, "h2").length < 3 ||
  elements(homeNodes, "img").length === 0 ||
  elements(homeNodes, "img").some(
    (node) => (attr(node, "alt") ?? "").trim() === "",
  ) ||
  !elements(homeNodes, "a").some(
    (node) => attr(node, "href") === "tel:0212345678",
  ) ||
  !elements(homeNodes, "time").some(
    (node) => attr(node, "datetime") === contract.noticePublishedAt,
  )
) {
  throw new Error("Static home accessibility semantics are invalid");
}
assertDocumentSemantics(homeNodes);

const publicSiteUrl = new URL(contract.publicSiteUrl).toString();
const homeTitle = textContent(elements(homeNodes, "title")[0] ?? {}).trim();
const ogImage = metaContent(homeNodes, "property", "og:image");
if (
  homeTitle !== contract.homeTitle ||
  canonical(homeNodes) !== publicSiteUrl ||
  metaContent(homeNodes, "name", "description") !== contract.homeDescription ||
  metaContent(homeNodes, "property", "og:title") !== contract.shopName ||
  metaContent(homeNodes, "property", "og:description") !== contract.homeDescription ||
  metaContent(homeNodes, "property", "og:url") !== publicSiteUrl ||
  typeof ogImage !== "string"
) {
  throw new Error("Static home SEO metadata is invalid");
}
const ogImageUrl = new URL(ogImage);
if (
  ogImageUrl.origin !== new URL(publicSiteUrl).origin ||
  !ogImageUrl.pathname.startsWith("/generated/media/")
) {
  throw new Error("Static Open Graph media is invalid");
}
await response(ogImageUrl.pathname, 200);

const jsonLdNode = elements(homeNodes, "script").find(
  (node) => attr(node, "type") === "application/ld+json",
);
const jsonLd = JSON.parse(textContent(jsonLdNode ?? {}));
if (
  jsonLd.name !== contract.shopName ||
  jsonLd.url !== publicSiteUrl ||
  jsonLd.telephone !== contract.phone ||
  jsonLd.address?.streetAddress !== contract.address ||
  JSON.stringify(jsonLd.sameAs) !== JSON.stringify(contract.sameAs)
) {
  throw new Error("Static LocalBusiness data is invalid");
}

const notice = await (await response(contract.noticePath, 200)).text();
const noticeDocument = parse(notice);
const noticeNodes = descendants(noticeDocument);
const noticeCanonical = new URL(contract.noticePath, publicSiteUrl).toString();
const noticeChecks = {
  body: notice.includes(contract.noticeText),
  headingLevel: notice.includes("<h2>합성 공지 안내</h2>"),
  list: notice.includes("<li>합성 목록 항목</li>"),
  code: notice.includes("<code>안전한 코드</code>"),
  rawHtml: !notice.includes("<script>alert(1)</script>"),
  main: elements(noticeNodes, "main").length === 1,
  heading:
    elements(noticeNodes, "h1").length === 1 &&
    textContent(elements(noticeNodes, "h1")[0] ?? {}).trim() ===
      contract.noticeTitle,
  time: elements(noticeNodes, "time").some(
    (node) => attr(node, "datetime") === contract.noticePublishedAt,
  ),
  canonical: canonical(noticeNodes) === noticeCanonical,
};
const failedNoticeCheck = Object.entries(noticeChecks).find(([, passed]) => !passed);
if (failedNoticeCheck !== undefined) {
  throw new Error(`Static notice contract failed: ${failedNoticeCheck[0]}`);
}
assertDocumentSemantics(noticeNodes);

const mediaResponse = await response(contract.mediaPath, 200);
const mediaBytes = Buffer.from(await mediaResponse.arrayBuffer());
if (mediaBytes.length === 0) {
  throw new Error("Static media response is empty");
}
const filename = basename(new URL(contract.mediaPath, baseUrl).pathname);
const hash = filename.split(".")[0];
if (createHash("sha256").update(mediaBytes).digest("hex") !== hash) {
  throw new Error("Static media hash does not match its filename");
}

const siteFiles = await regularFiles(currentReal);
const generatedMediaFiles = siteFiles.filter((path) =>
  relative(currentReal, path).startsWith(`generated${sep}media${sep}`),
);
const allowedMediaExtensions = new Set([".avif", ".webp", ".jpeg"]);
if (
  generatedMediaFiles.length === 0 ||
  generatedMediaFiles.some((path) => !allowedMediaExtensions.has(extname(path)))
) {
  throw new Error("Public media derivative set is invalid");
}
const textExtensions = new Set([".css", ".html", ".js", ".json", ".txt", ".xml"]);
for (const path of siteFiles.filter((candidate) =>
  textExtensions.has(extname(candidate)),
)) {
  const value = await readFile(path, "utf8");
  if (privateMarkers.some((marker) => value.includes(marker))) {
    throw new Error("Private runtime marker leaked into the static release");
  }
}
const css = (
  await Promise.all(
    siteFiles
      .filter((path) => extname(path) === ".css")
      .map((path) => readFile(path, "utf8")),
  )
).join("\n");
if (
  !/min-width:\s*320px/u.test(css) ||
  !/@media\s*\(max-width:\s*32rem\)/u.test(css) ||
  !/@media\s*\(prefers-reduced-motion:\s*reduce\)/u.test(css)
) {
  throw new Error("Static responsive or reduced-motion CSS contract is missing");
}

const robots = await (await response("/robots.txt", 200)).text();
const sitemap = await (await response("/sitemap.xml", 200)).text();
if (!robots.includes("Sitemap:") || !sitemap.includes("acceptance.rhaomi.invalid")) {
  throw new Error("Static SEO artifacts are invalid");
}

for (const path of [
  "/__rhaomi_missing__",
  "/release-manifest.json",
  "/api/build/snapshot",
  "/api/admin/auth/me",
  "/internal/private",
  "/actuator/health",
]) {
  await response(path, 404);
}

process.stdout.write("Local publication serving acceptance passed\n");
