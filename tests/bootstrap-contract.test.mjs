import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { dirname, extname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const appRoot = join(projectRoot, "src", "app");

async function collectSourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nestedFiles = await Promise.all(
    entries.map(async (entry) => {
      const absolutePath = join(directory, entry.name);

      if (entry.isDirectory()) {
        return collectSourceFiles(absolutePath);
      }

      return [absolutePath];
    }),
  );

  return nestedFiles.flat();
}

test("Static Export 설정을 고정한다", async () => {
  const config = await readFile(join(projectRoot, "next.config.ts"), "utf8");

  assert.match(config, /output:\s*["']export["']/);
  assert.match(config, /trailingSlash:\s*true/);
  assert.match(config, /unoptimized:\s*true/);
});

test("요청 시점 서버 기능을 공개 app source에 포함하지 않는다", async () => {
  const sourceFiles = await collectSourceFiles(appRoot);
  const routeHandlers = sourceFiles.filter((sourceFile) =>
    /^route\.[cm]?[jt]sx?$/.test(sourceFile.split("/").at(-1) ?? ""),
  );

  assert.deepEqual(routeHandlers, []);

  for (const sourceFile of sourceFiles) {
    if (![".js", ".jsx", ".ts", ".tsx", ".mjs"].includes(extname(sourceFile))) {
      continue;
    }

    const source = await readFile(sourceFile, "utf8");

    assert.doesNotMatch(source, /["']use server["']/);
    assert.doesNotMatch(source, /from\s+["']next\/(?:headers|server)["']/);
    assert.doesNotMatch(source, /SPRING_DATASOURCE_/);
    assert.doesNotMatch(source, /RHAOMI_BOOTSTRAP_ADMIN_/);
  }
});

test("공개 홈은 관리자 runtime API나 진입 링크를 노출하지 않는다", async () => {
  const page = await readFile(join(appRoot, "page.tsx"), "utf8");

  assert.doesNotMatch(page, /\/api\/admin/);
  assert.doesNotMatch(page, /href\s*=\s*["'][^"']*\/admin\/?/i);
});

test("최소 홈 화면에 한국어 프로젝트 식별자를 제공한다", async () => {
  const page = await readFile(join(appRoot, "page.tsx"), "utf8");
  const layout = await readFile(join(appRoot, "layout.tsx"), "utf8");

  assert.match(page, /라오미펫/);
  assert.match(layout, /<html lang="ko">/);
});
