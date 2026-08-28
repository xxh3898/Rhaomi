import assert from "node:assert/strict";
import { access, readFile, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const outputRoot = join(projectRoot, "out");
const indexPath = join(outputRoot, "index.html");

await access(indexPath);

const indexStat = await stat(indexPath);
const html = await readFile(indexPath, "utf8");

assert.ok(indexStat.size > 0, "out/index.html이 비어 있습니다.");
assert.match(html, /<html[^>]*lang="ko"/);
assert.match(html, /라오미펫/);
assert.doesNotMatch(html, /https?:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?/i);

const localAssets = [
  ...html.matchAll(/(?:href|src)="(\/_next\/[^"?#]+)"/g),
].map((match) => match[1]);

for (const assetPath of new Set(localAssets)) {
  await access(join(outputRoot, decodeURIComponent(assetPath.slice(1))));
}

console.log(
  `Static export validation passed: out/index.html (${indexStat.size} bytes), ${new Set(localAssets).size} linked assets`,
);
