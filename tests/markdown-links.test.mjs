import assert from "node:assert/strict";
import { access, readdir, readFile } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const ignoredDirectories = new Set([".git", ".gradle", ".next", "build", "node_modules", "out"]);

async function collectMarkdownFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    if (entry.isDirectory() && ignoredDirectories.has(entry.name)) {
      continue;
    }

    const absolutePath = join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectMarkdownFiles(absolutePath)));
    } else if (entry.name.endsWith(".md")) {
      files.push(absolutePath);
    }
  }

  return files;
}

test("Markdown 상대 링크가 존재하는 파일을 가리킨다", async () => {
  const markdownFiles = await collectMarkdownFiles(projectRoot);
  const missingLinks = [];

  for (const markdownFile of markdownFiles) {
    const markdown = await readFile(markdownFile, "utf8");
    const linkPattern = /\[[^\]]*\]\(([^)]+)\)/g;

    for (const match of markdown.matchAll(linkPattern)) {
      const rawTarget = match[1].trim();
      if (/^(?:https?:|mailto:|tel:|#)/i.test(rawTarget)) {
        continue;
      }

      const withoutAnchor = rawTarget.split("#", 1)[0];
      const target = decodeURIComponent(withoutAnchor.replace(/^<|>$/g, ""));
      if (!target) {
        continue;
      }

      try {
        await access(resolve(dirname(markdownFile), target));
      } catch {
        missingLinks.push(
          `${relative(projectRoot, markdownFile)} -> ${rawTarget}`,
        );
      }
    }
  }

  assert.deepEqual(missingLinks, []);
});
