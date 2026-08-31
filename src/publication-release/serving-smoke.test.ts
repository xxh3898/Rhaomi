import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import { smokeStaticServingPath } from "./serving-smoke.mts";

const roots: string[] = [];

afterEach(async () => {
  await Promise.all(
    roots.splice(0).map((root) => rm(root, { recursive: true, force: true })),
  );
});

async function staticSite() {
  const root = await mkdtemp(join(tmpdir(), "rhaomi-serving-smoke-"));
  roots.push(root);
  await mkdir(join(root, "notices", "fixture"), { recursive: true });
  await mkdir(join(root, "generated", "media"), { recursive: true });
  await writeFile(join(root, "index.html"), "home");
  await writeFile(join(root, "404.html"), "missing");
  await writeFile(
    join(root, "notices", "fixture", "index.html"),
    "notice",
  );
  await writeFile(join(root, "generated", "media", "fixture.jpeg"), "media");
  return root;
}

describe("static serving smoke", () => {
  it("serves home, notice, media and a real 404 from the selected root", async () => {
    const root = await staticSite();

    await expect(
      smokeStaticServingPath({
        siteRoot: root,
        noticePath: "/notices/fixture/",
        mediaPath: "/generated/media/fixture.jpeg",
        failureCode: "RELEASE_VALIDATION_FAILED",
      }),
    ).resolves.toBeUndefined();
  });

  it("keeps pre-switch serving failures in the validation failure family", async () => {
    const root = await staticSite();
    await rm(join(root, "index.html"));

    await expect(
      smokeStaticServingPath({
        siteRoot: root,
        failureCode: "RELEASE_VALIDATION_FAILED",
      }),
    ).rejects.toMatchObject({ code: "RELEASE_VALIDATION_FAILED" });
  });
});
