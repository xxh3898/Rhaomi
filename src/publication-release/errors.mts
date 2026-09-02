export const PUBLICATION_RELEASE_ERROR_CODES = [
  "RELEASE_CONFIG_INVALID",
  "RELEASE_INPUT_INVALID",
  "RELEASE_BUILD_FAILED",
  "RELEASE_VALIDATION_FAILED",
  "RELEASE_FILESYSTEM_FAILED",
  "RELEASE_COLLISION",
  "RELEASE_CURRENT_INVALID",
  "RELEASE_POST_SWITCH_FAILED",
] as const;

export type PublicationReleaseErrorCode =
  (typeof PUBLICATION_RELEASE_ERROR_CODES)[number];

export type PublicationFailureDisposition =
  | "TERMINAL"
  | "TRANSIENT"
  | "GENERATION";

const SAFE_MESSAGES: Readonly<Record<PublicationReleaseErrorCode, string>> = {
  RELEASE_CONFIG_INVALID: "Publication release configuration is invalid.",
  RELEASE_INPUT_INVALID: "Publication release input is invalid.",
  RELEASE_BUILD_FAILED: "Static release build failed.",
  RELEASE_VALIDATION_FAILED: "Static release validation failed.",
  RELEASE_FILESYSTEM_FAILED: "Publication release filesystem operation failed.",
  RELEASE_COLLISION: "Immutable release collision was detected.",
  RELEASE_CURRENT_INVALID: "Current release state is invalid.",
  RELEASE_POST_SWITCH_FAILED: "Post-switch smoke validation failed.",
};

const DISPOSITION: Readonly<
  Record<PublicationReleaseErrorCode, PublicationFailureDisposition>
> = {
  RELEASE_CONFIG_INVALID: "TERMINAL",
  RELEASE_INPUT_INVALID: "TERMINAL",
  RELEASE_BUILD_FAILED: "TERMINAL",
  RELEASE_VALIDATION_FAILED: "TERMINAL",
  RELEASE_FILESYSTEM_FAILED: "TRANSIENT",
  RELEASE_COLLISION: "TERMINAL",
  RELEASE_CURRENT_INVALID: "TERMINAL",
  RELEASE_POST_SWITCH_FAILED: "TRANSIENT",
};

export class PublicationReleaseError extends Error {
  readonly code: PublicationReleaseErrorCode;
  readonly disposition: PublicationFailureDisposition;

  constructor(code: PublicationReleaseErrorCode) {
    super(SAFE_MESSAGES[code]);
    this.name = "PublicationReleaseError";
    this.code = code;
    this.disposition = DISPOSITION[code];
  }
}

export function isPublicationReleaseError(
  value: unknown,
): value is PublicationReleaseError {
  return value instanceof PublicationReleaseError;
}

export function releaseFail(code: PublicationReleaseErrorCode): never {
  throw new PublicationReleaseError(code);
}
