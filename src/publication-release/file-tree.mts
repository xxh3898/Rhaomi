import { createHash } from "node:crypto";
import { lstat, opendir, readFile } from "node:fs/promises";
import { join, relative, sep } from "node:path";

import { releaseFail } from "./errors.mts";

export type RegularFileEntry = Readonly<{
  absolutePath: string;
  relativePath: string;
  byteSize: number;
}>;

export async function regularFileTree(root: string): Promise<readonly RegularFileEntry[]> {
  const rootStat = await lstat(root).catch(() =>
    releaseFail("RELEASE_VALIDATION_FAILED"),
  );
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
    releaseFail("RELEASE_VALIDATION_FAILED");
  }
  const entries: RegularFileEntry[] = [];

  async function visit(directory: string): Promise<void> {
    const handle = await opendir(directory).catch(() =>
      releaseFail("RELEASE_VALIDATION_FAILED"),
    );
    for await (const item of handle) {
      const absolutePath = join(directory, item.name);
      const itemStat = await lstat(absolutePath).catch(() =>
        releaseFail("RELEASE_VALIDATION_FAILED"),
      );
      if (itemStat.isSymbolicLink() || (!itemStat.isDirectory() && !itemStat.isFile())) {
        releaseFail("RELEASE_VALIDATION_FAILED");
      }
      if (itemStat.isDirectory()) {
        await visit(absolutePath);
      } else {
        const relativePath = relative(root, absolutePath).split(sep).join("/");
        if (
          relativePath.length === 0 ||
          relativePath.startsWith("../") ||
          relativePath.includes("\\") ||
          relativePath.split("/").includes("..")
        ) {
          releaseFail("RELEASE_VALIDATION_FAILED");
        }
        entries.push({
          absolutePath,
          relativePath,
          byteSize: itemStat.size,
        });
      }
    }
  }

  await visit(root);
  return entries.sort((left, right) =>
    left.relativePath.localeCompare(right.relativePath, "en"),
  );
}

export async function siteTreeSha256(root: string): Promise<string> {
  const digest = createHash("sha256");
  for (const entry of await regularFileTree(root)) {
    digest.update(entry.relativePath, "utf8");
    digest.update("\0");
    digest.update(String(entry.byteSize), "ascii");
    digest.update("\0");
    digest.update(await readFile(entry.absolutePath));
    digest.update("\0");
  }
  return digest.digest("hex");
}
