import {
  BuildPreparationError,
  normalizePreparationError,
  preparePublicationStaging,
} from "../src/build-orchestration/index.mts";

type CliArguments = Readonly<{
  publishGeneration: string;
  outputRoot: string;
}>;

function parseArguments(values: readonly string[]): CliArguments {
  const parsed = new Map<string, string>();
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (
      value === undefined ||
      !["--publish-generation", "--output"].includes(key) ||
      parsed.has(key)
    ) {
      throw new BuildPreparationError("BUILD_API_CONFIG_INVALID");
    }
    parsed.set(key, value);
  }
  const publishGeneration = parsed.get("--publish-generation");
  const outputRoot = parsed.get("--output");
  if (
    parsed.size !== 2 ||
    publishGeneration === undefined ||
    outputRoot === undefined ||
    outputRoot.length === 0
  ) {
    throw new BuildPreparationError("BUILD_API_CONFIG_INVALID");
  }
  return { publishGeneration, outputRoot };
}

const EXIT_CODE = {
  TERMINAL: 20,
  TRANSIENT: 21,
  GENERATION: 22,
} as const;

async function main(): Promise<void> {
  const args = parseArguments(process.argv.slice(2));
  const result = await preparePublicationStaging({
    publishGeneration: args.publishGeneration,
    outputRoot: args.outputRoot,
  });
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

main().catch((error: unknown) => {
  const safeError = normalizePreparationError(error);
  process.stderr.write(
    `${JSON.stringify({
      status: "FAILED",
      code: safeError.code,
      disposition: safeError.disposition,
    })}\n`,
  );
  process.exitCode = EXIT_CODE[safeError.disposition];
});
