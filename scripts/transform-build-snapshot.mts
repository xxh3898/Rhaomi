import { readFile } from "node:fs/promises";
import { join, resolve } from "node:path";

import {
  BuildTransformError,
  MediaContentNotFoundError,
  isBuildTransformError,
  parseBuildSnapshotV1,
  transformBuildSnapshot,
  type BuildMediaAssetV1,
  type MediaContent,
  type MediaContentProvider,
} from "../src/build-transformer/index.mts";

type CliArguments = Readonly<{
  snapshotPath: string;
  mediaRoot: string;
  outputRoot: string;
}>;

function parseArguments(values: readonly string[]): CliArguments {
  const parsed = new Map<string, string>();
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (
      value === undefined ||
      !["--snapshot", "--media-root", "--output"].includes(key) ||
      parsed.has(key)
    ) {
      throw new BuildTransformError("SNAPSHOT_INVALID");
    }
    parsed.set(key, value);
  }
  const snapshotPath = parsed.get("--snapshot");
  const mediaRoot = parsed.get("--media-root");
  const outputRoot = parsed.get("--output");
  if (
    parsed.size !== 3 ||
    snapshotPath === undefined ||
    mediaRoot === undefined ||
    outputRoot === undefined
  ) {
    throw new BuildTransformError("SNAPSHOT_INVALID");
  }
  return {
    snapshotPath: resolve(snapshotPath),
    mediaRoot: resolve(mediaRoot),
    outputRoot: resolve(outputRoot),
  };
}

class FileSystemMediaContentProvider implements MediaContentProvider {
  readonly #mediaRoot: string;
  readonly #assets: ReadonlyMap<string, BuildMediaAssetV1>;

  constructor(mediaRoot: string, assets: readonly BuildMediaAssetV1[]) {
    this.#mediaRoot = mediaRoot;
    this.#assets = new Map(assets.map((asset) => [asset.id, asset]));
  }

  async get(mediaId: string): Promise<MediaContent> {
    const asset = this.#assets.get(mediaId);
    if (asset === undefined) throw new MediaContentNotFoundError();
    const extension = asset.contentType === "image/jpeg" ? "jpg" : "png";
    try {
      return {
        contentType: asset.contentType,
        bytes: await readFile(join(this.#mediaRoot, `${asset.id}.${extension}`)),
      };
    } catch {
      throw new MediaContentNotFoundError();
    }
  }
}

async function main(): Promise<void> {
  const args = parseArguments(process.argv.slice(2));
  let rawSnapshot: unknown;
  try {
    rawSnapshot = JSON.parse(await readFile(args.snapshotPath, "utf8"));
  } catch {
    throw new BuildTransformError("SNAPSHOT_INVALID");
  }
  const snapshot = parseBuildSnapshotV1(rawSnapshot);
  const result = await transformBuildSnapshot({
    snapshot,
    mediaContentProvider: new FileSystemMediaContentProvider(
      args.mediaRoot,
      snapshot.mediaAssets,
    ),
    stagingOutputRoot: args.outputRoot,
  });
  console.log(
    `Build snapshot transform completed: contentRevision=${result.content.contentRevision} publishGeneration=${result.content.publishGeneration} files=${result.publicFileCount}`,
  );
}

main().catch((error: unknown) => {
  const safeError = isBuildTransformError(error)
    ? error
    : new BuildTransformError("OUTPUT_FAILED");
  console.error(`${safeError.code}: ${safeError.message}`);
  process.exitCode = 1;
});
