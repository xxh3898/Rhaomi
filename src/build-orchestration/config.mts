import { resolve, sep } from "node:path";

import { preparationFail } from "./errors.mts";

const CREDENTIAL_PATTERN = /^[0-9a-f]{64}$/u;
const POSITIVE_DECIMAL_PATTERN = /^[1-9][0-9]*$/u;
const JAVA_LONG_MAX = BigInt("9223372036854775807");
const FORBIDDEN_OUTPUT_ROOTS = [
  "/srv/rhaomi/public",
  "/private/var/lib/rhaomi",
] as const;

export const DEFAULT_BUILD_API_REQUEST_TIMEOUT_MS = 10_000;

export type BuildApiEnvironment = Readonly<
  Record<string, string | undefined>
>;

export type BuildApiConfig = Readonly<{
  baseUrl: URL;
  credential: string;
  requestTimeoutMs: number;
}>;

export type PublishGeneration = Readonly<{
  decimal: string;
  value: bigint;
}>;

export function parsePublishGeneration(value: string): PublishGeneration {
  if (value.length > 19 || !POSITIVE_DECIMAL_PATTERN.test(value)) {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }

  let numericValue: bigint;
  try {
    numericValue = BigInt(value);
  } catch {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }
  if (numericValue > JAVA_LONG_MAX) {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }
  return { decimal: value, value: numericValue };
}

export function validateStagingOutputRoot(value: string): string {
  if (value.length === 0 || value !== value.trim()) {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }
  const outputRoot = resolve(value);
  if (
    FORBIDDEN_OUTPUT_ROOTS.some(
      (root) => outputRoot === root || outputRoot.startsWith(`${root}${sep}`),
    )
  ) {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }
  return outputRoot;
}

export function loadBuildApiConfig(
  environment: BuildApiEnvironment,
  requestTimeoutMs = DEFAULT_BUILD_API_REQUEST_TIMEOUT_MS,
): BuildApiConfig {
  const rawUrl = environment.BUILD_API_INTERNAL_URL;
  const credential = environment.BUILD_API_CREDENTIAL;
  if (
    rawUrl === undefined ||
    rawUrl.length === 0 ||
    rawUrl !== rawUrl.trim() ||
    !/^https?:\/\/[^/\\]/iu.test(rawUrl) ||
    rawUrl.includes("?") ||
    rawUrl.includes("#") ||
    credential === undefined ||
    !CREDENTIAL_PATTERN.test(credential) ||
    !Number.isSafeInteger(requestTimeoutMs) ||
    requestTimeoutMs <= 0
  ) {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }

  let baseUrl: URL;
  try {
    baseUrl = new URL(rawUrl);
  } catch {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }
  if (
    !["http:", "https:"].includes(baseUrl.protocol) ||
    baseUrl.hostname.length === 0 ||
    rawUrl.slice(rawUrl.indexOf("://") + 3).split("/", 1)[0]?.includes("@") ||
    baseUrl.username.length > 0 ||
    baseUrl.password.length > 0 ||
    baseUrl.search.length > 0 ||
    baseUrl.hash.length > 0 ||
    baseUrl.pathname !== "/"
  ) {
    preparationFail("BUILD_API_CONFIG_INVALID");
  }

  return { baseUrl, credential, requestTimeoutMs };
}
