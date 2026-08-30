import { createHash } from "node:crypto";
import { readdir, readFile, realpath, stat } from "node:fs/promises";
import { join, resolve } from "node:path";

const [workspaceArgument, ...digestArguments] = process.argv.slice(2);
const workspace = resolve(workspaceArgument ?? "/workspace");
const expectedDigests = new Set(digestArguments);

if (
  expectedDigests.size === 0 ||
  [...expectedDigests].some((digest) => !/^[0-9a-f]{64}$/.test(digest))
) {
  throw new Error("검증할 credential digest가 올바르지 않습니다.");
}

if (Object.hasOwn(process.env, "RHAOMI_BUILD_SERVICE_TOKEN")) {
  throw new Error("frontend environment에 build service token key가 존재합니다.");
}

const workspaceEntries = await readdir(workspace, { withFileTypes: true });
if (workspaceEntries.some((entry) => entry.name.startsWith(".env"))) {
  throw new Error("frontend workspace에 .env 계열 파일이 존재합니다.");
}

const tokenPattern = /(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])/g;
const visited = new Set();
let scannedFiles = 0;
let matchingTokens = 0;

async function scan(path) {
  const canonicalPath = await realpath(path);
  if (visited.has(canonicalPath)) {
    return;
  }
  visited.add(canonicalPath);

  const details = await stat(canonicalPath);
  if (details.isDirectory()) {
    const entries = await readdir(canonicalPath);
    for (const entry of entries) {
      await scan(join(canonicalPath, entry));
    }
    return;
  }
  if (!details.isFile()) {
    return;
  }

  scannedFiles += 1;
  const contents = (await readFile(canonicalPath)).toString("latin1");
  for (const match of contents.matchAll(tokenPattern)) {
    const digest = createHash("sha256").update(match[0], "utf8").digest("hex");
    if (expectedDigests.has(digest)) {
      matchingTokens += 1;
    }
  }
}

await scan(workspace);

if (matchingTokens !== 0) {
  throw new Error("frontend filesystem에서 build service token literal을 발견했습니다.");
}

console.log(
  `Frontend credential isolation passed: envFiles=0 tokenMatches=0 scannedFiles=${scannedFiles}`,
);
