import { randomUUID } from "node:crypto";
import {
  cp,
  mkdir,
  readFile,
  rm,
  rmdir,
  writeFile,
} from "node:fs/promises";
import { join } from "node:path";

import {
  BuildPreparationError,
  isBuildPreparationError,
  preparePublicationStaging,
  type BuildApiEnvironment,
  type BuildApiFetch,
} from "../build-orchestration/index.mts";
import { parsePublishGeneration } from "../build-orchestration/config.mts";
import { parseGeneratedArtifactsV2 } from "../public-site/contracts.mts";
import {
  loadPublicationReleaseConfig,
  releaseIdFor,
  type PublicationReleaseEnvironment,
  type ReleaseManifestV1,
} from "./contracts.mts";
import {
  PublicationReleaseError,
  isPublicationReleaseError,
  type PublicationFailureDisposition,
} from "./errors.mts";
import {
  installImmutableRelease,
  pruneSuccessfulReleases,
  readReleaseLink,
  removeUnreferencedInstalledRelease,
  shouldPublishGeneration,
  switchReleaseWithRollback,
  type ImmutableInstallResult,
} from "./filesystem.mts";
import { siteTreeSha256 } from "./file-tree.mts";
import { buildIsolatedNextExport } from "./next-build.mts";
import { validateStaticExport } from "./validator.mts";
import { smokeStaticServingPath } from "./serving-smoke.mts";

export type PublicationReleaseResult = Readonly<{
  status: "PUBLISHED" | "NO_PUBLIC_CHANGE";
  retentionStatus: "COMPLETE" | "DEFERRED" | "NOT_APPLICABLE";
  contentRevision: string;
  publishGeneration: string;
  generatedAt: string;
  releaseId: string;
}>;

export type SafePublicationFailure = Readonly<{
  code: string;
  disposition: PublicationFailureDisposition;
}>;

async function readJson(path: string): Promise<unknown> {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch {
    throw new PublicationReleaseError("RELEASE_INPUT_INVALID");
  }
}

function manifestJson(manifest: ReleaseManifestV1): string {
  return `${JSON.stringify(manifest, null, 2)}\n`;
}

export function normalizePublicationFailure(error: unknown): SafePublicationFailure {
  if (isBuildPreparationError(error)) {
    return { code: error.code, disposition: error.disposition };
  }
  if (isPublicationReleaseError(error)) {
    return { code: error.code, disposition: error.disposition };
  }
  return {
    code: "RELEASE_FILESYSTEM_FAILED",
    disposition: "TRANSIENT",
  };
}

export async function publishStaticRelease(input: Readonly<{
  publishGeneration: string;
  environment?: PublicationReleaseEnvironment & BuildApiEnvironment;
  fetchImpl?: BuildApiFetch;
  postSwitchSmoke?: (siteRoot: string) => Promise<void>;
}>): Promise<PublicationReleaseResult> {
  const environment = input.environment ?? process.env;
  const targetGeneration = parsePublishGeneration(input.publishGeneration).decimal;
  const config = loadPublicationReleaseConfig(environment);
  const runId = randomUUID();
  const sessionRoot = join(config.workRoot, `.run-${runId}`);
  const stagingRoot = join(sessionRoot, "staging");
  const buildWorkspaceParent = join(
    config.sourceRoot,
    ".rhaomi-publication-work",
  );
  const workspaceRoot = join(buildWorkspaceParent, `.run-${runId}`);
  const candidateRoot = join(config.releaseRoot, `.candidate-${runId}`);
  let candidateCreated = false;
  let installedCandidate: ImmutableInstallResult | null = null;

  try {
    await mkdir(config.workRoot, { recursive: true });
    await mkdir(config.releaseRoot, { recursive: true });
    await mkdir(buildWorkspaceParent, { recursive: true });
    await mkdir(sessionRoot, { recursive: false });

    const staging = await preparePublicationStaging({
      publishGeneration: targetGeneration,
      outputRoot: stagingRoot,
      environment,
      fetchImpl: input.fetchImpl,
    });
    const artifacts = parseGeneratedArtifactsV2(
      await readJson(join(stagingRoot, "src", "generated", "content.json")),
      await readJson(
        join(stagingRoot, "src", "generated", "media-manifest.json"),
      ),
    );
    if (
      staging.contentRevision !== artifacts.content.contentRevision ||
      staging.publishGeneration !== artifacts.content.publishGeneration ||
      staging.generatedAt !== artifacts.content.generatedAt ||
      artifacts.content.publishGeneration !== targetGeneration
    ) {
      throw new PublicationReleaseError("RELEASE_INPUT_INVALID");
    }

    const outputRoot = await buildIsolatedNextExport({
      sourceRoot: config.sourceRoot,
      stagingRoot,
      workspaceRoot,
      publicSiteUrl: config.publicSiteUrl,
      timeoutMs: config.buildTimeoutMs,
      noticeCount: artifacts.content.notices.length,
    });
    await mkdir(candidateRoot, { recursive: false });
    candidateCreated = true;
    const candidateSiteRoot = join(candidateRoot, "site");
    await cp(outputRoot, candidateSiteRoot, {
      recursive: true,
      errorOnExist: true,
      force: false,
    });
    const releaseId = releaseIdFor(
      artifacts.content.contentRevision,
      artifacts.content.publishGeneration,
      config.codeSha,
    );
    const manifest: ReleaseManifestV1 = {
      schemaVersion: 1,
      releaseId,
      contentRevision: artifacts.content.contentRevision,
      publishGeneration: artifacts.content.publishGeneration,
      generatedAt: artifacts.content.generatedAt,
      codeSha: config.codeSha,
      codeImageTag: config.codeImageTag,
      codeImageDigest: config.codeImageDigest,
      flywayVersion: config.flywayVersion,
      sbomReference: config.sbomReference,
      siteSha256: await siteTreeSha256(candidateSiteRoot),
    };
    await writeFile(
      join(candidateRoot, "release-manifest.json"),
      manifestJson(manifest),
      { flag: "wx" },
    );

    const forbiddenValues = [
      environment.BUILD_API_CREDENTIAL ?? "",
      environment.BUILD_API_INTERNAL_URL ?? "",
      config.sourceRoot,
      config.workRoot,
      config.releaseRoot,
      config.currentLink,
      config.previousLink,
    ];
    const smoke = async (siteRoot: string): Promise<void> => {
      await validateStaticExport({
        siteRoot,
        artifacts,
        publicSiteUrl: config.publicSiteUrl,
        forbiddenValues,
      });
    };
    await smoke(candidateSiteRoot);
    await smokeStaticServingPath({
      siteRoot: candidateSiteRoot,
      failureCode: "RELEASE_VALIDATION_FAILED",
      noticePath: artifacts.content.notices[0]
        ? `/notices/${artifacts.content.notices[0].slug}/`
        : undefined,
      mediaPath: artifacts.mediaManifest.items[0]?.variants[0]?.publicPath,
    });

    if (
      !(await shouldPublishGeneration({
        targetGeneration,
        currentLink: config.currentLink,
        releaseRoot: config.releaseRoot,
      }))
    ) {
      await rm(candidateRoot, { recursive: true, force: false });
      candidateCreated = false;
      return {
        status: "NO_PUBLIC_CHANGE",
        retentionStatus: "NOT_APPLICABLE",
        contentRevision: artifacts.content.contentRevision,
        publishGeneration: artifacts.content.publishGeneration,
        generatedAt: artifacts.content.generatedAt,
        releaseId,
      };
    }

    const installed = await installImmutableRelease({
      candidateRoot,
      releaseRoot: config.releaseRoot,
      manifest,
    });
    installedCandidate = installed;
    candidateCreated = false;
    const switchResult = await switchReleaseWithRollback({
      installed,
      releaseRoot: config.releaseRoot,
      currentLink: config.currentLink,
      previousLink: config.previousLink,
      postSwitchSmoke: async () => {
        const current = await readReleaseLink(
          config.currentLink,
          config.releaseRoot,
        );
        if (
          current === null ||
          current.manifest.releaseId !== installed.manifest.releaseId
        ) {
          throw new PublicationReleaseError("RELEASE_POST_SWITCH_FAILED");
        }
        await smoke(current.siteRoot);
        await smokeStaticServingPath({
          siteRoot: config.currentLink,
          noticePath: artifacts.content.notices[0]
            ? `/notices/${artifacts.content.notices[0].slug}/`
            : undefined,
          mediaPath:
            artifacts.mediaManifest.items[0]?.variants[0]?.publicPath,
        });
        await input.postSwitchSmoke?.(current.siteRoot);
      },
    });
    if (switchResult === "NO_PUBLIC_CHANGE") {
      if (installed.created) {
        await removeUnreferencedInstalledRelease({
          installed,
          releaseRoot: config.releaseRoot,
          currentLink: config.currentLink,
          previousLink: config.previousLink,
        });
        installedCandidate = null;
      }
      return {
        status: "NO_PUBLIC_CHANGE",
        retentionStatus: "NOT_APPLICABLE",
        contentRevision: artifacts.content.contentRevision,
        publishGeneration: artifacts.content.publishGeneration,
        generatedAt: artifacts.content.generatedAt,
        releaseId,
      };
    }
    let retentionStatus: "COMPLETE" | "DEFERRED" = "COMPLETE";
    try {
      await pruneSuccessfulReleases({
        releaseRoot: config.releaseRoot,
        currentLink: config.currentLink,
        previousLink: config.previousLink,
        retention: config.releaseRetention,
      });
    } catch (error) {
      if (!isPublicationReleaseError(error)) throw error;
      retentionStatus = "DEFERRED";
    }
    return {
      status: "PUBLISHED",
      retentionStatus,
      contentRevision: artifacts.content.contentRevision,
      publishGeneration: artifacts.content.publishGeneration,
      generatedAt: artifacts.content.generatedAt,
      releaseId,
    };
  } catch (error) {
    if (installedCandidate?.created) {
      await removeUnreferencedInstalledRelease({
        installed: installedCandidate,
        releaseRoot: config.releaseRoot,
        currentLink: config.currentLink,
        previousLink: config.previousLink,
      });
      installedCandidate = null;
    }
    if (error instanceof BuildPreparationError || error instanceof PublicationReleaseError) {
      throw error;
    }
    throw new PublicationReleaseError("RELEASE_FILESYSTEM_FAILED");
  } finally {
    if (candidateCreated) {
      await rm(candidateRoot, { recursive: true, force: true }).catch(() => undefined);
    }
    await rm(workspaceRoot, { recursive: true, force: true }).catch(() => undefined);
    await rmdir(buildWorkspaceParent).catch(() => undefined);
    await rm(sessionRoot, { recursive: true, force: true }).catch(() => undefined);
  }
}
