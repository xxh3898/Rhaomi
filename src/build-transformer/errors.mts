export const BUILD_TRANSFORM_ERROR_CODES = [
  "SNAPSHOT_INVALID",
  "MEDIA_NOT_FOUND",
  "MEDIA_INVALID",
  "MEDIA_TRANSFORM_FAILED",
  "OUTPUT_FAILED",
] as const;

export type BuildTransformErrorCode =
  (typeof BUILD_TRANSFORM_ERROR_CODES)[number];

const SAFE_MESSAGES: Readonly<Record<BuildTransformErrorCode, string>> = {
  SNAPSHOT_INVALID: "Build snapshot contract is invalid.",
  MEDIA_NOT_FOUND: "Required media content is unavailable.",
  MEDIA_INVALID: "Required media content is invalid.",
  MEDIA_TRANSFORM_FAILED: "Public media transform failed.",
  OUTPUT_FAILED: "Generated staging output failed.",
};

export class BuildTransformError extends Error {
  readonly code: BuildTransformErrorCode;

  constructor(code: BuildTransformErrorCode) {
    super(SAFE_MESSAGES[code]);
    this.name = "BuildTransformError";
    this.code = code;
  }
}

export class MediaContentNotFoundError extends Error {
  constructor() {
    super(SAFE_MESSAGES.MEDIA_NOT_FOUND);
    this.name = "MediaContentNotFoundError";
  }
}

export function isBuildTransformError(
  value: unknown,
): value is BuildTransformError {
  return value instanceof BuildTransformError;
}

export function fail(code: BuildTransformErrorCode): never {
  throw new BuildTransformError(code);
}
