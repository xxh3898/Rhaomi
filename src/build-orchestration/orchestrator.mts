import {
  BuildTransformError,
  transformBuildSnapshot,
} from "../build-transformer/index.mts";
import {
  loadBuildApiConfig,
  parsePublishGeneration,
  validateStagingOutputRoot,
  type BuildApiEnvironment,
} from "./config.mts";
import {
  BuildPreparationError,
  isBuildPreparationError,
} from "./errors.mts";
import { BuildApiClient, type BuildApiFetch } from "./http-client.mts";

export type PublicationStagingResult = Readonly<{
  status: "STAGING_PREPARED";
  contentRevision: number;
  publishGeneration: number;
  generatedAt: string;
  publicFileCount: number;
}>;

function mapTransformError(error: BuildTransformError): BuildPreparationError {
  switch (error.code) {
    case "SNAPSHOT_INVALID":
      return new BuildPreparationError("BUILD_SNAPSHOT_INVALID");
    case "MEDIA_NOT_FOUND":
      return new BuildPreparationError("BUILD_MEDIA_NOT_FOUND");
    case "MEDIA_INVALID":
      return new BuildPreparationError("BUILD_MEDIA_INVALID");
    case "MEDIA_TRANSFORM_FAILED":
      return new BuildPreparationError("BUILD_TRANSFORM_FAILED");
    case "OUTPUT_FAILED":
      return new BuildPreparationError("BUILD_OUTPUT_FAILED");
  }
}

export function normalizePreparationError(
  error: unknown,
): BuildPreparationError {
  if (isBuildPreparationError(error)) return error;
  if (error instanceof BuildTransformError) return mapTransformError(error);
  return new BuildPreparationError("BUILD_TRANSFORM_FAILED");
}

export async function preparePublicationStaging(input: Readonly<{
  publishGeneration: string;
  outputRoot: string;
  environment?: BuildApiEnvironment;
  fetchImpl?: BuildApiFetch;
  requestTimeoutMs?: number;
}>): Promise<PublicationStagingResult> {
  try {
    const generation = parsePublishGeneration(input.publishGeneration);
    const outputRoot = validateStagingOutputRoot(input.outputRoot);
    const config = loadBuildApiConfig(
      input.environment ?? process.env,
      input.requestTimeoutMs,
    );
    const client = new BuildApiClient(config, input.fetchImpl);
    const snapshot = await client.fetchSnapshot(generation);
    const mediaContentProvider = client.createMediaContentProvider(
      generation,
      snapshot.mediaAssets,
    );

    // Preserve transport retry categories before the transformer intentionally
    // collapses unknown provider failures into its deterministic media boundary.
    await mediaContentProvider.prefetchAll();

    const transformed = await transformBuildSnapshot({
      snapshot,
      mediaContentProvider,
      stagingOutputRoot: outputRoot,
    });
    return {
      status: "STAGING_PREPARED",
      contentRevision: transformed.content.contentRevision,
      publishGeneration: transformed.content.publishGeneration,
      generatedAt: transformed.content.generatedAt,
      publicFileCount: transformed.publicFileCount,
    };
  } catch (error) {
    throw normalizePreparationError(error);
  }
}
