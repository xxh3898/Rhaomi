export const BUILD_PREPARATION_ERROR_CODES = [
  "BUILD_API_CONFIG_INVALID",
  "BUILD_API_UNAUTHORIZED",
  "BUILD_GENERATION_NOT_ACTIVE",
  "BUILD_API_TRANSIENT",
  "BUILD_RESPONSE_INVALID",
  "BUILD_SNAPSHOT_INVALID",
  "BUILD_MEDIA_NOT_FOUND",
  "BUILD_MEDIA_UNAVAILABLE",
  "BUILD_MEDIA_INVALID",
  "BUILD_TRANSFORM_FAILED",
  "BUILD_OUTPUT_FAILED",
] as const;

export type BuildPreparationErrorCode =
  (typeof BUILD_PREPARATION_ERROR_CODES)[number];

export const BUILD_PREPARATION_DISPOSITIONS = [
  "TERMINAL",
  "TRANSIENT",
  "GENERATION",
] as const;

export type BuildPreparationDisposition =
  (typeof BUILD_PREPARATION_DISPOSITIONS)[number];

const DISPOSITION_BY_CODE: Readonly<
  Record<BuildPreparationErrorCode, BuildPreparationDisposition>
> = {
  BUILD_API_CONFIG_INVALID: "TERMINAL",
  BUILD_API_UNAUTHORIZED: "TERMINAL",
  BUILD_GENERATION_NOT_ACTIVE: "GENERATION",
  BUILD_API_TRANSIENT: "TRANSIENT",
  BUILD_RESPONSE_INVALID: "TERMINAL",
  BUILD_SNAPSHOT_INVALID: "TERMINAL",
  BUILD_MEDIA_NOT_FOUND: "TERMINAL",
  BUILD_MEDIA_UNAVAILABLE: "TRANSIENT",
  BUILD_MEDIA_INVALID: "TERMINAL",
  BUILD_TRANSFORM_FAILED: "TERMINAL",
  BUILD_OUTPUT_FAILED: "TERMINAL",
};

export class BuildPreparationError extends Error {
  readonly code: BuildPreparationErrorCode;
  readonly disposition: BuildPreparationDisposition;

  constructor(code: BuildPreparationErrorCode) {
    super(code);
    this.name = "BuildPreparationError";
    this.code = code;
    this.disposition = DISPOSITION_BY_CODE[code];
  }
}

export function isBuildPreparationError(
  value: unknown,
): value is BuildPreparationError {
  return value instanceof BuildPreparationError;
}

export function preparationFail(code: BuildPreparationErrorCode): never {
  throw new BuildPreparationError(code);
}
