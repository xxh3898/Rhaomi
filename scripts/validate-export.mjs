import assert from "node:assert/strict";
import { access, readFile, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const outputRoot = join(projectRoot, "out");
const indexPath = join(outputRoot, "index.html");
const adminIndexPath = join(outputRoot, "admin", "index.html");

await access(indexPath);
await access(adminIndexPath);

const indexStat = await stat(indexPath);
const html = await readFile(indexPath, "utf8");
const adminIndexStat = await stat(adminIndexPath);
const adminHtml = await readFile(adminIndexPath, "utf8");

assert.ok(indexStat.size > 0, "out/index.html이 비어 있습니다.");
assert.match(html, /<html[^>]*lang="ko"/);
assert.match(html, /라오미펫/);
assert.doesNotMatch(html, /href="[^"]*\/admin\/?"/i);
assert.doesNotMatch(html, /https?:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?/i);
assert.ok(adminIndexStat.size > 0, "out/admin/index.html이 비어 있습니다.");
assert.match(adminHtml, /<html[^>]*lang="ko"/);
assert.match(adminHtml, /라오미펫 관리자/);
assert.match(adminHtml, /<meta[^>]+name="robots"[^>]+content="[^"]*noindex/i);
assert.match(adminHtml, /<meta[^>]+name="robots"[^>]+content="[^"]*nofollow/i);
assert.match(adminHtml, /<meta[^>]+name="robots"[^>]+content="[^"]*noarchive/i);
assert.doesNotMatch(
  adminHtml,
  /https?:\/\/(?:localhost|127\.0\.0\.1|backend)(?::\d+)?/i,
);

const localAssets = [
  ...html.matchAll(/(?:href|src)="(\/_next\/[^"?#]+)"/g),
  ...adminHtml.matchAll(/(?:href|src)="(\/_next\/[^"?#]+)"/g),
].map((match) => match[1]);

for (const assetPath of new Set(localAssets)) {
  await access(join(outputRoot, decodeURIComponent(assetPath.slice(1))));
}

console.log(
  `Static export validation passed: out/index.html (${indexStat.size} bytes), out/admin/index.html (${adminIndexStat.size} bytes), ${new Set(localAssets).size} linked assets`,
);
