import { randomUUID } from "node:crypto";
import {
  lstat,
  readdir,
  readFile,
  readlink,
  realpath,
  rename,
  rm,
  symlink,
  unlink,
} from "node:fs/promises";
import {
  basename,
  dirname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from "node:path";

import {
  compareGenerations,
  parseReleaseManifest,
  type ReleaseManifestV1,
} from "./contracts.mts";
import { releaseFail } from "./errors.mts";
import { siteTreeSha256 } from "./file-tree.mts";

export type InstalledRelease = Readonly<{
  packageRoot: string;
  siteRoot: string;
  manifest: ReleaseManifestV1;
}>;

export type ImmutableInstallResult = InstalledRelease &
  Readonly<{ created: boolean }>;

export type CurrentReleaseState = InstalledRelease &
  Readonly<{ linkTarget: string }>;

async function pathState(path: string) {
  try {
    return await lstat(path);
  } catch (error) {
    if (
      typeof error === "object" &&
      error !== null &&
      "code" in error &&
      error.code === "ENOENT"
    ) {
      return null;
    }
    releaseFail("RELEASE_FILESYSTEM_FAILED");
  }
}

async function readManifest(packageRoot: string): Promise<ReleaseManifestV1> {
  const manifestPath = join(packageRoot, "release-manifest.json");
  const manifestStat = await pathState(manifestPath);
  if (
    manifestStat === null ||
    !manifestStat.isFile() ||
    manifestStat.isSymbolicLink() ||
    manifestStat.size > 32 * 1024
  ) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  let value: unknown;
  try {
    value = JSON.parse(await readFile(manifestPath, "utf8"));
  } catch {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  return parseReleaseManifest(value);
}

function directChildName(parentPath: string, childPath: string): string | null {
  const child = relative(resolve(parentPath), resolve(childPath));
  if (
    child.length === 0 ||
    child === ".." ||
    child.startsWith(`..${sep}`) ||
    isAbsolute(child) ||
    child.includes(sep)
  ) {
    return null;
  }
  return child;
}

export async function inspectInstalledRelease(
  packageRoot: string,
  releaseRoot: string,
): Promise<InstalledRelease> {
  const normalizedReleaseRoot = resolve(releaseRoot);
  const normalizedPackageRoot = resolve(packageRoot);
  const child = directChildName(normalizedReleaseRoot, normalizedPackageRoot);
  if (
    child === null ||
    basename(normalizedPackageRoot).startsWith(".")
  ) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  const packageStat = await pathState(normalizedPackageRoot);
  const siteRoot = join(normalizedPackageRoot, "site");
  const siteStat = await pathState(siteRoot);
  if (
    packageStat === null ||
    siteStat === null ||
    !packageStat.isDirectory() ||
    !siteStat.isDirectory() ||
    packageStat.isSymbolicLink() ||
    siteStat.isSymbolicLink()
  ) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  const manifest = await readManifest(normalizedPackageRoot);
  if (
    manifest.releaseId !== basename(normalizedPackageRoot) ||
    (await siteTreeSha256(siteRoot)) !== manifest.siteSha256
  ) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  return { packageRoot: normalizedPackageRoot, siteRoot, manifest };
}

export async function readReleaseLink(
  linkPath: string,
  releaseRoot: string,
): Promise<CurrentReleaseState | null> {
  const linkStat = await pathState(linkPath);
  if (linkStat === null) return null;
  if (!linkStat.isSymbolicLink()) releaseFail("RELEASE_CURRENT_INVALID");
  const linkTarget = await readlink(linkPath).catch(() =>
    releaseFail("RELEASE_CURRENT_INVALID"),
  );
  const target = resolve(dirname(linkPath), linkTarget);
  if (basename(target) !== "site") releaseFail("RELEASE_CURRENT_INVALID");
  const installed = await inspectInstalledRelease(dirname(target), releaseRoot);
  const actualTarget = await realpath(target).catch(() =>
    releaseFail("RELEASE_CURRENT_INVALID"),
  );
  if (actualTarget !== installed.siteRoot) releaseFail("RELEASE_CURRENT_INVALID");
  return { ...installed, linkTarget };
}

async function atomicSymlink(linkPath: string, target: string): Promise<void> {
  const existing = await pathState(linkPath);
  if (existing !== null && !existing.isSymbolicLink()) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  const temporary = join(
    dirname(linkPath),
    `.${basename(linkPath)}.next-${randomUUID()}`,
  );
  const relativeTarget = relative(dirname(linkPath), target);
  try {
    await symlink(relativeTarget, temporary, "dir");
    await rename(temporary, linkPath);
  } catch {
    await unlink(temporary).catch(() => undefined);
    releaseFail("RELEASE_FILESYSTEM_FAILED");
  }
}

async function removeSymlink(linkPath: string): Promise<void> {
  const state = await pathState(linkPath);
  if (state === null) return;
  if (!state.isSymbolicLink()) releaseFail("RELEASE_CURRENT_INVALID");
  await unlink(linkPath).catch(() => releaseFail("RELEASE_FILESYSTEM_FAILED"));
}

async function restoreLink(
  linkPath: string,
  previous: CurrentReleaseState | null,
): Promise<void> {
  if (previous === null) {
    await removeSymlink(linkPath);
  } else {
    await atomicSymlink(linkPath, previous.siteRoot);
  }
}

export async function installImmutableRelease(input: Readonly<{
  candidateRoot: string;
  releaseRoot: string;
  manifest: ReleaseManifestV1;
}>): Promise<ImmutableInstallResult> {
  const candidateName = directChildName(input.releaseRoot, input.candidateRoot);
  if (
    candidateName === null ||
    !candidateName.startsWith(".candidate-")
  ) {
    releaseFail("RELEASE_INPUT_INVALID");
  }
  const candidate = await inspectCandidate(input.candidateRoot, input.manifest);
  const finalRoot = join(input.releaseRoot, input.manifest.releaseId);
  const finalState = await pathState(finalRoot);
  if (finalState !== null) {
    const installed = await inspectInstalledRelease(finalRoot, input.releaseRoot);
    if (
      JSON.stringify(installed.manifest) !== JSON.stringify(input.manifest) ||
      installed.manifest.siteSha256 !== candidate.manifest.siteSha256
    ) {
      releaseFail("RELEASE_COLLISION");
    }
    await rm(input.candidateRoot, { recursive: true, force: false }).catch(() =>
      releaseFail("RELEASE_FILESYSTEM_FAILED"),
    );
    return { ...installed, created: false };
  }
  await rename(input.candidateRoot, finalRoot).catch(() =>
    releaseFail("RELEASE_FILESYSTEM_FAILED"),
  );
  return {
    ...(await inspectInstalledRelease(finalRoot, input.releaseRoot)),
    created: true,
  };
}

export async function removeUnreferencedInstalledRelease(input: Readonly<{
  installed: InstalledRelease;
  releaseRoot: string;
  currentLink: string;
  previousLink: string;
}>): Promise<boolean> {
  const current = await readReleaseLink(input.currentLink, input.releaseRoot);
  const previous = await readReleaseLink(input.previousLink, input.releaseRoot);
  const target = resolve(input.installed.packageRoot);
  if (
    current?.packageRoot === target ||
    previous?.packageRoot === target
  ) {
    return false;
  }
  const verified = await inspectInstalledRelease(target, input.releaseRoot);
  if (
    JSON.stringify(verified.manifest) !==
    JSON.stringify(input.installed.manifest)
  ) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  await rm(target, { recursive: true, force: false }).catch(() =>
    releaseFail("RELEASE_FILESYSTEM_FAILED"),
  );
  return true;
}

async function inspectCandidate(
  candidateRoot: string,
  expected: ReleaseManifestV1,
): Promise<InstalledRelease> {
  const rootStat = await pathState(candidateRoot);
  const siteRoot = join(candidateRoot, "site");
  const siteStat = await pathState(siteRoot);
  if (
    rootStat === null ||
    siteStat === null ||
    !rootStat.isDirectory() ||
    !siteStat.isDirectory() ||
    rootStat.isSymbolicLink() ||
    siteStat.isSymbolicLink()
  ) {
    releaseFail("RELEASE_INPUT_INVALID");
  }
  const manifest = await readManifest(candidateRoot);
  if (
    JSON.stringify(manifest) !== JSON.stringify(expected) ||
    (await siteTreeSha256(siteRoot)) !== manifest.siteSha256
  ) {
    releaseFail("RELEASE_INPUT_INVALID");
  }
  return { packageRoot: candidateRoot, siteRoot, manifest };
}

export async function shouldPublishGeneration(input: Readonly<{
  targetGeneration: string;
  currentLink: string;
  releaseRoot: string;
}>): Promise<boolean> {
  const current = await readReleaseLink(input.currentLink, input.releaseRoot);
  return (
    current === null ||
    compareGenerations(input.targetGeneration, current.manifest.publishGeneration) > 0
  );
}

export async function switchReleaseWithRollback(input: Readonly<{
  installed: InstalledRelease;
  releaseRoot: string;
  currentLink: string;
  previousLink: string;
  postSwitchSmoke: (siteRoot: string) => Promise<void>;
  beforeCurrentSwitch?: () => Promise<void>;
}>): Promise<"SWITCHED" | "NO_PUBLIC_CHANGE"> {
  const current = await readReleaseLink(input.currentLink, input.releaseRoot);
  const previous = await readReleaseLink(input.previousLink, input.releaseRoot);
  if (
    current !== null &&
    compareGenerations(
      input.installed.manifest.publishGeneration,
      current.manifest.publishGeneration,
    ) <= 0
  ) {
    return "NO_PUBLIC_CHANGE";
  }
  if (current === null && previous !== null) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }

  try {
    if (current !== null) {
      await atomicSymlink(input.previousLink, current.siteRoot);
    }
    await input.beforeCurrentSwitch?.();
    await atomicSymlink(input.currentLink, input.installed.siteRoot);
  } catch (error) {
    await restoreLink(input.previousLink, previous);
    throw error;
  }

  try {
    await input.postSwitchSmoke(input.currentLink);
  } catch {
    await restoreLink(input.currentLink, current);
    await restoreLink(input.previousLink, previous);
    if (current !== null) {
      await input.postSwitchSmoke(input.currentLink).catch(() => undefined);
    }
    releaseFail("RELEASE_POST_SWITCH_FAILED");
  }
  return "SWITCHED";
}

export async function pruneSuccessfulReleases(input: Readonly<{
  releaseRoot: string;
  currentLink: string;
  previousLink: string;
  retention: number;
  protectPackageRoots?: readonly string[];
}>): Promise<void> {
  if (
    !Number.isSafeInteger(input.retention) ||
    input.retention < 1 ||
    input.retention > 100
  ) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  const current = await readReleaseLink(input.currentLink, input.releaseRoot);
  const previous = await readReleaseLink(input.previousLink, input.releaseRoot);
  const protectedRoots = new Set(
    [
      current?.packageRoot,
      previous?.packageRoot,
      ...(input.protectPackageRoots ?? []),
    ]
      .filter((value): value is string => value !== undefined)
      .map((value) => resolve(value)),
  );
  const entries = await readdir(input.releaseRoot, { withFileTypes: true }).catch(
    () => releaseFail("RELEASE_FILESYSTEM_FAILED"),
  );
  const installed: InstalledRelease[] = [];
  for (const entry of entries) {
    if (entry.name.startsWith(".")) continue;
    if (!entry.isDirectory() || entry.isSymbolicLink()) continue;
    installed.push(
      await inspectInstalledRelease(
        join(input.releaseRoot, entry.name),
        input.releaseRoot,
      ),
    );
  }
  installed.sort((left, right) => {
    const generationOrder = compareGenerations(
      right.manifest.publishGeneration,
      left.manifest.publishGeneration,
    );
    return generationOrder === 0
      ? right.manifest.releaseId.localeCompare(left.manifest.releaseId)
      : generationOrder;
  });
  const retained = new Set(
    installed
      .slice(0, input.retention)
      .map((release) => release.packageRoot),
  );
  for (const release of installed) {
    if (
      retained.has(release.packageRoot) ||
      protectedRoots.has(release.packageRoot)
    ) {
      continue;
    }
    const verified = await inspectInstalledRelease(
      release.packageRoot,
      input.releaseRoot,
    );
    if (verified.manifest.releaseId !== release.manifest.releaseId) {
      releaseFail("RELEASE_CURRENT_INVALID");
    }
    await rm(release.packageRoot, { recursive: true, force: false }).catch(() =>
      releaseFail("RELEASE_FILESYSTEM_FAILED"),
    );
  }
}
