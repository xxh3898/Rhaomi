import {
  normalizePublicationFailure,
  publishStaticRelease,
} from "../src/publication-release/index.mts";

function parseArguments(values: readonly string[]): string {
  if (
    values.length !== 2 ||
    values[0] !== "--publish-generation" ||
    values[1].length === 0
  ) {
    return "";
  }
  return values[1];
}

const EXIT_CODE = {
  TERMINAL: 20,
  TRANSIENT: 21,
  GENERATION: 22,
} as const;

async function main(): Promise<void> {
  const publishGeneration = parseArguments(process.argv.slice(2));
  const result = await publishStaticRelease({ publishGeneration });
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

main().catch((error: unknown) => {
  const safeError = normalizePublicationFailure(error);
  process.stderr.write(
    `${JSON.stringify({
      status: "FAILED",
      code: safeError.code,
      disposition: safeError.disposition,
    })}\n`,
  );
  process.exitCode = EXIT_CODE[safeError.disposition];
});
