import {
  access,
  mkdtemp,
  mkdir,
  readlink,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import type { ReleaseManifestV1 } from "./contracts.mts";
import {
  installImmutableRelease,
  pruneSuccessfulReleases,
  readReleaseLink,
  shouldPublishGeneration,
  switchReleaseWithRollback,
} from "./filesystem.mts";
import { siteTreeSha256 } from "./file-tree.mts";

const SHA = "a".repeat(40);
const DIGEST = `sha256:${"b".repeat(64)}`;
const roots: string[] = [];

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

async function fixtureRoot() {
  const root = await mkdtemp(join(tmpdir(), "rhaomi-release-test-"));
  roots.push(root);
  const releaseRoot = join(root, "public", "releases");
  await mkdir(releaseRoot, { recursive: true });
  return {
    root,
    releaseRoot,
    currentLink: join(root, "public", "current"),
    previousLink: join(root, "public", "previous"),
  };
}

async function candidate(
  releaseRoot: string,
  generation: string,
  content = `generation-${generation}`,
) {
  const candidateRoot = join(releaseRoot, `.candidate-${generation}`);
  const siteRoot = join(candidateRoot, "site");
  await mkdir(siteRoot, { recursive: true });
  await writeFile(join(siteRoot, "index.html"), content);
  const releaseId = `g-${generation}.r-1.c-${SHA.slice(0, 12)}`;
  const manifest: ReleaseManifestV1 = {
    schemaVersion: 1,
    releaseId,
    contentRevision: "1",
    publishGeneration: generation,
    generatedAt: "2026-08-31T00:00:00Z",
    codeSha: SHA,
    codeImageTag: `sha-${SHA}`,
    codeImageDigest: DIGEST,
    flywayVersion: "9",
    sbomReference: DIGEST,
    siteSha256: await siteTreeSha256(siteRoot),
  };
  await writeFile(
    join(candidateRoot, "release-manifest.json"),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
  return { candidateRoot, manifest };
}

async function exactParentRelease(
  paths: Awaited<ReturnType<typeof fixtureRoot>>,
  linkPath: string,
) {
  const packageRoot = dirname(paths.releaseRoot);
  const siteRoot = join(packageRoot, "site");
  await mkdir(siteRoot, { recursive: true });
  await writeFile(join(siteRoot, "index.html"), "exact parent escape");
  const manifest: ReleaseManifestV1 = {
    schemaVersion: 1,
    releaseId: basename(packageRoot),
    contentRevision: "1",
    publishGeneration: "1",
    generatedAt: "2026-08-31T00:00:00Z",
    codeSha: SHA,
    codeImageTag: `sha-${SHA}`,
    codeImageDigest: DIGEST,
    flywayVersion: "9",
    sbomReference: DIGEST,
    siteSha256: await siteTreeSha256(siteRoot),
  };
  await writeFile(
    join(packageRoot, "release-manifest.json"),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
  await symlink("site", linkPath, "dir");
}

describe("immutable release switch", () => {
  it("advances current and preserves the last successful release as previous", async () => {
    const paths = await fixtureRoot();
    const firstCandidate = await candidate(paths.releaseRoot, "2");
    const first = await installImmutableRelease({
      ...firstCandidate,
      releaseRoot: paths.releaseRoot,
    });
    await switchReleaseWithRollback({
      installed: first,
      ...paths,
      postSwitchSmoke: async () => undefined,
    });

    const secondCandidate = await candidate(paths.releaseRoot, "3");
    const second = await installImmutableRelease({
      ...secondCandidate,
      releaseRoot: paths.releaseRoot,
    });
    await switchReleaseWithRollback({
      installed: second,
      ...paths,
      postSwitchSmoke: async () => undefined,
    });

    expect((await readReleaseLink(paths.currentLink, paths.releaseRoot))?.manifest.publishGeneration).toBe("3");
    expect((await readReleaseLink(paths.previousLink, paths.releaseRoot))?.manifest.publishGeneration).toBe("2");
    expect(await readlink(paths.currentLink)).not.toContain(".candidate-");
  });

  it("refuses equal and lower generations without changing links", async () => {
    const paths = await fixtureRoot();
    const firstCandidate = await candidate(paths.releaseRoot, "9007199254740993");
    const first = await installImmutableRelease({
      ...firstCandidate,
      releaseRoot: paths.releaseRoot,
    });
    await switchReleaseWithRollback({
      installed: first,
      ...paths,
      postSwitchSmoke: async () => undefined,
    });

    expect(
      await shouldPublishGeneration({
        targetGeneration: "9007199254740993",
        currentLink: paths.currentLink,
        releaseRoot: paths.releaseRoot,
      }),
    ).toBe(false);
    expect(
      await shouldPublishGeneration({
        targetGeneration: "9007199254740992",
        currentLink: paths.currentLink,
        releaseRoot: paths.releaseRoot,
      }),
    ).toBe(false);
    expect(
      await shouldPublishGeneration({
        targetGeneration: "9223372036854775807",
        currentLink: paths.currentLink,
        releaseRoot: paths.releaseRoot,
      }),
    ).toBe(true);
  });

  it("returns a final no-op if current advances before the switch", async () => {
    const paths = await fixtureRoot();
    const currentCandidate = await candidate(paths.releaseRoot, "5");
    const current = await installImmutableRelease({
      ...currentCandidate,
      releaseRoot: paths.releaseRoot,
    });
    await switchReleaseWithRollback({
      installed: current,
      ...paths,
      postSwitchSmoke: async () => undefined,
    });
    const staleCandidate = await candidate(paths.releaseRoot, "4");
    const stale = await installImmutableRelease({
      ...staleCandidate,
      releaseRoot: paths.releaseRoot,
    });

    await expect(
      switchReleaseWithRollback({
        installed: stale,
        ...paths,
        postSwitchSmoke: async () => {
          throw new Error("stale smoke must not run");
        },
      }),
    ).resolves.toBe("NO_PUBLIC_CHANGE");
    expect(
      (await readReleaseLink(paths.currentLink, paths.releaseRoot))?.manifest
        .publishGeneration,
    ).toBe("5");
  });

  it("restores current and previous when post-switch smoke fails", async () => {
    const paths = await fixtureRoot();
    const firstCandidate = await candidate(paths.releaseRoot, "2");
    const first = await installImmutableRelease({
      ...firstCandidate,
      releaseRoot: paths.releaseRoot,
    });
    await switchReleaseWithRollback({
      installed: first,
      ...paths,
      postSwitchSmoke: async () => undefined,
    });
    const secondCandidate = await candidate(paths.releaseRoot, "3");
    const second = await installImmutableRelease({
      ...secondCandidate,
      releaseRoot: paths.releaseRoot,
    });

    await expect(
      switchReleaseWithRollback({
        installed: second,
        ...paths,
        postSwitchSmoke: async () => {
          throw new Error("synthetic smoke failure");
        },
      }),
    ).rejects.toThrowError(/smoke/i);
    expect((await readReleaseLink(paths.currentLink, paths.releaseRoot))?.manifest.publishGeneration).toBe("2");
    expect(await readReleaseLink(paths.previousLink, paths.releaseRoot)).toBeNull();
  });

  it("keeps old current and restores previous when execution stops between link updates", async () => {
    const paths = await fixtureRoot();
    const firstCandidate = await candidate(paths.releaseRoot, "2");
    const first = await installImmutableRelease({
      ...firstCandidate,
      releaseRoot: paths.releaseRoot,
    });
    await switchReleaseWithRollback({
      installed: first,
      ...paths,
      postSwitchSmoke: async () => undefined,
    });
    const secondCandidate = await candidate(paths.releaseRoot, "3");
    const second = await installImmutableRelease({
      ...secondCandidate,
      releaseRoot: paths.releaseRoot,
    });

    await expect(
      switchReleaseWithRollback({
        installed: second,
        ...paths,
        beforeCurrentSwitch: async () => {
          throw new Error("synthetic crash point");
        },
        postSwitchSmoke: async () => undefined,
      }),
    ).rejects.toThrowError(/crash point/u);
    expect(
      (await readReleaseLink(paths.currentLink, paths.releaseRoot))?.manifest
        .publishGeneration,
    ).toBe("2");
    expect(await readReleaseLink(paths.previousLink, paths.releaseRoot)).toBeNull();
  });

  it("removes a failed first current when no rollback target exists", async () => {
    const paths = await fixtureRoot();
    const firstCandidate = await candidate(paths.releaseRoot, "1");
    const first = await installImmutableRelease({
      ...firstCandidate,
      releaseRoot: paths.releaseRoot,
    });

    await expect(
      switchReleaseWithRollback({
        installed: first,
        ...paths,
        postSwitchSmoke: async () => {
          throw new Error("synthetic first-release smoke failure");
        },
      }),
    ).rejects.toThrowError(/smoke/i);
    expect(await readReleaseLink(paths.currentLink, paths.releaseRoot)).toBeNull();
    expect(await readReleaseLink(paths.previousLink, paths.releaseRoot)).toBeNull();
  });

  it("accepts an exact immutable collision and rejects different content", async () => {
    const paths = await fixtureRoot();
    const initialCandidate = await candidate(paths.releaseRoot, "7");
    const initial = await installImmutableRelease({
      ...initialCandidate,
      releaseRoot: paths.releaseRoot,
    });
    const identicalCandidate = await candidate(paths.releaseRoot, "7");
    const identical = await installImmutableRelease({
      ...identicalCandidate,
      releaseRoot: paths.releaseRoot,
    });
    expect(identical.manifest).toEqual(initial.manifest);

    const conflictingCandidate = await candidate(
      paths.releaseRoot,
      "7",
      "different immutable bytes",
    );
    await expect(
      installImmutableRelease({
        ...conflictingCandidate,
        releaseRoot: paths.releaseRoot,
      }),
    ).rejects.toThrowError(/collision/i);
  });

  it("retains newest releases and always preserves current and previous", async () => {
    const paths = await fixtureRoot();
    const installed = [];
    for (const generation of ["1", "2", "3", "4", "5", "6"]) {
      const releaseCandidate = await candidate(paths.releaseRoot, generation);
      installed.push(
        await installImmutableRelease({
          ...releaseCandidate,
          releaseRoot: paths.releaseRoot,
        }),
      );
    }
    await switchReleaseWithRollback({
      installed: installed[4],
      ...paths,
      postSwitchSmoke: async () => undefined,
    });
    await switchReleaseWithRollback({
      installed: installed[5],
      ...paths,
      postSwitchSmoke: async () => undefined,
    });

    await pruneSuccessfulReleases({
      ...paths,
      retention: 3,
    });

    for (const release of installed.slice(3)) {
      await expect(access(release.packageRoot)).resolves.toBeUndefined();
    }
    for (const release of installed.slice(0, 3)) {
      await expect(access(release.packageRoot)).rejects.toMatchObject({
        code: "ENOENT",
      });
    }
    expect(
      (await readReleaseLink(paths.currentLink, paths.releaseRoot))?.manifest
        .publishGeneration,
    ).toBe("6");
    expect(
      (await readReleaseLink(paths.previousLink, paths.releaseRoot))?.manifest
        .publishGeneration,
    ).toBe("5");
  });

  it("fails closed for non-symlink and malformed current authority", async () => {
    const nonSymlink = await fixtureRoot();
    await writeFile(nonSymlink.currentLink, "not-a-link");
    await expect(
      readReleaseLink(nonSymlink.currentLink, nonSymlink.releaseRoot),
    ).rejects.toThrowError(/invalid/i);

    const malformed = await fixtureRoot();
    const releaseCandidate = await candidate(malformed.releaseRoot, "2");
    const installed = await installImmutableRelease({
      ...releaseCandidate,
      releaseRoot: malformed.releaseRoot,
    });
    await switchReleaseWithRollback({
      installed,
      ...malformed,
      postSwitchSmoke: async () => undefined,
    });
    await writeFile(
      join(installed.packageRoot, "release-manifest.json"),
      "{}\n",
    );
    await expect(
      readReleaseLink(malformed.currentLink, malformed.releaseRoot),
    ).rejects.toThrowError(/invalid/i);
  });

  it("rejects a current symlink outside the configured release root", async () => {
    const paths = await fixtureRoot();
    const outside = join(paths.root, "outside", "site");
    await mkdir(outside, { recursive: true });
    await symlink(outside, paths.currentLink, "dir");

    await expect(
      readReleaseLink(paths.currentLink, paths.releaseRoot),
    ).rejects.toThrowError(/invalid/i);
  });

  it("rejects an exact-parent current package even when its manifest and site are valid", async () => {
    const paths = await fixtureRoot();
    await exactParentRelease(paths, paths.currentLink);

    await expect(
      readReleaseLink(paths.currentLink, paths.releaseRoot),
    ).rejects.toMatchObject({ code: "RELEASE_CURRENT_INVALID" });
  });

  it("rejects an exact-parent previous package even when its manifest and site are valid", async () => {
    const paths = await fixtureRoot();
    await exactParentRelease(paths, paths.previousLink);

    await expect(
      readReleaseLink(paths.previousLink, paths.releaseRoot),
    ).rejects.toMatchObject({ code: "RELEASE_CURRENT_INVALID" });
  });
});
